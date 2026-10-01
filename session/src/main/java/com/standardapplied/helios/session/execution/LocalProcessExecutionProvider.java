/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import ai.singlr.core.common.RedactionResult;
import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.common.Validate;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.core.tool.CommandGrant;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Host-process {@link ExecutionProvider} backed by direct {@link ProcessBuilder} dispatch. Each
 * supported {@link Runtime} pins a binary path at build time (resolved against the supplied {@code
 * PATH}) and is invoked with an argv shape configured by a {@link RuntimeHandler}.
 *
 * <h2>Security model</h2>
 *
 * Matches the {@link CommandGrant} primitive that backs single-binary tool grants:
 *
 * <ul>
 *   <li><b>Binary path pinned at build time.</b> A hostile {@code PATH} change later cannot shadow
 *       the resolved binary.
 *   <li><b>Always argv array, never shell.</b> {@code bash -c '<script>'} runs in an argv slot
 *       under {@code ProcessBuilder} — no surrounding shell interprets metacharacters.
 *   <li><b>Environment cleared then injected.</b> The child does not inherit the JVM's environment;
 *       only the granted {@code PATH} plus the request's {@link ExecutionRequest#environment()} is
 *       visible.
 *   <li><b>Stdin closed (or fed once and closed).</b> No interactive surprises.
 *   <li><b>Per-call temp cwd by default.</b> If the request omits {@link
 *       ExecutionRequest#workingDirectory()}, a fresh temp directory is created and deleted after
 *       the invocation.
 *   <li><b>Output redaction is mandatory.</b> Both stdout and stderr are scrubbed against the
 *       provider's {@link SecretRegistry} before they leave the host process.
 *   <li><b>Process tree reaped on timeout / cancellation.</b> Descendants are destroyed alongside
 *       the direct child.
 *   <li><b>Concurrency-bounded.</b> Per-provider {@link Semaphore}; overflows wait rather than fail
 *       because the {@code Execute} tool's surrounding {@link
 *       ai.singlr.session.ConcurrencyLimits#maxConcurrentExecutions() executionPermits} pool
 *       already gates the loop.
 * </ul>
 *
 * <h2>Phase 5 scope</h2>
 *
 * The default factory {@link #defaultPosix(SecretRegistry)} wires {@link Runtime#BASH} (to {@code
 * bash}) and {@link Runtime#PYTHON} (to {@code python3}). Other {@link Runtime} values stay
 * declared but unhandled — customers register their own handlers via {@link
 * Builder#withRuntime(Runtime, RuntimeHandler)}.
 *
 * <p>{@code Runtime.JSHELL} is intentionally absent: Phase 6 wires the existing {@code JvmSandbox}
 * / {@code ReplSession} substrate behind it. {@code Runtime.SQL} is declared in the enum but needs
 * a separate JDBC-allowlist provider — a follow-up slice.
 *
 * <h2>Lifecycle</h2>
 *
 * The provider is {@link AutoCloseable}; {@link #close()} forcibly destroys every in-flight
 * subprocess and removes the JVM shutdown hook the constructor installed. The shutdown hook is
 * defense-in-depth: if the host JVM exits without calling {@code close()} (uncaught {@code Error},
 * {@code System.exit(...)}, OS signal), the hook still kills every child process so the host cannot
 * leave orphaned subprocesses behind. After {@code close()}, calls to {@link
 * #execute(SessionContext, ExecutionRequest, CancellationToken) execute} complete exceptionally
 * with an {@link IllegalStateException}.
 *
 * <p>The provider does NOT close its {@link SecretRegistry} — the registry is typically shared
 * across many tools and the caller owns its lifecycle.
 */
public final class LocalProcessExecutionProvider implements ExecutionProvider, AutoCloseable {

  private static final Logger LOGGER =
      Logger.getLogger(LocalProcessExecutionProvider.class.getName());

  /**
   * Disambiguated alias for {@code java.lang.Runtime} — the simple name {@code Runtime} resolves to
   * the {@link ai.singlr.session.execution.Runtime} enum in this package.
   */
  private static final java.lang.Runtime JVM = java.lang.Runtime.getRuntime();

  private static final int DEFAULT_MAX_OUTPUT_BYTES = 50_000;
  private static final int DEFAULT_MAX_CONCURRENT = 4;
  private static final Duration DEFAULT_MAX_TIMEOUT = Duration.ofMinutes(5);
  private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";
  private static final byte[] TRUNCATION_MARKER =
      "\n[truncated: output exceeded cap]".getBytes(StandardCharsets.US_ASCII);

  private final Map<Runtime, RuntimeHandler> handlers;
  private final SecretRegistry secretRegistry;
  private final ExecutionCapabilities capabilities;
  private final String path;
  private final int maxOutputBytes;
  private final Semaphore concurrency;
  private final Set<Process> inflight = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread shutdownHook;
  private final boolean shutdownHookRegistered;

  private LocalProcessExecutionProvider(Builder b) {
    this.handlers = Map.copyOf(b.handlers);
    this.secretRegistry = b.secretRegistry != null ? b.secretRegistry : new SecretRegistry();
    this.path = b.path != null ? b.path : DEFAULT_PATH;
    this.maxOutputBytes = b.maxOutputBytes;
    this.concurrency = new Semaphore(b.maxConcurrent);
    this.capabilities =
        ExecutionCapabilities.newBuilder()
            .withSupportedRuntimes(handlers.keySet())
            .withNetworkAllowed(b.networkAllowed)
            .withFilesystemWriteAllowed(b.filesystemWriteAllowed)
            .withMaxTimeout(b.maxTimeout)
            .build();
    this.shutdownHook = new Thread(this::reapInflight, "helios-exec-shutdown");
    this.shutdownHookRegistered = b.registerShutdownHook;
    if (shutdownHookRegistered) {
      JVM.addShutdownHook(shutdownHook);
    }
  }

  /**
   * Curated POSIX factory: registers {@link Runtime#BASH} to {@code bash} and {@link
   * Runtime#PYTHON} to {@code python3} using {@code -c} script dispatch. Mirrors the binaries the
   * spec calls out as the Phase 5 acceptance baseline.
   *
   * @param secretRegistry the registry used for stdout/stderr redaction; non-null
   * @return a fresh provider
   * @throws NullPointerException if {@code secretRegistry} is null
   * @throws IllegalStateException if either binary cannot be resolved on the host {@code PATH}
   */
  public static LocalProcessExecutionProvider defaultPosix(SecretRegistry secretRegistry) {
    Objects.requireNonNull(secretRegistry, "secretRegistry must not be null");
    return newBuilder()
        .withSecretRegistry(secretRegistry)
        .withRuntime(Runtime.BASH, RuntimeHandler.dashC("bash"))
        .withRuntime(Runtime.PYTHON, RuntimeHandler.dashC("python3"))
        .build();
  }

  /**
   * Start a builder.
   *
   * @return a fresh builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** The secret registry this provider redacts against. */
  public SecretRegistry secretRegistry() {
    return secretRegistry;
  }

  @Override
  public ExecutionCapabilities capabilities() {
    return capabilities;
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
    var future = new CompletableFuture<ExecutionResult>();
    Thread.ofVirtual()
        .name("helios-exec-" + session.sessionId() + "-" + request.runtime())
        .start(
            () -> {
              try {
                future.complete(runSync(request, cancellation));
              } catch (CancellationException e) {
                future.completeExceptionally(e);
              } catch (Throwable t) {
                future.completeExceptionally(t);
              }
            });
    return future;
  }

  private ExecutionResult runSync(ExecutionRequest request, CancellationToken cancellation) {
    var handler = handlers.get(request.runtime());
    if (handler == null) {
      return ExecutionResult.refusal(
          "runtime "
              + request.runtime()
              + " is not supported by this provider (supported: "
              + capabilities.supportedRuntimes()
              + ")");
    }
    var effectiveTimeout =
        request.timeout().compareTo(capabilities.maxTimeout()) > 0
            ? capabilities.maxTimeout()
            : request.timeout();

    cancellation.throwIfCancelled();
    // Cancellation cannot directly unblock Semaphore.acquire() — wire it to interrupt the worker
    // thread so a session shutdown during a permit wait propagates as InterruptedException.
    var worker = Thread.currentThread();
    var acquireDone = new AtomicBoolean();
    Runnable acquireInterruptCallback =
        () -> {
          if (!acquireDone.get()) {
            worker.interrupt();
          }
        };
    var acquireRegistration = cancellation.onCancel(acquireInterruptCallback);
    try {
      concurrency.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException(
          "interrupted while acquiring permit for " + request.runtime());
    } finally {
      acquireDone.set(true);
      // Detach the per-acquire callback so a long-lived (per-session) token does not accumulate
      // one entry per execute call.
      acquireRegistration.remove();
    }
    Path effectiveCwd = null;
    boolean ownCwd = false;
    try {
      if (closed.get()) {
        throw new CancellationException(
            "provider closed before " + request.runtime() + " could start");
      }
      if (request.workingDirectory() != null) {
        effectiveCwd = request.workingDirectory();
      } else {
        effectiveCwd = Files.createTempDirectory("helios-exec-");
        ownCwd = true;
      }
      return runProcess(request, handler, effectiveCwd, effectiveTimeout, cancellation);
    } catch (IOException e) {
      return ExecutionResult.refusal(
          "I/O error launching " + request.runtime() + ": " + e.getMessage());
    } finally {
      concurrency.release();
      if (ownCwd) {
        deleteRecursively(effectiveCwd);
      }
    }
  }

  private ExecutionResult runProcess(
      ExecutionRequest request,
      RuntimeHandler handler,
      Path cwd,
      Duration timeout,
      CancellationToken cancellation)
      throws IOException {
    var argv = handler.buildArgv(request);
    var pb = new ProcessBuilder(argv);
    pb.environment().clear();
    pb.environment().put("PATH", path);
    pb.environment().putAll(request.environment());
    pb.directory(cwd.toFile());
    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
    var startNanos = System.nanoTime();
    var proc = pb.start();
    inflight.add(proc);
    // Once-only kill: a successful exit removes the callback's effect so a later cancel after
    // many calls does not iterate an ever-growing list of stale process refs and does not try to
    // destroy an already-reaped process.
    var killed = new AtomicBoolean();
    Runnable killCallback =
        () -> {
          if (killed.compareAndSet(false, true)) {
            proc.descendants().forEach(ProcessHandle::destroy);
            proc.destroy();
          }
        };
    var killRegistration = cancellation.onCancel(killCallback);
    var stdinThread = startStdinFeeder(proc, request.stdin().orElse(null));
    var stdoutSink = new BoundedSink(maxOutputBytes);
    var stderrSink = new BoundedSink(maxOutputBytes);
    var t1 = Thread.startVirtualThread(() -> drain(proc.getInputStream(), stdoutSink));
    var t2 = Thread.startVirtualThread(() -> drain(proc.getErrorStream(), stderrSink));
    boolean exited;
    try {
      exited = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      killCallback.run();
      reapForcibly(proc);
      joinQuietly(stdinThread);
      joinQuietly(t1);
      joinQuietly(t2);
      inflight.remove(proc);
      killRegistration.remove();
      throw new CancellationException(
          "interrupted while waiting for " + request.runtime() + " process");
    }
    var timedOut = !exited;
    if (timedOut) {
      killCallback.run();
      reapForcibly(proc);
    }
    joinQuietly(stdinThread);
    joinQuietly(t1);
    joinQuietly(t2);
    inflight.remove(proc);
    // Mark the per-call kill callback inert so any later token cancellation does not retain or
    // act on this now-reaped process, then detach from the (possibly long-lived) token's list so
    // the callback reference does not accumulate across many calls.
    killed.set(true);
    killRegistration.remove();
    // If the token fired during execution and the process did NOT time out on its own, the
    // process was killed by cancellation — surface that as a CancellationException rather than a
    // misleading "normal exit 143" ExecutionResult. Timed-out runs still return a result
    // (timedOut=true) so callers can distinguish the two terminal causes.
    if (!timedOut && cancellation.isCancelled()) {
      throw new CancellationException(
          "execution of " + request.runtime() + " cancelled: " + cancellation.reason().orElse(""));
    }
    var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    var exitCode = timedOut ? -1 : proc.exitValue();
    var redactor = secretRegistry.redactor();
    var stdoutResult = redactor.redact(stdoutSink.bytes());
    var stderrResult = redactor.redact(stderrSink.bytes());
    return new ExecutionResult(
        exitCode,
        stdoutResult.text(),
        stderrResult.text(),
        elapsed,
        timedOut,
        mergeCounts(stdoutResult, stderrResult));
  }

  private static Thread startStdinFeeder(Process proc, String stdin) {
    return Thread.startVirtualThread(
        () -> {
          try (OutputStream out = proc.getOutputStream()) {
            if (stdin != null) {
              out.write(stdin.getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
          } catch (IOException ignored) {
            // Child closed its stdin or exited before we finished writing — both expected.
          }
        });
  }

  private static void reapForcibly(Process proc) {
    try {
      if (!proc.waitFor(2, TimeUnit.SECONDS)) {
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        proc.destroyForcibly();
        proc.waitFor(1, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void drain(InputStream in, BoundedSink sink) {
    var buf = new byte[8192];
    try (in) {
      int n;
      while ((n = in.read(buf)) >= 0) {
        sink.write(buf, 0, n);
      }
    } catch (IOException ignored) {
      // Stream closed by process termination.
    }
  }

  private static void joinQuietly(Thread t) {
    try {
      t.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Whether this provider has been closed.
   *
   * @return {@code true} after the first successful {@link #close()}
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Snapshot of in-flight child processes — diagnostics only. The returned count is approximate
   * because subprocess completion is racy with the caller's read.
   *
   * @return non-negative count
   */
  public int inflightCount() {
    return inflight.size();
  }

  /**
   * Close the provider: destroy every in-flight subprocess and remove the JVM shutdown hook. After
   * close, future {@link #execute(SessionContext, ExecutionRequest, CancellationToken) execute}
   * calls complete exceptionally with an {@link IllegalStateException}. Safe to call from any
   * thread; subsequent calls are no-ops.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    reapInflight();
    if (shutdownHookRegistered) {
      try {
        JVM.removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM is already shutting down — the hook is running or has run.
      }
    }
  }

  private void reapInflight() {
    for (var p : List.copyOf(inflight)) {
      try {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
          p.descendants().forEach(ProcessHandle::destroyForcibly);
          p.destroyForcibly();
          p.waitFor(1, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "failed to reap subprocess on provider close", e);
      } finally {
        inflight.remove(p);
      }
    }
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

  private static void deleteRecursively(Path root) {
    try (var stream = Files.walk(root)) {
      stream
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // Best-effort cleanup.
                }
              });
    } catch (IOException ignored) {
      // Best-effort cleanup.
    }
  }

  /**
   * Per-runtime argv shape. Implementations pin a binary at construction time and translate an
   * {@link ExecutionRequest} into the argv list the host process is invoked with.
   *
   * <p>The provided {@link #dashC(String)} factory covers the common "{@code <bin> -c <script>
   * [args...]}" shape used by {@code bash} and {@code python3}; bespoke runtimes (e.g. {@code
   * Rscript -e} or {@code node -e}) can plug a custom handler.
   */
  public interface RuntimeHandler {

    /**
     * Build the argv for one invocation. The returned list is passed verbatim to {@link
     * ProcessBuilder}.
     *
     * @param request the request; non-null
     * @return non-empty list whose first element is the absolute binary path
     */
    List<String> buildArgv(ExecutionRequest request);

    /**
     * A handler that runs {@code <binary> -c <script> [args...]} — the conventional one-shot script
     * dispatch supported by {@code bash}, {@code zsh}, {@code python3}, and many others. The binary
     * is resolved against the host {@code PATH} at construction time.
     *
     * @param binarySpec absolute path or basename of the binary; non-blank
     * @return a fresh handler
     * @throws NullPointerException if {@code binarySpec} is null
     * @throws IllegalArgumentException if {@code binarySpec} is blank
     * @throws IllegalStateException if the binary cannot be resolved on the host {@code PATH}
     */
    static RuntimeHandler dashC(String binarySpec) {
      Validate.notBlank("binarySpec", binarySpec);
      var resolved = CommandGrant.resolveBinary(binarySpec, System.getenv("PATH"));
      return request -> {
        var argv = new ArrayList<String>(3 + request.args().size());
        argv.add(resolved.toString());
        argv.add("-c");
        argv.add(request.script());
        argv.addAll(request.args());
        return List.copyOf(argv);
      };
    }
  }

  /** Bounded output sink — drops bytes past the cap and appends a truncation marker. */
  private static final class BoundedSink {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int max;
    private boolean truncated;

    BoundedSink(int max) {
      this.max = max;
    }

    synchronized void write(byte[] buf, int off, int len) {
      var remaining = max - out.size();
      if (remaining <= 0) {
        truncated = true;
        return;
      }
      var toWrite = Math.min(len, remaining);
      out.write(buf, off, toWrite);
      if (toWrite < len) {
        truncated = true;
      }
    }

    synchronized byte[] bytes() {
      var raw = out.toByteArray();
      if (!truncated) {
        return raw;
      }
      var combined = new byte[raw.length + TRUNCATION_MARKER.length];
      System.arraycopy(raw, 0, combined, 0, raw.length);
      System.arraycopy(TRUNCATION_MARKER, 0, combined, raw.length, TRUNCATION_MARKER.length);
      return combined;
    }
  }

  /** Mutable builder for {@link LocalProcessExecutionProvider}. */
  public static final class Builder {

    private final Map<Runtime, RuntimeHandler> handlers = new EnumMap<>(Runtime.class);
    private SecretRegistry secretRegistry;
    private String path;
    private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
    private int maxConcurrent = DEFAULT_MAX_CONCURRENT;
    private Duration maxTimeout = DEFAULT_MAX_TIMEOUT;
    private boolean networkAllowed = true;
    private boolean filesystemWriteAllowed = true;
    private boolean registerShutdownHook = true;

    private Builder() {}

    /**
     * Register a runtime handler. Overrides any previously-registered handler for the same runtime.
     *
     * @param runtime the runtime; non-null
     * @param handler the handler; non-null
     * @return this builder
     * @throws NullPointerException if any argument is null
     */
    public Builder withRuntime(Runtime runtime, RuntimeHandler handler) {
      Objects.requireNonNull(runtime, "runtime must not be null");
      Objects.requireNonNull(handler, "handler must not be null");
      this.handlers.put(runtime, handler);
      return this;
    }

    /**
     * Set the shared {@link SecretRegistry} the provider redacts stdout / stderr against. Defaults
     * to a fresh empty registry — usable but invisible to the rest of the session, so callers that
     * want cross-tool redaction should wire the session-shared registry here.
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
     * Override the {@code PATH} environment variable injected into child processes. Defaults to a
     * sane minimal path ({@code /usr/local/bin:/usr/bin:/bin}).
     *
     * @param path the {@code PATH} value; non-blank
     * @return this builder
     * @throws NullPointerException if {@code path} is null
     * @throws IllegalArgumentException if {@code path} is blank
     */
    public Builder withPath(String path) {
      this.path = Validate.notBlank("path", path);
      return this;
    }

    /**
     * Cap on captured stdout and stderr (each, in bytes). Output past the cap is dropped and a
     * truncation marker is appended. Defaults to 50,000 bytes.
     *
     * @param bytes byte cap; at least 1024
     * @return this builder
     * @throws IllegalArgumentException if {@code bytes < 1024}
     */
    public Builder withMaxOutputBytes(int bytes) {
      this.maxOutputBytes = Validate.atLeast("maxOutputBytes", bytes, 1024);
      return this;
    }

    /**
     * Cap on concurrent invocations. Defaults to 4.
     *
     * @param n positive concurrency cap
     * @return this builder
     * @throws IllegalArgumentException if {@code n < 1}
     */
    public Builder withMaxConcurrent(int n) {
      this.maxConcurrent = Validate.atLeast("maxConcurrent", n, 1);
      return this;
    }

    /**
     * Upper bound on any single invocation's timeout. Requests with a longer timeout are clamped to
     * this value. Defaults to 5 minutes.
     *
     * @param maxTimeout strictly positive duration
     * @return this builder
     * @throws NullPointerException if {@code maxTimeout} is null
     * @throws IllegalArgumentException if {@code maxTimeout} is zero or negative
     */
    public Builder withMaxTimeout(Duration maxTimeout) {
      this.maxTimeout = Validate.positiveDuration("maxTimeout", maxTimeout);
      return this;
    }

    /**
     * Capability flag advertised through {@link ExecutionCapabilities#networkAllowed()}. The
     * provider does not enforce this — it's informational metadata for hooks / consumers.
     *
     * @param networkAllowed whether child processes can reach networks
     * @return this builder
     */
    public Builder withNetworkAllowed(boolean networkAllowed) {
      this.networkAllowed = networkAllowed;
      return this;
    }

    /**
     * Capability flag advertised through {@link ExecutionCapabilities#filesystemWriteAllowed()}.
     * Informational; the provider does not enforce filesystem isolation.
     *
     * @param filesystemWriteAllowed whether child processes can write files
     * @return this builder
     */
    public Builder withFilesystemWriteAllowed(boolean filesystemWriteAllowed) {
      this.filesystemWriteAllowed = filesystemWriteAllowed;
      return this;
    }

    /**
     * Whether to install a JVM shutdown hook that reaps any in-flight subprocess on host JVM exit.
     * Defaults to {@code true}. Pass {@code false} for tests or short-lived utilities where the
     * hook itself would leak across test invocations.
     *
     * @param register true to install the shutdown hook
     * @return this builder
     */
    public Builder withShutdownHook(boolean register) {
      this.registerShutdownHook = register;
      return this;
    }

    /**
     * Build the immutable provider.
     *
     * @return the provider
     * @throws IllegalStateException if no runtime handlers were registered
     */
    public LocalProcessExecutionProvider build() {
      if (handlers.isEmpty()) {
        throw new IllegalStateException(
            "at least one runtime handler must be registered via withRuntime(...)");
      }
      return new LocalProcessExecutionProvider(this);
    }
  }
}
