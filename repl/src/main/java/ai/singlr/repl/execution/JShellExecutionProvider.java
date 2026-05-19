/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.execution;

import ai.singlr.core.common.RedactionResult;
import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.common.Strings;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.repl.ReplConfig;
import ai.singlr.repl.ReplException;
import ai.singlr.repl.ReplSession;
import ai.singlr.repl.SandboxBindingsListener;
import ai.singlr.session.execution.ExecutionCapabilities;
import ai.singlr.session.execution.ExecutionProvider;
import ai.singlr.session.execution.ExecutionRequest;
import ai.singlr.session.execution.ExecutionResult;
import ai.singlr.session.execution.Runtime;
import ai.singlr.session.execution.SessionStartOutcome;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ExecutionProvider} that dispatches {@link Runtime#JSHELL} requests to a per-session
 * persistent {@link ReplSession}. The persistent state model is the value here:
 *
 * <ul>
 *   <li>{@link #onSessionStart} forks a fresh JShell sandbox subprocess and keeps it alive for the
 *       entire Helios session, keyed by {@link SessionContext#sessionId()}. If the configured
 *       concurrency cap is exhausted the start is refused with {@link
 *       SessionStartOutcome#refuse(String) Refuse} so the agent loop terminates cleanly via {@code
 *       ResultMessage.ErrorProviderUnavailable}.
 *   <li>{@link #execute} routes each {@code Runtime.JSHELL} request to that session's existing
 *       {@code ReplSession}, so variables defined in turn 1 are still visible in turn 7. The
 *       sandbox's JIT warms up once per session, not once per call.
 *   <li>{@link #onSessionEnd} closes the {@code ReplSession} and releases its semaphore permit so
 *       the next session can claim it.
 * </ul>
 *
 * <p>The provider only handles {@link Runtime#JSHELL}; other runtimes return a refusal-shaped
 * {@link ExecutionResult}. Compose with {@link
 * ai.singlr.session.execution.LocalProcessExecutionProvider} (or your own) when you need BASH /
 * PYTHON alongside JSHELL by wrapping multiple providers behind a routing adapter.
 *
 * <h2>Cancellation</h2>
 *
 * The per-call {@link CancellationToken} fires when the surrounding {@code Execute} tool dispatch
 * is cancelled or times out. {@link ReplSession#execute} is synchronous and blocks the calling
 * virtual thread for the duration of the snippet; we register a {@code cancellation.onCancel} that
 * closes the {@code ReplSession} when cancellation fires, killing the sandbox subprocess and
 * causing the in-flight {@code execute} to throw. The session is then unavailable for further calls
 * and {@link #onSessionEnd} will be a no-op (the close already happened). Cancelling a single
 * execute kills the whole session by design — a JShell snippet that's wedged in a tight loop or
 * holding shared state can't be safely resumed without state corruption.
 *
 * <h2>Output redaction</h2>
 *
 * Matches {@link ai.singlr.session.execution.LocalProcessExecutionProvider}: stdout and stderr
 * captured from the sandbox are scrubbed against the configured {@link SecretRegistry} before the
 * result is returned. Per-secret hit counts are surfaced via {@link
 * ExecutionResult#secretRedactionCounts()} so callers can audit how often a sandbox snippet brushed
 * against a registered secret. The default registry is empty, so a deployer who has not registered
 * any secrets pays a no-op pass.
 *
 * <h2>Lifecycle</h2>
 *
 * {@code AutoCloseable}; {@link #close} forcibly destroys every live {@code ReplSession} keyed in
 * the per-session map and removes the JVM shutdown hook the constructor installed.
 */
public final class JShellExecutionProvider implements ExecutionProvider, AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(JShellExecutionProvider.class.getName());

  /**
   * Disambiguated alias for {@code java.lang.Runtime} — the simple name {@code Runtime} resolves to
   * the {@link ai.singlr.session.execution.Runtime} enum used as a dispatch key here.
   */
  private static final java.lang.Runtime JVM = java.lang.Runtime.getRuntime();

  private static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 4;
  private static final Duration DEFAULT_MAX_TIMEOUT = Duration.ofMinutes(5);

  private final ReplConfig replConfig;
  private final int maxConcurrentSessions;
  private final Semaphore sessionPermits;
  private final ExecutionCapabilities capabilities;
  private final SecretRegistry secretRegistry;
  private final Map<String, ReplSession> sessions = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread shutdownHook;
  private final boolean shutdownHookRegistered;
  private final String startupSnippet;

  private JShellExecutionProvider(Builder b) {
    var bareConfig = b.replConfig;
    this.secretRegistry = b.secretRegistry != null ? b.secretRegistry : new SecretRegistry();
    this.replConfig = withRedactingBindingsListener(bareConfig, this.secretRegistry);
    this.maxConcurrentSessions = b.maxConcurrentSessions;
    this.sessionPermits = new Semaphore(maxConcurrentSessions);
    this.capabilities =
        ExecutionCapabilities.newBuilder()
            .withSupportedRuntimes(Set.of(Runtime.JSHELL))
            .withNetworkAllowed(b.networkAllowed)
            .withFilesystemWriteAllowed(b.filesystemWriteAllowed)
            .withMaxTimeout(b.maxTimeout)
            .build();
    this.startupSnippet = b.startupSnippet;
    this.shutdownHook = new Thread(this::reapAllSessions, "helios-jshell-shutdown");
    this.shutdownHookRegistered = b.registerShutdownHook;
    if (shutdownHookRegistered) {
      JVM.addShutdownHook(shutdownHook);
    }
  }

  /**
   * The secret registry this provider redacts stdout / stderr against. Mirrors {@link
   * ai.singlr.session.execution.LocalProcessExecutionProvider#secretRegistry()} so a deployer can
   * wire one shared registry across both providers.
   *
   * @return the configured registry (never null — defaults to an empty registry when {@link
   *     Builder#withSecretRegistry(SecretRegistry)} is not called)
   */
  public SecretRegistry secretRegistry() {
    return secretRegistry;
  }

  /**
   * Convenience factory that builds a provider with default-everything except the {@link
   * ReplConfig}. Equivalent to {@code newBuilder().withReplConfig(config).build()}.
   *
   * @param replConfig the configuration used to spawn each session's sandbox; non-null
   * @return a fresh provider
   * @throws NullPointerException if {@code replConfig} is null
   */
  public static JShellExecutionProvider create(ReplConfig replConfig) {
    Objects.requireNonNull(replConfig, "replConfig must not be null");
    return newBuilder().withReplConfig(replConfig).build();
  }

  /**
   * Convenience factory for the CodeAct-shaped single-session usage: one persistent sandbox per
   * Helios session with the supplied {@link ReplConfig} (carrying host functions registered
   * up-front, e.g. {@code submit}, {@code predict}, {@code __getInput}) and an optional startup
   * snippet executed before the model's first {@code execute_code} call (typically the {@link
   * ai.singlr.repl.InputBindings}-generated input-variable bindings).
   *
   * @param replConfig the configuration used to spawn each session's sandbox; non-null
   * @param startupSnippet the snippet to execute once per sandbox after creation; may be {@code
   *     null} or blank to skip
   * @return a fresh provider
   * @throws NullPointerException if {@code replConfig} is null
   */
  public static JShellExecutionProvider singleSandbox(
      ReplConfig replConfig, String startupSnippet) {
    Objects.requireNonNull(replConfig, "replConfig must not be null");
    return newBuilder().withReplConfig(replConfig).withStartupSnippet(startupSnippet).build();
  }

  /**
   * Start a builder.
   *
   * @return a fresh builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public ExecutionCapabilities capabilities() {
    return capabilities;
  }

  /**
   * The configured maximum concurrent sessions.
   *
   * @return the cap passed via {@link Builder#withMaxConcurrentSessions(int)}
   */
  public int maxConcurrentSessions() {
    return maxConcurrentSessions;
  }

  /**
   * Number of live (open) sessions. Useful for tests and observability.
   *
   * @return non-negative count
   */
  public int liveSessionCount() {
    return sessions.size();
  }

  /**
   * Whether this provider has been closed.
   *
   * @return {@code true} after the first {@link #close}
   */
  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public SessionStartOutcome onSessionStart(SessionContext ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    if (closed.get()) {
      return SessionStartOutcome.refuse("provider is closed");
    }
    if (sessions.containsKey(ctx.sessionId())) {
      return SessionStartOutcome.refuse(
          "session " + ctx.sessionId() + " already has a JShell sandbox bound");
    }
    if (!sessionPermits.tryAcquire()) {
      return SessionStartOutcome.refuse(
          "JShell session pool saturated (cap=" + maxConcurrentSessions + ")");
    }
    ReplSession session;
    try {
      // ReplSession.create takes a Semaphore for its own concurrency accounting; we pass a fresh
      // single-permit semaphore so the ReplSession releases it on close without touching our
      // pool-wide permit (which we manage explicitly above).
      var perSessionPermit = new Semaphore(1);
      session = ReplSession.create(replConfig, perSessionPermit);
    } catch (RuntimeException e) {
      sessionPermits.release();
      return SessionStartOutcome.refuse(
          "failed to spawn JShell sandbox for session " + ctx.sessionId() + ": " + e.getMessage());
    }
    var existing = sessions.putIfAbsent(ctx.sessionId(), session);
    if (existing != null) {
      // Lost the race against another onSessionStart for the same id; close the one we just
      // built and refuse. The pre-check above narrows this window but a concurrent caller could
      // still slip through.
      safeClose(session);
      sessionPermits.release();
      return SessionStartOutcome.refuse(
          "session " + ctx.sessionId() + " already has a JShell sandbox bound");
    }
    if (!Strings.isBlank(startupSnippet)) {
      try {
        var result = session.execute(startupSnippet);
        if (result.exitCode() != 0) {
          var detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
          sessions.remove(ctx.sessionId());
          safeClose(session);
          sessionPermits.release();
          return SessionStartOutcome.refuse(
              "JShell startup snippet failed for session "
                  + ctx.sessionId()
                  + " (exit="
                  + result.exitCode()
                  + "): "
                  + detail);
        }
      } catch (RuntimeException e) {
        sessions.remove(ctx.sessionId());
        safeClose(session);
        sessionPermits.release();
        return SessionStartOutcome.refuse(
            "JShell startup snippet failed for session " + ctx.sessionId() + ": " + e.getMessage());
      }
    }
    // Defense-in-depth: session-scoped cancellation also tears down, in case the host bypasses
    // onSessionEnd (uncaught error during loop construction, crashed cleanup path).
    ctx.cancellation()
        .onCancel(
            () -> {
              var stale = sessions.remove(ctx.sessionId());
              if (stale != null) {
                safeClose(stale);
                sessionPermits.release();
              }
            });
    return SessionStartOutcome.accept();
  }

  @Override
  public void onSessionEnd(SessionContext ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    var session = sessions.remove(ctx.sessionId());
    if (session != null) {
      safeClose(session);
      sessionPermits.release();
    }
  }

  @Override
  public CompletionStage<ExecutionResult> execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    if (closed.get()) {
      return CompletableFuture.failedFuture(new IllegalStateException("provider is closed"));
    }
    if (request.runtime() != Runtime.JSHELL) {
      return CompletableFuture.completedFuture(refusal(request, "runtime not supported"));
    }
    var replSession = sessions.get(session.sessionId());
    if (replSession == null) {
      return CompletableFuture.completedFuture(
          refusal(
              request,
              "no JShell session registered for sessionId="
                  + session.sessionId()
                  + " — onSessionStart not called or already onSessionEnd'd"));
    }
    var future = new CompletableFuture<ExecutionResult>();
    Thread.ofVirtual()
        .name("helios-jshell-" + session.sessionId())
        .start(
            () -> {
              var killed = new AtomicBoolean();
              Runnable killCallback =
                  () -> {
                    if (killed.compareAndSet(false, true)) {
                      safeClose(replSession);
                    }
                  };
              var killRegistration = cancellation.onCancel(killCallback);
              var startNanos = System.nanoTime();
              try {
                var raw = replSession.execute(request.script());
                var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                if (cancellation.isCancelled()) {
                  future.completeExceptionally(
                      new CancellationException(
                          "JShell snippet cancelled: " + cancellation.reason().orElse("")));
                  return;
                }
                var redacted = redactRaw(raw);
                future.complete(
                    new ExecutionResult(
                        raw.exitCode(),
                        redacted.stdout(),
                        redacted.stderr(),
                        elapsed,
                        false,
                        redacted.counts()));
              } catch (ReplException e) {
                if (cancellation.isCancelled()) {
                  future.completeExceptionally(
                      new CancellationException(
                          "JShell snippet cancelled: " + cancellation.reason().orElse("")));
                  return;
                }
                future.complete(
                    refusal(
                        request,
                        "JShell execution failed: "
                            + (e.getMessage() == null
                                ? e.getClass().getSimpleName()
                                : e.getMessage())));
              } catch (Throwable t) {
                future.completeExceptionally(t);
              } finally {
                // Mark the kill callback inert regardless of outcome — the session is in a known
                // post-call state and a later token fire must not double-close. Idempotent close
                // covers the race, but the gate avoids the spurious work. Also detach the
                // callback from the (long-lived) session token's list so per-call references do
                // not accumulate.
                killed.set(true);
                killRegistration.remove();
              }
            });
    return future;
  }

  /**
   * Forcibly close every live session and detach the JVM shutdown hook. Subsequent {@link
   * #onSessionStart} calls refuse with "provider is closed"; subsequent {@link #execute} calls
   * complete exceptionally with {@link IllegalStateException}.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    reapAllSessions();
    if (shutdownHookRegistered) {
      try {
        JVM.removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM already shutting down — hook is firing or has fired.
      }
    }
  }

  private void reapAllSessions() {
    for (var entry : List.copyOf(sessions.entrySet())) {
      sessions.remove(entry.getKey());
      safeClose(entry.getValue());
      sessionPermits.release();
    }
  }

  private RedactedOutput redactRaw(ai.singlr.repl.sandbox.ExecutionResult raw) {
    var redactor = secretRegistry.redactor();
    var stdoutResult = redactor.redact(raw.stdout());
    var stderrResult = redactor.redact(raw.stderr());
    return new RedactedOutput(
        stdoutResult.text(), stderrResult.text(), mergeCounts(stdoutResult, stderrResult));
  }

  /**
   * Return a copy of {@code config} whose {@link SandboxBindingsListener} is wrapped to scrub every
   * binding value through {@code registry}'s redactor before delivery. {@code stdout} and {@code
   * stderr} are redacted upstream by {@link #redactRaw}; without this wrapper, operator telemetry
   * receiving the bindings snapshot would see {@code var apiKey = "sk-..."} verbatim.
   *
   * <p>Returns {@code config} unchanged when no listener is configured.
   */
  private static ReplConfig withRedactingBindingsListener(
      ReplConfig config, SecretRegistry registry) {
    var listener = redactingBindingsListener(registry, config.sandboxBindingsListener());
    if (listener == config.sandboxBindingsListener()) {
      return config;
    }
    return new ReplConfig(
        config.sandboxFactory(),
        config.executionTimeout(),
        config.maxConcurrentSessions(),
        config.hostFunctions(),
        config.maxOutputCharsToModel(),
        listener,
        config.maxBindingValueChars(),
        config.maxBindingSnapshotChars(),
        config.maxExecutedCodeChars());
  }

  /**
   * Build a {@link SandboxBindingsListener} that decorates {@code delegate} with per-value
   * redaction against {@code registry}. Returns {@code null} when {@code delegate} is null
   * (preserves the null-disables semantics of {@link ReplConfig#sandboxBindingsListener()}).
   *
   * <p>Package-private for testing — the wrapping logic is the security-critical bit and is worth
   * exercising directly without needing a full sandbox subprocess.
   */
  static SandboxBindingsListener redactingBindingsListener(
      SecretRegistry registry, SandboxBindingsListener delegate) {
    if (delegate == null) {
      return null;
    }
    return (bindings, result) -> {
      if (bindings.isEmpty()) {
        delegate.onBindings(bindings, result);
        return;
      }
      var redactor = registry.redactor();
      var redacted = new LinkedHashMap<String, String>(bindings.size());
      for (var entry : bindings.entrySet()) {
        var value = entry.getValue();
        redacted.put(entry.getKey(), value == null ? null : redactor.redact(value).text());
      }
      // Preserve declaration order — Map.copyOf would lose it. The listener never mutates.
      delegate.onBindings(Collections.unmodifiableMap(redacted), result);
    };
  }

  private static Map<String, Integer> mergeCounts(RedactionResult a, RedactionResult b) {
    if (a.counts().isEmpty() && b.counts().isEmpty()) {
      return Map.of();
    }
    var merged = new LinkedHashMap<String, Integer>();
    a.counts().forEach((k, v) -> merged.merge(k, v, Integer::sum));
    b.counts().forEach((k, v) -> merged.merge(k, v, Integer::sum));
    return Map.copyOf(merged);
  }

  private record RedactedOutput(String stdout, String stderr, Map<String, Integer> counts) {}

  private static void safeClose(ReplSession session) {
    try {
      session.close();
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "failed to close ReplSession", e);
    }
  }

  private static ExecutionResult refusal(ExecutionRequest request, String reason) {
    return ExecutionResult.refusal(
        "JShellExecutionProvider: " + reason + " (runtime=" + request.runtime() + ")");
  }

  /** Mutable builder for {@link JShellExecutionProvider}. */
  public static final class Builder {

    private ReplConfig replConfig;
    private int maxConcurrentSessions = DEFAULT_MAX_CONCURRENT_SESSIONS;
    private Duration maxTimeout = DEFAULT_MAX_TIMEOUT;
    private boolean networkAllowed = false;
    private boolean filesystemWriteAllowed = false;
    private boolean registerShutdownHook = true;
    private String startupSnippet;
    private SecretRegistry secretRegistry;

    private Builder() {}

    /**
     * Set the shared {@link SecretRegistry} the provider redacts stdout / stderr against. Mirrors
     * the {@link
     * ai.singlr.session.execution.LocalProcessExecutionProvider.Builder#withSecretRegistry
     * LocalProcessExecutionProvider} setter so a deployer can wire one shared registry across both
     * providers. Defaults to a fresh empty registry — usable but invisible to other tools.
     *
     * @param secretRegistry the registry; non-null
     * @return this builder
     * @throws NullPointerException if {@code secretRegistry} is null
     */
    public Builder withSecretRegistry(SecretRegistry secretRegistry) {
      this.secretRegistry =
          Objects.requireNonNull(secretRegistry, "secretRegistry must not be null");
      return this;
    }

    /**
     * Set the {@link ReplConfig} used to spawn each per-session sandbox. Required.
     *
     * @param replConfig non-null config
     * @return this builder
     * @throws NullPointerException if {@code replConfig} is null
     */
    public Builder withReplConfig(ReplConfig replConfig) {
      this.replConfig = Objects.requireNonNull(replConfig, "replConfig must not be null");
      return this;
    }

    /**
     * Cap on concurrent live sessions. Each session holds one sandbox subprocess; the cap limits
     * how many subprocess slots the provider commits to. Sessions past the cap have their {@code
     * onSessionStart} refused. Defaults to 4.
     *
     * @param n positive cap
     * @return this builder
     * @throws IllegalArgumentException if {@code n < 1}
     */
    public Builder withMaxConcurrentSessions(int n) {
      if (n < 1) {
        throw new IllegalArgumentException("maxConcurrentSessions must be at least 1, got " + n);
      }
      this.maxConcurrentSessions = n;
      return this;
    }

    /**
     * Capability advertised through {@link ExecutionCapabilities#maxTimeout()}. Informational — the
     * actual per-snippet timeout comes from {@link ReplConfig#executionTimeout()}. Defaults to 5
     * minutes.
     *
     * @param maxTimeout strictly positive duration
     * @return this builder
     */
    public Builder withMaxTimeout(Duration maxTimeout) {
      Objects.requireNonNull(maxTimeout, "maxTimeout must not be null");
      if (maxTimeout.isZero() || maxTimeout.isNegative()) {
        throw new IllegalArgumentException(
            "maxTimeout must be strictly positive, got " + maxTimeout);
      }
      this.maxTimeout = maxTimeout;
      return this;
    }

    /**
     * Network capability flag. Informational; JShell sandbox enforcement happens at the sandbox
     * level. Defaults to {@code false}.
     *
     * @param allowed whether the sandbox can reach networks
     * @return this builder
     */
    public Builder withNetworkAllowed(boolean allowed) {
      this.networkAllowed = allowed;
      return this;
    }

    /**
     * Filesystem-write capability flag. Informational. Defaults to {@code false}.
     *
     * @param allowed whether the sandbox can write files
     * @return this builder
     */
    public Builder withFilesystemWriteAllowed(boolean allowed) {
      this.filesystemWriteAllowed = allowed;
      return this;
    }

    /**
     * Whether to install a JVM shutdown hook that reaps every live sandbox on host JVM exit.
     * Defaults to {@code true}. Pass {@code false} for tests where the hook would leak across test
     * runs.
     *
     * @param register true to install the shutdown hook
     * @return this builder
     */
    public Builder withShutdownHook(boolean register) {
      this.registerShutdownHook = register;
      return this;
    }

    /**
     * A JShell snippet executed once on every new sandbox, immediately after {@link
     * ReplSession#create} returns. Use this to install input bindings, custom prelude declarations,
     * or any other per-session JShell state the agent should see before its first {@code
     * execute_code} call.
     *
     * <p>If the snippet fails (non-zero exit or thrown exception) the session start is refused with
     * {@link SessionStartOutcome#refuse(String)}, the partially-spawned sandbox is closed, and the
     * pool permit is released — so a broken startup snippet cannot tombstone the provider.
     *
     * @param snippet the snippet to run; {@code null} or blank disables the feature
     * @return this builder
     */
    public Builder withStartupSnippet(String snippet) {
      this.startupSnippet = snippet;
      return this;
    }

    /**
     * Build the immutable provider.
     *
     * @return the provider
     * @throws IllegalStateException if {@code replConfig} was never set
     */
    public JShellExecutionProvider build() {
      if (replConfig == null) {
        throw new IllegalStateException("replConfig is required");
      }
      return new JShellExecutionProvider(this);
    }
  }
}
