/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.repl.ReplException;
import com.standardapplied.helios.repl.ReplSession;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One execute on a session's {@link ReplSession}, run on the caller's thread, which the session
 * blocks for the duration of the snippet. A cancellation that fires during the call, or an
 * interrupt of the caller, closes the session, killing its sandbox, and the call throws a {@link
 * CancellationException}, the interrupt status kept; a sandbox failure returns a refusal-shaped
 * result the model can read.
 */
final class SessionExecution {

  private SessionExecution() {}

  /** Run {@code request} on {@code session} and return what the sandbox reported. */
  static ExecutionResult run(
      ReplSession session,
      ExecutionRequest request,
      Duration timeout,
      CancellationToken cancellation) {
    var killed = new AtomicBoolean();
    Runnable kill =
        () -> {
          if (killed.compareAndSet(false, true)) {
            SandboxPool.safeClose(session);
          }
        };
    var killRegistration = cancellation.onCancel(kill);
    try {
      var result = attempt(session, request, timeout);
      if (Thread.currentThread().isInterrupted()) {
        kill.run();
        throw new CancellationException("JShell snippet interrupted");
      }
      if (cancellation.isCancelled()) {
        throw new CancellationException(
            "JShell snippet cancelled: " + cancellation.reason().orElse(""));
      }
      return result;
    } finally {
      // Mark the kill callback inert regardless of outcome — the session is in a known post-call
      // state and a later token fire must not double-close. Also detach the callback from the
      // long-lived session token's list so per-call references do not accumulate.
      killed.set(true);
      killRegistration.remove();
    }
  }

  /** The refusal-shaped result for {@code request}, naming the provider and the runtime. */
  static ExecutionResult refusal(ExecutionRequest request, String reason) {
    return ExecutionResult.refusal(
        "JShellExecutionProvider: " + reason + " (runtime=" + request.runtime() + ")");
  }

  private static ExecutionResult attempt(
      ReplSession session, ExecutionRequest request, Duration timeout) {
    var startNanos = System.nanoTime();
    try {
      var raw = session.execute(request.script(), timeout);
      var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      return new ExecutionResult(
          raw.exitCode(), raw.stdout(), raw.stderr(), elapsed, raw.timedOut());
    } catch (ReplException e) {
      return refusal(
          request,
          "JShell execution failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
  }
}
