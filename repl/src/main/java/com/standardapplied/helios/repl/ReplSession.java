/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.sandbox.ExecuteParams;
import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stateful REPL session backed by a sandbox. Each session has its own sandbox instance, host
 * function registry, and execution history.
 *
 * <p>Sessions are bound to a single agent run and must be closed when done to release sandbox
 * resources and the concurrency semaphore permit. In v2, RLM/CodeAct harness flows are gone —
 * {@code predict} and {@code submit} live as session-level Tools on {@code AgentSession}, not as
 * sandbox host functions. {@code ReplSession} is the substrate the future CodeAct preset assembles
 * atop along with {@link ai.singlr.repl.CodeExecutionTool}.
 */
public final class ReplSession implements AutoCloseable {

  private final ReplConfig config;
  private final Sandbox sandbox;
  private final HostFunctionRegistry registry;
  private final Map<String, AtomicInteger> hostFnCounts;
  private final List<ExecutionResult> history = new ArrayList<>();
  private final Semaphore semaphore;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private ReplSession(
      ReplConfig config,
      Sandbox sandbox,
      HostFunctionRegistry registry,
      Semaphore semaphore,
      Map<String, AtomicInteger> hostFnCounts) {
    this.config = config;
    this.sandbox = sandbox;
    this.registry = registry;
    this.semaphore = semaphore;
    this.hostFnCounts = hostFnCounts;
  }

  /**
   * Create a new session from the given config, acquiring a semaphore permit.
   *
   * @param config the REPL configuration
   * @param semaphore the concurrency limiter
   * @return a new session ready for code execution
   * @throws ReplException if no permits are available or sandbox creation fails
   */
  public static ReplSession create(ReplConfig config, Semaphore semaphore) {
    if (config == null) {
      throw new IllegalArgumentException("Config must not be null");
    }
    if (semaphore == null) {
      throw new IllegalArgumentException("Semaphore must not be null");
    }
    if (!semaphore.tryAcquire()) {
      throw new ReplException(
          "Max concurrent sessions reached (" + (semaphore.availablePermits()) + " available)");
    }
    try {
      var registry = new HostFunctionRegistry();
      var hostFnCounts = new ConcurrentHashMap<String, AtomicInteger>();
      for (var fn : config.hostFunctions()) {
        registry.register(wrapForCounting(fn, hostFnCounts));
      }
      var sandbox = config.sandboxFactory().create(registry);
      return new ReplSession(config, sandbox, registry, semaphore, hostFnCounts);
    } catch (Exception e) {
      semaphore.release();
      if (e instanceof ReplException re) {
        throw re;
      }
      throw new ReplException("Failed to create session", e);
    }
  }

  /**
   * Names excluded from {@link #calledHostFunctions()}. Framework-reserved primitives that the
   * substrate registers itself ({@code getInput}, {@code __getInput}, {@code __call}); the map
   * contains exactly the user-registered host functions.
   */
  private static final Set<String> CALLED_HOST_FN_EXCLUDES = HostFunctionRegistry.RESERVED_NAMES;

  /**
   * Wrap every user-registered host function with a per-call counter that flows into {@link
   * #calledHostFunctions()}. Reserved names ({@link #CALLED_HOST_FN_EXCLUDES}) are not counted —
   * they are framework-provided plumbing, not interesting trajectory data.
   *
   * <p>Cost per call: a single {@link AtomicInteger#incrementAndGet} on a per-name counter.
   */
  private static HostFunction wrapForCounting(
      HostFunction fn, Map<String, AtomicInteger> hostFnCounts) {
    var inner = fn.handler();
    var name = fn.name();
    if (CALLED_HOST_FN_EXCLUDES.contains(name)) {
      return fn;
    }
    return new HostFunction(
        name,
        fn.description(),
        fn.parameters(),
        params -> {
          hostFnCounts.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
          return inner.handle(params);
        });
  }

  /**
   * Execute code in the sandbox.
   *
   * @param code the source code to execute
   * @return the execution result
   * @throws ReplException if the session is closed or the sandbox is dead
   */
  public ExecutionResult execute(String code) {
    if (closed.get()) {
      throw new ReplException("Session is closed");
    }
    if (!sandbox.isAlive()) {
      throw new ReplException("Sandbox is no longer alive");
    }
    var request =
        ExecutionRequest.newBuilder().withCode(code).withTimeout(config.executionTimeout()).build();
    var executeParams =
        new ExecuteParams(
            config.sandboxBindingsListener() != null,
            config.maxBindingValueChars(),
            config.maxBindingSnapshotChars());
    var startNanos = System.nanoTime();
    var rawResult = sandbox.execute(request, executeParams);
    var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    var result =
        applyExecutedCodeCap(rawResult, config.maxExecutedCodeChars()).withDuration(elapsed);
    history.add(result);
    var listener = config.sandboxBindingsListener();
    if (listener != null) {
      try {
        listener.onBindings(result.bindings(), result);
      } catch (Throwable t) {
        // Listener contract: must not throw. Swallow so a misbehaving listener can't tank the run.
      }
    }
    return result;
  }

  /**
   * Apply the configured per-call cap to the {@code executedCode} field. Truncated values get a
   * {@code ... (len=N)} marker so consumers know the original length. Cap of {@code 0} returns the
   * result unchanged.
   */
  private static ExecutionResult applyExecutedCodeCap(ExecutionResult raw, int cap) {
    var code = raw.executedCode();
    if (cap <= 0 || code == null || code.length() <= cap) {
      return raw;
    }
    var truncated = code.substring(0, cap) + "... (len=" + code.length() + ")";
    return new ExecutionResult(
        truncated, raw.stdout(), raw.stderr(), raw.exitCode(), raw.submitted(), raw.bindings());
  }

  /**
   * Per-host-function call counts for the trajectory, keyed by function name. Excludes the
   * framework-reserved names ({@code getInput}, {@code __getInput}, {@code __call}); contains
   * exactly the user-registered host functions that the model invoked.
   *
   * @return immutable map of {@code name -> callCount}; absent keys mean zero calls
   */
  public Map<String, Integer> calledHostFunctions() {
    var snapshot = new LinkedHashMap<String, Integer>();
    for (var entry : hostFnCounts.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue().get());
    }
    return Map.copyOf(snapshot);
  }

  /**
   * The execution history for this session.
   *
   * @return unmodifiable list of execution results
   */
  public List<ExecutionResult> history() {
    return Collections.unmodifiableList(history);
  }

  /** The host function registry for this session. */
  public HostFunctionRegistry registry() {
    return registry;
  }

  /** The config this session was created from. */
  public ReplConfig config() {
    return config;
  }

  /** Whether this session is still open. */
  public boolean isOpen() {
    return !closed.get() && sandbox.isAlive();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        sandbox.close();
      } finally {
        semaphore.release();
      }
    }
  }
}
