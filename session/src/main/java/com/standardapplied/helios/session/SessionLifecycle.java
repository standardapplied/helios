/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.StopClassifier;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Start-up and shutdown of one session's runtime. Acquires the session's event stream, notifies the
 * {@link ExecutionProvider} that the session starts once everything else is built ({@link
 * SessionStart}, bounded by {@link SessionLimits#startTimeout()}), starts the agent-loop virtual
 * thread on first use and, beside it, the wall-clock virtual thread; its {@link SessionTeardown}
 * releases all of it in reverse order, by one {@link SessionLimits#stopGrace()} deadline, before
 * the result future settles.
 *
 * <p>The runtime ends exactly once: a start-up the provider does not accept, {@link #startIfNeeded}
 * and {@link #close} share one compare-and-set, so exactly one path tears the runtime down and
 * settles the result.
 */
final class SessionLifecycle {

  private final SessionState state;
  private final SessionLimits limits;
  private final CompletableFuture<ResultMessage> result;
  private final SessionEventPublisher events;
  private final SessionStart start;
  private final SessionTeardown teardown;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private AgentLoop loop;

  SessionLifecycle(
      SessionState state,
      SessionContext sessionContext,
      ExecutionProvider executionProvider,
      SessionLimits limits,
      CompletableFuture<ResultMessage> result) {
    this.state = state;
    this.limits = limits;
    this.result = result;
    this.events = new SessionEventPublisher(state.sessionId());
    this.teardown =
        new SessionTeardown(state, sessionContext, executionProvider, events, limits.stopGrace());
    this.start =
        new SessionStart(
            state, sessionContext, executionProvider, () -> teardown.end(loop.toolDispatch()));
  }

  /** The session's event stream, which the shutdown closes. */
  SessionEventPublisher events() {
    return events;
  }

  /**
   * Release what the lifecycle acquired, for a session whose construction failed before {@link
   * #open} or whose provider did not accept it there; such a session can never start.
   */
  void abandon() {
    started.set(true);
    events.close();
  }

  /**
   * Bind the loop and notify the execution provider that the session starts, the last step of the
   * session's construction, waiting for it within {@code startTimeout}. A start the provider does
   * not accept ({@link SessionStart}) ends the session with its {@link
   * ResultMessage.ErrorProviderUnavailable}, or with the {@link Error} {@code onSessionStart}
   * threw, once what the lifecycle acquired is released. {@code onSessionEnd} is called only if the
   * provider accepts after the session stopped waiting, by the {@link SessionTeardown} that ends
   * that late acceptance.
   */
  void open(AgentLoop loop) {
    this.loop = loop;
    start.await(limits.startTimeout()).ifPresent(this::refuse);
  }

  private void refuse(ResultMessage refusal) {
    state.setTerminal(refusal);
    abandon();
    settle(refusal, state.ending().seal());
  }

  /**
   * Start the agent loop and the wall clock, each on a virtual thread of its own, at most once. The
   * wall clock cancels the session's {@link
   * com.standardapplied.helios.core.runtime.CancellationToken} when {@code limits.maxWallClock()}
   * elapses. Without it, {@code maxWallClock} is only checked at turn boundaries by {@link
   * StopClassifier}, so a turn blocked inside a model stream (silent socket, hung edge / proxy /
   * load balancer), a tool, a permission prompt or a back-off blocks the loop indefinitely. The
   * cancellation cuts the turn; the loop proceeds to its next iteration, where {@link
   * StopClassifier} sees {@code state.elapsed() > maxWallClock} and produces {@link
   * ResultMessage.ErrorMaxWallClock}.
   */
  void startIfNeeded() {
    if (started.compareAndSet(false, true)) {
      BoundedTask.start(
          "helios-wall-clock-" + state.sessionId(), this::awaitWallClock, state.ending());
      Thread.ofVirtual().name("helios-agent-loop-" + state.sessionId()).start(this::runLoop);
    }
  }

  /**
   * Wait until the session is cancelled or {@code maxWallClock} has passed, whichever is first, and
   * expire the session at the latter. Every end of a session cancels it, so the wait always ends.
   * It waits on the session's token, never on the result future, which a caller may complete.
   */
  private Void awaitWallClock() throws InterruptedException {
    var ended = new CountDownLatch(1);
    var registration = state.cancellation().onCancel(ended::countDown);
    try {
      if (!ended.await(limits.maxWallClock().toNanos(), TimeUnit.NANOSECONDS)) {
        expireWallClock();
      }
    } finally {
      registration.remove();
    }
    return null;
  }

  /**
   * Whether the loop was started, or can no longer start because the session closed or its provider
   * did not accept it.
   */
  boolean isStarted() {
    return started.get();
  }

  /**
   * Close the session: cancel it ({@link
   * com.standardapplied.helios.session.loop.SessionEnding#cancel}), its token's callbacks on a
   * thread of their own, so a callback that throws never reaches the caller. For a session whose
   * loop never started, also write a {@link ResultMessage.Cancelled} terminal and run the teardown
   * on the caller's thread, by one {@code stopGrace} deadline, then settle the result: as after a
   * run, it settles only once the runtime has ended, so a host that frees capacity on the result
   * never frees it while the provider still holds the session. Once the loop started, or the
   * session ended at start-up, only the session is cancelled: the running loop observes it and ends
   * the runtime itself. Never throws.
   */
  void close() {
    state.ending().cancel("session closed");
    if (started.compareAndSet(false, true)) {
      var cancelled =
          new ResultMessage.Cancelled(
              state.sessionId(),
              "session closed",
              state.totals().usage(),
              state.totals().cost(),
              state.elapsed());
      state.setTerminal(cancelled);
      end(cancelled);
    }
  }

  /** What the wall clock does at its deadline, package-private so a test can run it at once. */
  void expireWallClock() {
    state.ending().cancel("maxWallClock exceeded after " + limits.maxWallClock().toMillis() + "ms");
  }

  /**
   * Drive the loop to terminal, tear the runtime down, then settle the result future. Every event
   * is in each open stream's buffer, or missed by it, before the future resolves; the teardown does
   * not wait for readers, which read on their own threads.
   *
   * <p>{@link AgentLoop#run} turns every {@link Exception} into a terminal, so only an {@link
   * Error} escapes it, which goes to the session's {@link
   * com.standardapplied.helios.session.loop.SessionEnding}. When the result settles with an {@code
   * Error}, this thread then ends by throwing it to its uncaught-exception handler. A {@link
   * RuntimeException} that escapes anyway, because turning it into a terminal failed, settles the
   * result exceptionally as itself, so the result never stays pending.
   */
  private void runLoop() {
    ResultMessage terminal = null;
    RuntimeException crash = null;
    try {
      terminal = loop.run(state, limits);
    } catch (RuntimeException e) {
      crash = e;
    } catch (Error e) {
      state.ending().fail(e);
    }
    // An interrupt host code left on this thread is no request to cut the teardown's waits short.
    Thread.interrupted();
    var settling = teardown.endAndSeal(loop.toolDispatch());
    if (settling.isEmpty() && crash != null) {
      result.completeExceptionally(crash);
      return;
    }
    settle(terminal, settling);
    if (settling.isPresent()) {
      throw settling.get();
    }
  }

  private void end(ResultMessage terminal) {
    settle(terminal, teardown.endAndSeal(loop.toolDispatch()));
  }

  private void settle(ResultMessage terminal, Optional<Error> failure) {
    failure.ifPresentOrElse(result::completeExceptionally, () -> result.complete(terminal));
  }
}
