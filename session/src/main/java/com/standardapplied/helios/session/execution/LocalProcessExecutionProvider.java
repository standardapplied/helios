/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import com.standardapplied.helios.core.common.Validate;
import com.standardapplied.helios.core.process.BinaryResolver;
import com.standardapplied.helios.core.process.BoundedProcess;
import com.standardapplied.helios.core.process.ProcessOutcome;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.CommandGrant;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-process {@link ExecutionProvider} backed by direct {@link BoundedProcess} dispatch. Each
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
 *       under {@link BoundedProcess} — no surrounding shell interprets metacharacters.
 *   <li><b>Environment cleared then injected.</b> The child does not inherit the JVM's environment;
 *       only the granted {@code PATH} plus the request's {@link ExecutionRequest#environment()} is
 *       visible.
 *   <li><b>Stdin closed (or fed once and closed).</b> No interactive surprises.
 *   <li><b>Per-call temp cwd by default.</b> If the request omits {@link
 *       ExecutionRequest#workingDirectory()}, a fresh temp directory is created and deleted after
 *       the invocation.
 *   <li><b>Output is redacted by the session.</b> Stdout and stderr are returned raw and the
 *       session redacts the {@code Execute} tool's output against its secret registry. A stream cut
 *       at the output cap loses the start of a secret registered there that the cut went through,
 *       so the session's redaction cannot miss it.
 *   <li><b>Process tree reaped on timeout, cancellation and interrupt.</b> Descendants are
 *       destroyed alongside the direct child; one still alive {@link BoundedProcess#GRACE} after
 *       the kill is destroyed forcibly.
 *   <li><b>Concurrency-bounded.</b> Per-provider {@link Semaphore}; overflows wait rather than fail
 *       because the {@code Execute} tool's surrounding {@link
 *       com.standardapplied.helios.session.ConcurrencyLimits#maxConcurrentExecutions()
 *       executionPermits} pool already gates the loop.
 * </ul>
 *
 * <p>The provider does not isolate the network or the filesystem: a child reaches whatever the host
 * process reaches. Isolate the host process itself (container, VM) where that matters.
 *
 * <h2>Dispatch</h2>
 *
 * {@link #execute(SessionContext, ExecutionRequest, CancellationToken) execute} runs on the
 * caller's thread. Interrupting that thread, or cancelling the call's token, kills the child and
 * its descendants and makes {@code execute} throw a {@link CancellationException}; a token fired
 * while the call waits for a permit ends the wait the same way. A request's timeout is clamped to
 * {@link Builder#withMaxTimeout(Duration)}.
 *
 * <h2>Phase 5 scope</h2>
 *
 * The default factory {@link #defaultPosix()} wires {@link Runtime#BASH} (to {@code bash}) and
 * {@link Runtime#PYTHON} (to {@code python3}). Other {@link Runtime} values stay declared but
 * unhandled — customers register their own handlers via {@link Builder#withRuntime(Runtime,
 * RuntimeHandler)}.
 *
 * <p>{@code Runtime.JSHELL} is intentionally absent: {@code helios-repl}'s {@code
 * JShellExecutionProvider} serves it. {@code Runtime.SQL} is declared in the enum but needs a
 * separate JDBC-allowlist provider — a follow-up slice.
 *
 * <h2>Lifecycle</h2>
 *
 * The provider is {@link AutoCloseable}; {@link #close()} forcibly destroys every in-flight
 * subprocess and removes the JVM shutdown hook the constructor installed. The shutdown hook is
 * defense-in-depth: if the host JVM exits without calling {@code close()} (uncaught {@code Error},
 * {@code System.exit(...)}, OS signal), the hook still kills every child process so the host cannot
 * leave orphaned subprocesses behind. After {@code close()}, calls to {@link
 * #execute(SessionContext, ExecutionRequest, CancellationToken) execute} throw an {@link
 * IllegalStateException}.
 */
public final class LocalProcessExecutionProvider implements ExecutionProvider, AutoCloseable {

  private static final int DEFAULT_MAX_OUTPUT_BYTES = 50_000;
  private static final int DEFAULT_MAX_CONCURRENT = 4;
  private static final Duration DEFAULT_MAX_TIMEOUT = Duration.ofMinutes(5);
  private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";

  private final Map<Runtime, RuntimeHandler> handlers;
  private final Duration maxTimeout;
  private final String path;
  private final int maxOutputBytes;
  private final Semaphore concurrency;
  private final InflightProcesses inflight;

  private LocalProcessExecutionProvider(Builder b) {
    this.handlers = Collections.unmodifiableMap(new EnumMap<>(b.handlers));
    this.path = b.path != null ? b.path : DEFAULT_PATH;
    this.maxOutputBytes = b.maxOutputBytes;
    this.concurrency = new Semaphore(b.maxConcurrent);
    this.maxTimeout = b.maxTimeout;
    this.inflight = new InflightProcesses(b.registerShutdownHook);
  }

  /**
   * Curated POSIX factory: registers {@link Runtime#BASH} to {@code bash} and {@link
   * Runtime#PYTHON} to {@code python3} using {@code -c} script dispatch. Mirrors the binaries the
   * spec calls out as the Phase 5 acceptance baseline.
   *
   * @return a fresh provider
   * @throws IllegalStateException if either binary cannot be resolved on the host {@code PATH}
   */
  public static LocalProcessExecutionProvider defaultPosix() {
    return newBuilder()
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

  @Override
  public Set<Runtime> supportedRuntimes() {
    return handlers.keySet();
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalStateException if the provider is closed
   */
  @Override
  public ExecutionResult execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    if (inflight.isClosed()) {
      throw new IllegalStateException("provider is closed");
    }
    var handler = handlers.get(request.runtime());
    if (handler == null) {
      return ExecutionResult.refusal(
          "runtime "
              + request.runtime()
              + " is not supported by this provider (supported: "
              + handlers.keySet()
              + ")");
    }
    var effectiveTimeout =
        request.timeout().compareTo(maxTimeout) > 0 ? maxTimeout : request.timeout();

    cancellation.throwIfCancelled();
    // Cancellation cannot directly unblock Semaphore.acquire() — wire it to interrupt the calling
    // thread so a cancellation during a permit wait propagates as InterruptedException.
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
    try {
      if (inflight.isClosed()) {
        throw new CancellationException(
            "provider closed before " + request.runtime() + " could start");
      }
      return runProcess(session, request, handler, effectiveTimeout, cancellation);
    } catch (IOException e) {
      return ExecutionResult.refusal(
          "I/O error launching " + request.runtime() + ": " + e.getMessage());
    } finally {
      concurrency.release();
    }
  }

  private ExecutionResult runProcess(
      SessionContext session,
      ExecutionRequest request,
      RuntimeHandler handler,
      Duration timeout,
      CancellationToken cancellation)
      throws IOException {
    var environment = new LinkedHashMap<String, String>();
    environment.put("PATH", path);
    environment.putAll(request.environment());
    try (var process =
        BoundedProcess.newBuilder(handler.buildArgv(request))
            .withEnvironment(environment)
            .withWorkingDirectory(request.workingDirectory())
            .withTempDirectoryPrefix("helios-exec-")
            .withTimeout(timeout)
            .withMaxOutputBytes(maxOutputBytes)
            .withSecretRegistry(session.secrets())
            .start()) {
      return awaitProcess(request, process, cancellation);
    }
  }

  private ExecutionResult awaitProcess(
      ExecutionRequest request, BoundedProcess process, CancellationToken cancellation) {
    var proc = process.process();
    inflight.track(proc);
    var stdinFeeder = StdinFeeder.start(process, request.stdin().orElse(null));
    ProcessOutcome outcome;
    try {
      outcome = process.await(cancellation);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException(
          "interrupted while waiting for " + request.runtime() + " process");
    } finally {
      stdinFeeder.join();
      inflight.untrack(proc);
    }
    // If the token fired during execution and the process did NOT time out on its own, the
    // process was killed by cancellation — surface that as a CancellationException rather than a
    // misleading "normal exit 143" ExecutionResult. Timed-out runs still return a result
    // (timedOut=true) so callers can distinguish the two terminal causes.
    if (!outcome.timedOut() && cancellation.isCancelled()) {
      throw new CancellationException(
          "execution of " + request.runtime() + " cancelled: " + cancellation.reason().orElse(""));
    }
    return new ExecutionResult(
        outcome.exitCode(),
        new String(outcome.stdout(), StandardCharsets.UTF_8),
        new String(outcome.stderr(), StandardCharsets.UTF_8),
        outcome.elapsed(),
        outcome.timedOut());
  }

  /**
   * Whether this provider has been closed.
   *
   * @return {@code true} after the first successful {@link #close()}
   */
  public boolean isClosed() {
    return inflight.isClosed();
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
   * calls throw an {@link IllegalStateException}. Safe to call from any thread; subsequent calls
   * are no-ops.
   */
  @Override
  public void close() {
    inflight.close();
  }

  private record StdinFeeder(Thread thread) {

    static StdinFeeder start(BoundedProcess process, String stdin) {
      return new StdinFeeder(
          Thread.startVirtualThread(
              () -> {
                try (OutputStream out = process.stdin()) {
                  if (stdin != null) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                  }
                } catch (IOException ignored) {
                  // Child closed its stdin or exited before we finished writing — both expected.
                }
              }));
    }

    void join() {
      try {
        thread.join(BoundedProcess.GRACE);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
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
     * BoundedProcess}.
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
      var resolved = BinaryResolver.resolve(binarySpec, System.getenv("PATH"));
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

  /** Mutable builder for {@link LocalProcessExecutionProvider}. */
  public static final class Builder {

    private final Map<Runtime, RuntimeHandler> handlers = new EnumMap<>(Runtime.class);
    private String path;
    private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
    private int maxConcurrent = DEFAULT_MAX_CONCURRENT;
    private Duration maxTimeout = DEFAULT_MAX_TIMEOUT;
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
