/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolBinding;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One tool call, from admission until its work ends. The call's answer settles once: with the
 * tool's result, whatever the tool threw past {@link
 * com.standardapplied.helios.core.tool.Tool#execute Tool.execute}, a {@link CancellationException},
 * or a {@link TimeoutException} at the deadline. Its worker virtual thread, once admitted by the
 * category's {@link CategoryPermits}, starts the tool only while the answer is unsettled, the
 * session's token is not cancelled and the deadline has not passed. The call's own token is linked
 * to the session's and is cancelled on a thread of its own, so a callback that blocks or throws
 * holds up neither the canceller nor the worker's interrupt. The work is the worker and that
 * cancellation; when both have ended, the worker gives the permit back and removes the link.
 */
final class DispatchedCall {

  private static final Logger LOGGER = Logger.getLogger(DispatchedCall.class.getName());

  /**
   * How often a caller's wait reads the session's token itself. The token runs its callbacks one
   * after another, so an earlier callback that blocks would otherwise keep this call's link from
   * running.
   */
  private static final long SESSION_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  private final CancellationToken token = new CancellationToken();
  private final CompletableFuture<ToolResult> answer = new CompletableFuture<>();
  private final CountDownLatch answered = new CountDownLatch(1);
  private final AtomicBoolean tokenClaimed = new AtomicBoolean();
  private final CompletableFuture<Void> tokenSettled = new CompletableFuture<>();
  private final CountDownLatch ended = new CountDownLatch(1);
  private final ToolBinding binding;
  private final ToolCall call;
  private final ToolContext context;
  private final CancellationToken parent;
  private final Duration timeout;
  private final long deadline;
  private final CategoryPermits permits;
  private final AtomicInteger liveCalls;
  private final Thread worker;
  private volatile CancellationToken.Registration parentLink = CancellationToken.Registration.NOOP;

  DispatchedCall(
      ToolBinding binding,
      ToolCall call,
      SessionContext session,
      CancellationToken parent,
      Duration timeout,
      CategoryPermits permits,
      AtomicInteger liveCalls) {
    this.binding = binding;
    this.call = call;
    this.context = new ToolContext(session, token, timeout);
    this.parent = parent;
    this.timeout = timeout;
    this.deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(timeout);
    this.permits = permits;
    this.liveCalls = liveCalls;
    this.worker = Thread.ofVirtual().name("helios-tool-" + call.name()).unstarted(this::work);
    answer.whenComplete((result, failure) -> answered.countDown());
  }

  /**
   * Arm the deadline on {@code scheduler}, link the call to its parent token and start its worker.
   */
  void start(ScheduledExecutorService scheduler) {
    var timer =
        scheduler.schedule(
            this::expire, TimeUnit.NANOSECONDS.convert(timeout), TimeUnit.NANOSECONDS);
    answer.whenComplete((result, failure) -> timer.cancel(false));
    parentLink = parent.onCancel(() -> cancel(parent.reason().orElseThrow()));
    liveCalls.incrementAndGet();
    worker.start();
  }

  /**
   * Wait for the call's answer, no later than the deadline even when the scheduler is late. At the
   * deadline, cancel the call and give its work up to {@code stopGrace} to end: a call whose work
   * ended is {@link ToolResult#timedOut timed out}; one whose tool still runs is {@link
   * ToolResult#unknown unknown}. A call with side effects that this method stops waiting for while
   * its tool runs is quarantined until its work ends.
   *
   * @return the tool's result, a {@link ToolResult#threw threw} result for a non-{@link Error}
   *     throwable that escaped {@link com.standardapplied.helios.core.tool.Tool#execute
   *     Tool.execute}, or the timed-out or unknown result
   * @throws Error the error the tool threw, as itself
   * @throws CancellationException if the session's token is cancelled, or the calling thread is
   *     interrupted (its interrupt status is kept), before this method returns
   */
  ToolResult await(Duration stopGrace) {
    try {
      if (!settlesBy(answered, deadline)) {
        expire();
      }
      return answer.get();
    } catch (ExecutionException e) {
      return expired(e.getCause(), stopGrace);
    } catch (InterruptedException e) {
      throw interrupted();
    }
  }

  /**
   * Wait until {@code latch} opens or {@code until} passes, reading the session's token as it waits
   * and once more before it returns.
   *
   * @return {@code true} if {@code latch} opened
   * @throws CancellationException if the session's token is cancelled before this method returns
   */
  private boolean settlesBy(CountDownLatch latch, long until) throws InterruptedException {
    while (!latch.await(
        Math.clamp(until - System.nanoTime(), 0L, SESSION_CHECK_NANOS), TimeUnit.NANOSECONDS)) {
      throwIfSessionCancelled();
      if (System.nanoTime() - until >= 0) {
        return false;
      }
    }
    throwIfSessionCancelled();
    return true;
  }

  private void expire() {
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
      settlesBy(ended, System.nanoTime() + TimeUnit.NANOSECONDS.convert(stopGrace));
    } catch (InterruptedException e) {
      throw interrupted();
    }
    return permits.quarantine(this, binding)
        ? ToolResult.unknown(call.name(), timeout)
        : ToolResult.timedOut(call.name(), timeout);
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
      Thread.ofVirtual().name("helios-tool-cancel-" + call.name()).start(() -> cancelToken(reason));
    }
    worker.interrupt();
  }

  private void throwIfSessionCancelled() {
    if (parent.isCancelled()) {
      var reason = parent.reason().orElseThrow();
      cancel(reason);
      permits.quarantine(this, binding);
      throw new CancellationException(reason);
    }
  }

  private CancellationException interrupted() {
    Thread.currentThread().interrupt();
    var reason = "interrupted waiting for tool " + call.name();
    cancel(reason);
    permits.quarantine(this, binding);
    return new CancellationException(reason);
  }

  private void work() {
    try {
      var admitted =
          permits.admit(
              this,
              () -> !answer.isDone() && !parent.isCancelled() && System.nanoTime() - deadline < 0,
              reason ->
                  answer.complete(ToolResult.rejected(ToolDispatch.class.getSimpleName(), reason)));
      if (admitted) {
        answer.complete(binding.tool().execute(call.arguments(), context));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Throwable e) {
      if (!answer.completeExceptionally(e)) {
        LOGGER.log(Level.SEVERE, "tool '" + call.name() + "' threw after its call was answered", e);
      }
    } finally {
      if (!tokenClaimed.compareAndSet(false, true)) {
        tokenSettled.join();
      }
      permits.release(this);
      parentLink.remove();
      liveCalls.decrementAndGet();
      ended.countDown();
    }
  }

  private void cancelToken(String reason) {
    try {
      token.cancel(reason);
    } catch (Error e) {
      LOGGER.log(Level.SEVERE, "a cancellation callback of tool '" + call.name() + "' threw", e);
    } finally {
      tokenSettled.complete(null);
    }
  }
}
