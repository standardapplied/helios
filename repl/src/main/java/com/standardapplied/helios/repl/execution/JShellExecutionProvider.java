/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.repl.execution;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.ReplSession;
import com.standardapplied.helios.session.execution.ExecutionCapabilities;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * com.standardapplied.helios.session.execution.LocalProcessExecutionProvider} (or your own) when
 * you need BASH / PYTHON alongside JSHELL by wrapping multiple providers behind a routing adapter.
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
 * Matches {@link com.standardapplied.helios.session.execution.LocalProcessExecutionProvider}:
 * stdout and stderr captured from the sandbox are scrubbed against the configured {@link
 * SecretRegistry} before the result is returned. Per-secret hit counts are surfaced via {@link
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

  /**
   * Disambiguated alias for {@code java.lang.Runtime} — the simple name {@code Runtime} resolves to
   * the {@link com.standardapplied.helios.session.execution.Runtime} enum used as a dispatch key
   * here.
   */
  private static final java.lang.Runtime JVM = java.lang.Runtime.getRuntime();

  private static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 4;
  private static final Duration DEFAULT_MAX_TIMEOUT = Duration.ofMinutes(5);

  private final SandboxPool pool;
  private final ExecutionCapabilities capabilities;
  private final SecretRegistry secretRegistry;
  private final OutputRedaction redaction;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread shutdownHook;
  private final boolean shutdownHookRegistered;

  private JShellExecutionProvider(Builder b) {
    this.secretRegistry = b.secretRegistry != null ? b.secretRegistry : new SecretRegistry();
    this.redaction = new OutputRedaction(secretRegistry);
    this.pool =
        new SandboxPool(
            redaction.withRedactingBindingsListener(b.replConfig),
            b.startupSnippet,
            b.maxConcurrentSessions);
    this.capabilities =
        ExecutionCapabilities.newBuilder()
            .withSupportedRuntimes(Set.of(Runtime.JSHELL))
            .withNetworkAllowed(b.networkAllowed)
            .withFilesystemWriteAllowed(b.filesystemWriteAllowed)
            .withMaxTimeout(b.maxTimeout)
            .build();
    this.shutdownHook = new Thread(pool::close, "helios-jshell-shutdown");
    this.shutdownHookRegistered = b.registerShutdownHook;
    if (shutdownHookRegistered) {
      JVM.addShutdownHook(shutdownHook);
    }
  }

  /**
   * The secret registry this provider redacts stdout / stderr against. Mirrors {@link
   * com.standardapplied.helios.session.execution.LocalProcessExecutionProvider#secretRegistry()} so
   * a deployer can wire one shared registry across both providers.
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
    return pool.maxConcurrentSessions();
  }

  /**
   * Number of live (open) sessions. Useful for tests and observability.
   *
   * @return non-negative count
   */
  public int liveSessionCount() {
    return pool.liveCount();
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
    return pool.start(ctx);
  }

  @Override
  public void onSessionEnd(SessionContext ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    pool.end(ctx.sessionId());
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
      return CompletableFuture.completedFuture(
          SessionExecution.refusal(request, "runtime not supported"));
    }
    var replSession = pool.session(session.sessionId());
    if (replSession == null) {
      return CompletableFuture.completedFuture(
          SessionExecution.refusal(
              request,
              "no JShell session registered for sessionId="
                  + session.sessionId()
                  + " — onSessionStart not called or already onSessionEnd'd"));
    }
    var timeout =
        request.timeout().compareTo(capabilities.maxTimeout()) > 0
            ? capabilities.maxTimeout()
            : request.timeout();
    return SessionExecution.start(
        session.sessionId(), replSession, request, timeout, cancellation, redaction);
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
    pool.close();
    if (shutdownHookRegistered) {
      try {
        JVM.removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM already shutting down — hook is firing or has fired.
      }
    }
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
     * com.standardapplied.helios.session.execution.LocalProcessExecutionProvider.Builder#withSecretRegistry
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
     * ReplSession#create} returns. Use this to install custom declarations or any other per-session
     * JShell state the agent should see before its first {@code Execute} call.
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
