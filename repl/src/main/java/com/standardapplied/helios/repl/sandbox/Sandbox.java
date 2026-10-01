/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

/**
 * Isolated execution environment for running code. Implementations may use JVM subprocesses,
 * containers, or other isolation mechanisms.
 */
public interface Sandbox extends AutoCloseable {

  /**
   * Execute code in the sandbox using {@link ExecuteParams#DEFAULT default} per-call params.
   *
   * @param request the execution request
   * @return the execution result
   */
  ExecutionResult execute(ExecutionRequest request);

  /**
   * Execute code with per-call param overrides. Default implementation ignores {@code params} and
   * delegates to {@link #execute(ExecutionRequest)}; sandboxes that support binding snapshots or
   * other per-call telemetry override this.
   *
   * @param request the execution request
   * @param params per-call overrides (binding-snapshot caps, etc.)
   * @return the execution result
   */
  default ExecutionResult execute(ExecutionRequest request, ExecuteParams params) {
    return execute(request);
  }

  /**
   * Whether the sandbox process is still alive and ready to accept requests.
   *
   * @return {@code true} if the sandbox can execute code
   */
  boolean isAlive();

  /** Destroy the sandbox and release all resources. */
  @Override
  void close();
}
