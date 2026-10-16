/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolResult;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One tool call from admission until its work ends. The worker virtual thread is admitted by the
 * category's {@link CategoryPermits}, runs the tool only if the call is still wanted and its
 * deadline has not passed, and the call gives the permit back only once its work has ended, so the
 * category's capacity stays charged for as long as the work is alive.
 *
 * <p>The call has its own {@link CancellationToken}, linked to the session's: the session's
 * cancellation reaches the call, the call's cancellation never reaches the session. {@link #cancel}
 * interrupts the worker and cancels that token on a thread of its own, so a cancellation callback
 * that blocks or throws holds up neither the canceller nor the interrupt. A call cancelled, or past
 * its deadline, before its tool started never starts it, nor does one whose session token is
 * cancelled before the session's callbacks reach it. A caller waiting for the call also reads the
 * session's token itself, so an earlier session callback that blocks cannot keep the call from
 * being cancelled. The call's work is its worker and, once started, that token's cancellation;
 * {@code liveCalls} counts the call until both have ended, when the permit is given back and the
 * link removed.
 *
 * <p>The call's answer settles once, with the tool's result, an {@link Error} the tool threw, a
 * {@link CancellationException}, or a {@link TimeoutException} at the deadline, whichever comes
 * first; a later completion changes nothing, and a later {@link Error} is logged. A caller that
 * stops waiting while the work still runs quarantines the dispatcher's every category until it
 * ends.
 */
final class DispatchedCall {

  private enum Phase {
    WAITING,
    RUNNING,
    ABANDONED
  }

  private static final Logger LOGGER = Logger.getLogger(DispatchedCall.class.getName());

  /**
   * How often a caller's wait also reads the session's token itself. The token runs its callbacks
   * one after another on the cancelling thread, so an earlier callback that blocks would otherwise
   * keep this call's link from ever running.
   */
  private static final long SESSION_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  private final CancellationToken token = new CancellationToken();
  private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.WAITING);
  private final CompletableFuture<ToolResult> answer = new CompletableFuture<>();
  private final CountDownLatch answered = new CountDownLatch(1);
  private final AtomicReference<CancellationException> cancellation = new AtomicReference<>();
  private final AtomicBoolean tokenClaimed = new AtomicBoolean();
  private final CompletableFuture<Void> tokenSettled = new CompletableFuture<>();
  private final CompletableFuture<Void> workerEnded = new CompletableFuture<>();
  private final CountDownLatch ended = new CountDownLatch(1);
  private final Tool tool;
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
      Tool tool,
      ToolCall call,
      SessionContext session,
      CancellationToken parent,
      Duration timeout,
      CategoryPermits permits,
      AtomicInteger liveCalls) {
    this.tool = tool;
    this.call = call;
    this.context = new ToolContext(session, token, timeout);
    this.parent = parent;
    this.timeout = timeout;
    this.deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(timeout);
    this.permits = permits;
    this.liveCalls = liveCalls;
    this.worker = Thread.ofVirtual().name("helios-tool-" + call.name()).unstarted(this::work);
    answer.whenComplete((result, failure) -> answered.countDown());
    CompletableFuture.allOf(workerEnded, tokenSettled)
        .thenRun(
            () -> {
              permits.release(this);
              parentLink.remove();
              liveCalls.decrementAndGet();
              ended.countDown();
            });
  }

  /**
   * Arm the deadline on {@code scheduler}, link the call to its parent token and start its worker.
   * When the deadline comes due, on {@code scheduler} or in {@link #await}, whichever is first, a
   * tool not yet started never starts, and the answer settles with a {@link TimeoutException}.
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
   * Wait for the call's answer. At the deadline, cancel the call and give its work up to {@code
   * stopGrace} to end: a tool that never started, or whose work ended in time, is {@link
   * ToolResult#timedOut timed out}; one whose work still runs is {@link ToolResult#unknown unknown}
   * and quarantines every category until it ends.
   *
   * @return the tool's result, or the timed-out or unknown result
   * @throws Error the error the tool threw, as itself
   * @throws CancellationException if the session's token is cancelled, or the calling thread is
   *     interrupted (its interrupt status is kept), before this method returns
   */
  ToolResult await(Duration stopGrace) {
    try {
      if (!settlesBy(answered, deadline)) {
        expire();
      }
      var result = answer.get();
      throwIfSessionCancelled();
      return result;
    } catch (ExecutionException e) {
      return expired(e.getCause(), stopGrace);
    } catch (CancellationException e) {
      permits.quarantine(this);
      throw cancellation.get();
    } catch (InterruptedException e) {
      throw interrupted();
    }
  }

  /**
   * Settle the call's answer with a {@link CancellationException} unless it has settled already,
   * cancel the call's token on a thread of its own unless the worker has ended, and interrupt the
   * worker. The answer settles first, so a tool that returns because it was woken cannot answer in
   * its place.
   *
   * @return {@code true} if the tool had not started and now never will
   */
  boolean cancel(String reason) {
    var neverStarted = phase.compareAndExchange(Phase.WAITING, Phase.ABANDONED) != Phase.RUNNING;
    cancellation.compareAndSet(null, new CancellationException(reason));
    answer.completeExceptionally(cancellation.get());
    try {
      if (tokenClaimed.compareAndSet(false, true)) {
        Thread.ofVirtual()
            .name("helios-tool-cancel-" + call.name())
            .start(() -> cancelToken(reason));
      }
    } finally {
      worker.interrupt();
    }
    return neverStarted;
  }

  /**
   * Wait until {@code latch} opens or {@code until} passes, reading the session's token as it
   * waits.
   *
   * @return {@code true} if {@code latch} opened
   * @throws CancellationException if the session's token is cancelled first
   */
  private boolean settlesBy(CountDownLatch latch, long until) throws InterruptedException {
    while (!latch.await(
        Math.clamp(until - System.nanoTime(), 0L, SESSION_CHECK_NANOS), TimeUnit.NANOSECONDS)) {
      if (System.nanoTime() - until >= 0) {
        return false;
      }
      throwIfSessionCancelled();
    }
    return true;
  }

  private void expire() {
    phase.compareAndSet(Phase.WAITING, Phase.ABANDONED);
    answer.completeExceptionally(new TimeoutException());
  }

  private ToolResult expired(Throwable cause, Duration stopGrace) {
    if (!(cause instanceof TimeoutException)) {
      throw (Error) cause;
    }
    try {
      if (!cancel("tool '" + call.name() + "' timed out after " + timeout)) {
        settlesBy(ended, System.nanoTime() + TimeUnit.NANOSECONDS.convert(stopGrace));
      }
    } catch (InterruptedException e) {
      throw interrupted();
    }
    var stillRunning = permits.quarantine(this);
    throwIfSessionCancelled();
    return stillRunning
        ? ToolResult.unknown(call.name(), timeout)
        : ToolResult.timedOut(call.name(), timeout);
  }

  private void throwIfSessionCancelled() {
    if (parent.isCancelled()) {
      var reason = parent.reason().orElseThrow();
      cancel(reason);
      permits.quarantine(this);
      throw new CancellationException(reason);
    }
  }

  private CancellationException interrupted() {
    Thread.currentThread().interrupt();
    var reason = "interrupted waiting for tool " + call.name();
    cancel(reason);
    permits.quarantine(this);
    return new CancellationException(reason);
  }

  private void work() {
    try {
      var admitted =
          permits.admit(
              this,
              () ->
                  !parent.isCancelled()
                      && System.nanoTime() - deadline < 0
                      && phase.compareAndSet(Phase.WAITING, Phase.RUNNING));
      if (admitted) {
        answer.complete(tool.execute(call.arguments(), context));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Error e) {
      if (!answer.completeExceptionally(e)) {
        LOGGER.log(Level.SEVERE, "tool '" + call.name() + "' threw after its call was answered", e);
      }
    } finally {
      if (tokenClaimed.compareAndSet(false, true)) {
        tokenSettled.complete(null);
      }
      workerEnded.complete(null);
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
