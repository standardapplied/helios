/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.tools.ToolBinding;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One tool call, from its start until its work ends. The call's answer settles once: with the
 * tool's result, whatever the tool threw past {@link
 * com.standardapplied.helios.core.tool.Tool#execute Tool.execute}, a {@link CancellationException},
 * or a {@link TimeoutException} at the deadline. Its worker virtual thread starts the tool only
 * while the answer is unsettled, the session's token is not cancelled and the deadline has not
 * passed. The call's own token is linked to the session's and is cancelled on a thread of its own,
 * so a callback that blocks or throws holds up neither the canceller nor the worker's interrupt.
 * The work is the worker and that cancellation; when both have ended, the worker removes the link
 * and the call is no longer live. A call its caller stops waiting for while its work runs is handed
 * to the dispatcher as abandoned. Every {@link Error} of the work, one a callback on the call's
 * token throws or one the tool throws, is recorded by the session's {@link SessionEnding}, the
 * tool's once the answer settled with it, so the caller reads it as itself.
 */
final class DispatchedCall {

  private static final Logger LOGGER = Logger.getLogger(DispatchedCall.class.getName());

  private final CancellationToken token = new CancellationToken();
  private final CompletableFuture<ToolResult> answer = new CompletableFuture<>();
  private final AtomicBoolean tokenClaimed = new AtomicBoolean();
  private final CompletableFuture<Void> tokenSettled = new CompletableFuture<>();
  private final CompletableFuture<Void> ended = new CompletableFuture<>();
  private final CompletableFuture<Void> sessionCancelled = new CompletableFuture<>();
  private final ToolBinding binding;
  private final ToolCall call;
  private final ToolContext context;
  private final CancellationToken parent;
  private final Duration timeout;
  private final long deadline;
  private final Set<DispatchedCall> liveCalls;
  private final Consumer<DispatchedCall> abandoned;
  private final SessionEnding ending;
  private final Thread worker;
  private volatile CancellationToken.Registration parentLink = CancellationToken.Registration.NOOP;

  DispatchedCall(
      ToolBinding binding,
      ToolCall call,
      SessionContext session,
      CancellationToken parent,
      Duration timeout,
      Predicate<String> withheld,
      Set<DispatchedCall> liveCalls,
      Consumer<DispatchedCall> abandoned,
      SessionEnding ending) {
    this.binding = binding;
    this.call = call;
    this.context = new ToolContext(session, token, timeout, withheld);
    this.parent = parent;
    this.timeout = timeout;
    this.deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(timeout);
    this.liveCalls = liveCalls;
    this.abandoned = abandoned;
    this.ending = ending;
    this.worker = Thread.ofVirtual().name("helios-tool-" + call.name()).unstarted(this::work);
  }

  /** Link the call to its parent token, add it to the live calls and start its worker. */
  void start() {
    parentLink =
        parent.onCancel(
            () -> {
              cancel(parent.reason().orElseThrow());
              sessionCancelled.complete(null);
            });
    liveCalls.add(this);
    worker.start();
  }

  ToolCall call() {
    return call;
  }

  /** Whether the call's work, its worker and its token's cancellation, has not ended. */
  boolean isRunning() {
    return !ended.isDone();
  }

  /**
   * Wait until the call's work has ended or {@code until} passes; an interrupt of the waiting
   * thread ends the wait, its interrupt status kept.
   *
   * @return {@code true} if the work ended
   */
  boolean endsBy(long until) {
    try {
      ended.get(Math.max(0L, until - System.nanoTime()), TimeUnit.NANOSECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException | TimeoutException e) {
      return false;
    }
  }

  /**
   * Wait for the call's answer until the deadline. At the deadline, cancel the call and give its
   * work up to {@code stopGrace} to end: a call whose work ended is {@link ToolResult#timedOut
   * timed out}; one whose tool still runs is {@link ToolResult#unknown unknown}, and abandoned.
   * Both waits end as soon as the session's token is cancelled, and a call the session's
   * cancellation or the caller's interrupt leaves running is abandoned.
   *
   * @return the tool's result, a {@link ToolResult#threw threw} result for a non-{@link Error}
   *     throwable that escaped {@link com.standardapplied.helios.core.tool.Tool#execute
   *     Tool.execute}, or the timed-out or unknown result
   * @throws Error the error the tool threw, as itself, the session's cancellation it caused
   *     included
   * @throws CancellationException if the session's token is cancelled, or the calling thread is
   *     interrupted (its interrupt status is kept), before this method returns
   */
  ToolResult await(Duration stopGrace) {
    try {
      if (!settlesBy(answer, deadline)) {
        expire();
      }
      return answer.get();
    } catch (ExecutionException e) {
      return expired(e.getCause(), stopGrace);
    } catch (InterruptedException e) {
      throw interrupted();
    } catch (CancellationException e) {
      if (answer.state() == Future.State.FAILED && answer.exceptionNow() instanceof Error error) {
        throw error;
      }
      throw e;
    }
  }

  /**
   * Wait until {@code future} settles or {@code until} passes, reading the session's token as it
   * waits and once more before it returns. A call the session's cancellation leaves running is
   * abandoned.
   *
   * @return {@code true} if {@code future} settled
   * @throws CancellationException if the session's token is cancelled before this method returns
   */
  private boolean settlesBy(CompletableFuture<?> future, long until) throws InterruptedException {
    var settled = TokenWait.settlesBy(future, parent, until);
    if (parent.isCancelled()) {
      var reason = parent.reason().orElseThrow();
      cancel(reason);
      abandoned.accept(this);
      throw new CancellationException(reason);
    }
    return settled;
  }

  /** Settle the answer with a {@link TimeoutException}, as the deadline does, unless it settled. */
  void expire() {
    answer.completeExceptionally(new TimeoutException());
  }

  private ToolResult expired(Throwable cause, Duration stopGrace) {
    if (cause instanceof Error error) {
      throw error;
    }
    if (!(cause instanceof TimeoutException)) {
      return ToolResult.threw(call.name(), cause);
    }
    cancel("tool '" + call.name() + "' timed out after " + timeout);
    try {
      settlesBy(
          CompletableFuture.anyOf(ended, sessionCancelled),
          System.nanoTime() + TimeUnit.NANOSECONDS.convert(stopGrace));
    } catch (InterruptedException e) {
      throw interrupted();
    }
    if (!isRunning()) {
      return ToolResult.timedOut(call.name(), timeout);
    }
    abandoned.accept(this);
    return ToolResult.unknown(call.name(), timeout);
  }

  /**
   * Settle the answer with a {@link CancellationException} unless it has settled, claim the call's
   * token for cancellation unless the worker has ended, and interrupt the worker. The answer
   * settles first, so a tool that returns because it was woken cannot answer in its place, and the
   * token is claimed before the interrupt, so a worker that ends because it was woken cannot claim
   * it first.
   */
  private void cancel(String reason) {
    answer.completeExceptionally(new CancellationException(reason));
    if (tokenClaimed.compareAndSet(false, true)) {
      token.cancelAsync(reason, ending::fail).thenRun(() -> tokenSettled.complete(null));
    }
    worker.interrupt();
  }

  private CancellationException interrupted() {
    Thread.currentThread().interrupt();
    var reason = "interrupted waiting for tool " + call.name();
    cancel(reason);
    abandoned.accept(this);
    return new CancellationException(reason);
  }

  private void work() {
    try {
      if (!answer.isDone() && !parent.isCancelled() && System.nanoTime() - deadline < 0) {
        answer.complete(binding.tool().execute(call.arguments(), context));
      }
    } catch (Error e) {
      answer.completeExceptionally(e);
      ending.fail(e);
    } catch (Throwable e) {
      if (!answer.completeExceptionally(e)) {
        LOGGER.log(
            Level.SEVERE,
            "tool '" + call.name() + "' threw after its call was answered",
            SerializedError.redactedForLog(e, context.sessionContext().secrets().redactor()));
      }
    } finally {
      if (!tokenClaimed.compareAndSet(false, true)) {
        tokenSettled.join();
      }
      parentLink.remove();
      ended.complete(null);
      liveCalls.remove(this);
    }
  }
}
