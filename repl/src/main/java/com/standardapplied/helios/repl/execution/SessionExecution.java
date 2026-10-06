/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.repl.ReplException;
import com.standardapplied.helios.repl.ReplSession;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One execute on a session's {@link ReplSession}, run on a virtual thread of its own because the
 * session blocks for the duration of the snippet. A cancellation that fires during the call closes
 * the session, killing its sandbox, and the call completes with a {@link CancellationException}; a
 * sandbox failure completes it with a refusal-shaped result the model can read.
 */
final class SessionExecution {

  private SessionExecution() {}

  /** Start running {@code request} on {@code session}. */
  static CompletableFuture<ExecutionResult> start(
      String sessionId,
      ReplSession session,
      ExecutionRequest request,
      CancellationToken cancellation,
      OutputRedaction redaction) {
    var future = new CompletableFuture<ExecutionResult>();
    Thread.ofVirtual()
        .name("helios-jshell-" + sessionId)
        .start(() -> run(session, request, cancellation, redaction, future));
    return future;
  }

  /** The refusal-shaped result for {@code request}, naming the provider and the runtime. */
  static ExecutionResult refusal(ExecutionRequest request, String reason) {
    return ExecutionResult.refusal(
        "JShellExecutionProvider: " + reason + " (runtime=" + request.runtime() + ")");
  }

  private static void run(
      ReplSession session,
      ExecutionRequest request,
      CancellationToken cancellation,
      OutputRedaction redaction,
      CompletableFuture<ExecutionResult> future) {
    var killed = new AtomicBoolean();
    var killRegistration =
        cancellation.onCancel(
            () -> {
              if (killed.compareAndSet(false, true)) {
                SandboxPool.safeClose(session);
              }
            });
    var startNanos = System.nanoTime();
    try {
      var raw = session.execute(request.script());
      var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      complete(future, cancellation, () -> redaction.redact(raw, elapsed));
    } catch (ReplException e) {
      complete(future, cancellation, () -> failure(request, e));
    } catch (Throwable t) {
      future.completeExceptionally(t);
    } finally {
      // Mark the kill callback inert regardless of outcome — the session is in a known post-call
      // state and a later token fire must not double-close. Also detach the callback from the
      // long-lived session token's list so per-call references do not accumulate.
      killed.set(true);
      killRegistration.remove();
    }
  }

  /** Complete with {@code result}, unless the call was cancelled while it ran. */
  private static void complete(
      CompletableFuture<ExecutionResult> future,
      CancellationToken cancellation,
      Supplier<ExecutionResult> result) {
    if (cancellation.isCancelled()) {
      future.completeExceptionally(
          new CancellationException(
              "JShell snippet cancelled: " + cancellation.reason().orElse("")));
      return;
    }
    future.complete(result.get());
  }

  private static ExecutionResult failure(ExecutionRequest request, ReplException e) {
    return refusal(
        request,
        "JShell execution failed: "
            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
  }
}
