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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One tool call from admission until its worker ends. The worker virtual thread takes the category
 * permit, runs the tool only if the call is still wanted, and gives the permit back only after the
 * tool has returned, so the category's capacity stays charged for as long as the work is alive.
 *
 * <p>The call has its own {@link CancellationToken}, linked to the session's: the session's
 * cancellation reaches the call, the call's cancellation never reaches the session. {@link #cancel}
 * cancels that token and interrupts the worker; a call cancelled before its tool started never
 * starts it. {@code liveCalls} counts the call from the start of its worker to its end, when the
 * link is removed.
 *
 * <p>The call's answer settles once, with the tool's result, an {@link Error} the tool threw, a
 * {@link CancellationException}, or a {@link TimeoutException} at the deadline, whichever comes
 * first; a later completion changes nothing, and a later {@link Error} is logged.
 */
final class DispatchedCall {

  private enum Phase {
    WAITING,
    RUNNING,
    ABANDONED
  }

  private static final Logger LOGGER = Logger.getLogger(DispatchedCall.class.getName());

  private final CancellationToken token = new CancellationToken();
  private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.WAITING);
  private final CompletableFuture<ToolResult> answer = new CompletableFuture<>();
  private final AtomicReference<CancellationException> cancellation = new AtomicReference<>();
  private final Tool tool;
  private final ToolCall call;
  private final ToolContext context;
  private final Duration timeout;
  private final Semaphore permits;
  private final AtomicInteger liveCalls;
  private final Thread worker;
  private volatile CancellationToken.Registration parentLink = CancellationToken.Registration.NOOP;

  DispatchedCall(
      Tool tool,
      ToolCall call,
      SessionContext session,
      Duration timeout,
      Semaphore permits,
      AtomicInteger liveCalls) {
    this.tool = tool;
    this.call = call;
    this.context = new ToolContext(session, token, timeout);
    this.timeout = timeout;
    this.permits = permits;
    this.liveCalls = liveCalls;
    this.worker = Thread.ofVirtual().name("helios-tool-" + call.name()).unstarted(this::work);
  }

  /**
   * Arm the deadline on {@code scheduler}, link the call to {@code parent} and start its worker.
   */
  void start(CancellationToken parent, ScheduledExecutorService scheduler) {
    var deadline =
        scheduler.schedule(
            this::expire, TimeUnit.NANOSECONDS.convert(timeout), TimeUnit.NANOSECONDS);
    answer.whenComplete((result, failure) -> deadline.cancel(false));
    parentLink = parent.onCancel(() -> cancel(parent.reason().orElseThrow()));
    liveCalls.incrementAndGet();
    worker.start();
  }

  /**
   * Wait for the call's answer. At the deadline, cancel the call and give its tool up to {@code
   * stopGrace} to stop: a tool that never started or stopped in time is {@link ToolResult#timedOut
   * timed out}, one still running is {@link ToolResult#unknown unknown}.
   *
   * @return the tool's result, or the timed-out or unknown result
   * @throws Error the error the tool threw, as itself
   * @throws CancellationException if the call is cancelled, or the calling thread interrupted (its
   *     interrupt status is kept), before it is answered
   */
  ToolResult await(Duration stopGrace) {
    try {
      return answer.get();
    } catch (ExecutionException e) {
      return expired(e.getCause(), stopGrace);
    } catch (CancellationException e) {
      throw cancellation.get();
    } catch (InterruptedException e) {
      throw interrupted();
    }
  }

  /**
   * Settle the call's answer with a {@link CancellationException} unless it has settled already,
   * then cancel the call's token and interrupt its worker. The answer settles first, so a tool that
   * returns because it was woken cannot answer in its place.
   *
   * @return {@code true} if the tool had not started and now never will
   */
  boolean cancel(String reason) {
    var neverStarted = phase.compareAndSet(Phase.WAITING, Phase.ABANDONED);
    cancellation.compareAndSet(null, new CancellationException(reason));
    answer.completeExceptionally(cancellation.get());
    token.cancel(reason);
    worker.interrupt();
    return neverStarted;
  }

  private ToolResult expired(Throwable cause, Duration stopGrace) {
    if (!(cause instanceof TimeoutException)) {
      throw (Error) cause;
    }
    try {
      var stopped =
          cancel("tool '" + call.name() + "' timed out after " + timeout) || worker.join(stopGrace);
      return stopped
          ? ToolResult.timedOut(call.name(), timeout)
          : ToolResult.unknown(call.name(), timeout);
    } catch (InterruptedException e) {
      throw interrupted();
    }
  }

  private CancellationException interrupted() {
    Thread.currentThread().interrupt();
    var reason = "interrupted waiting for tool " + call.name();
    cancel(reason);
    return new CancellationException(reason);
  }

  private void expire() {
    answer.completeExceptionally(new TimeoutException());
  }

  private void work() {
    try {
      permits.acquire();
      try {
        runIfWanted();
      } finally {
        permits.release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      parentLink.remove();
      liveCalls.decrementAndGet();
    }
  }

  private void runIfWanted() {
    if (!phase.compareAndSet(Phase.WAITING, Phase.RUNNING)) {
      return;
    }
    try {
      answer.complete(tool.execute(call.arguments(), context));
    } catch (Error e) {
      if (!answer.completeExceptionally(e)) {
        LOGGER.log(Level.SEVERE, "tool '" + call.name() + "' threw after its call was answered", e);
      }
    }
  }
}
