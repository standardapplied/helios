/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.StopClassifier;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Start-up and shutdown of one session's runtime. Acquires the session's event stream and then its
 * scheduler, notifies the {@link ExecutionProvider} that the session starts once everything else is
 * built ({@link SessionStart}, bounded by {@link SessionLimits#startTimeout()}), starts the
 * agent-loop virtual thread on first use and arms the wall-clock deadline; its {@link
 * SessionTeardown} releases all of it in reverse order before the result future settles.
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
  private final ScheduledExecutorService deadlineScheduler;
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
    var scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              var t = new Thread(r, "helios-deadline-" + state.sessionId());
              t.setDaemon(true);
              return t;
            });
    scheduler.setRemoveOnCancelPolicy(true);
    this.deadlineScheduler = scheduler;
    this.teardown =
        new SessionTeardown(
            state,
            sessionContext,
            executionProvider,
            events,
            deadlineScheduler,
            limits.teardownGrace());
    this.start =
        new SessionStart(
            state, sessionContext, executionProvider, () -> teardown.end(loop.toolDispatch()));
  }

  /** The session's event stream, which the shutdown closes. */
  SessionEventPublisher events() {
    return events;
  }

  /**
   * The session-scoped scheduler: the wall-clock deadline, the turn subscribers' stream-idle timers
   * and the tool calls' deadlines run on it, and it is shut down with the session. A cancelled
   * timer leaves its queue at once, so re-arming and finished calls accumulate nothing.
   */
  ScheduledExecutorService scheduler() {
    return deadlineScheduler;
  }

  /**
   * Release what the lifecycle acquired, for a session whose construction failed before {@link
   * #open} or whose provider did not accept it there; such a session can never start.
   */
  void abandon() {
    started.set(true);
    teardown.release();
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

  /** Start the agent loop on its virtual thread and arm the wall-clock deadline, at most once. */
  void startIfNeeded() {
    if (started.compareAndSet(false, true)) {
      scheduleWallClockDeadline();
      Thread.ofVirtual().name("helios-agent-loop-" + state.sessionId()).start(this::runLoop);
    }
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
   * thread of their own, so a callback that blocks or throws holds up neither the caller nor the
   * result, and end the runtime of a session whose loop never started, writing a {@link
   * ResultMessage.Cancelled} terminal. The result future settles only once the runtime has ended,
   * as after a run, so a host that frees capacity on the result never frees it while the provider
   * still holds the session. Once the loop started, or the session ended at start-up, only the
   * session is cancelled: the running loop observes it and ends the runtime itself. Never throws.
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

  /**
   * Arm a one-shot task that cancels the session's {@link
   * com.standardapplied.helios.core.runtime.CancellationToken} when {@code limits.maxWallClock()}
   * elapses. Without this, {@code maxWallClock} is only checked at turn boundaries by {@link
   * StopClassifier}, so a turn whose model stream never delivers {@code onComplete} / {@code
   * onError} (silent socket, hung edge / proxy / load balancer) blocks the loop indefinitely. The
   * wall-clock cancellation flips the token; the turn's subscriber observes it and unblocks; the
   * loop proceeds to its next iteration, where {@link StopClassifier} sees {@code state.elapsed() >
   * maxWallClock} and produces {@link ResultMessage.ErrorMaxWallClock}. The session is cancelled
   * through {@link com.standardapplied.helios.session.loop.SessionEnding#cancel}, so its token's
   * callbacks never run on the scheduler's one thread. The scheduler's shutdown drops it.
   */
  private void scheduleWallClockDeadline() {
    var millis = limits.maxWallClock().toMillis();
    deadlineScheduler.schedule(
        () -> state.ending().cancel("maxWallClock exceeded after " + millis + "ms"),
        millis,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Drive the loop to terminal, tear the runtime down, then settle the result future. The teardown
   * drains the publisher before the future resolves, the happens-before guarantee that lets
   * deployers read aggregates set by a {@code LoopEnded} subscriber immediately after {@code
   * result().get()} / {@code runBlocking(...)} unblocks.
   *
   * <p>{@link AgentLoop#run} turns every {@link Exception} into a terminal, so only an {@link
   * Error} escapes it, which goes to the session's {@link
   * com.standardapplied.helios.session.loop.SessionEnding}. When the result settles with an {@code
   * Error}, this thread then ends by throwing it to its uncaught-exception handler.
   */
  private void runLoop() {
    ResultMessage terminal = null;
    try {
      terminal = loop.run(state, limits);
    } catch (Error e) {
      state.ending().fail(e);
    }
    var settling = end(terminal);
    if (settling.isPresent()) {
      throw settling.get();
    }
  }

  private Optional<Error> end(ResultMessage terminal) {
    var settling = teardown.run(loop.toolDispatch());
    settle(terminal, settling);
    return settling;
  }

  private void settle(ResultMessage terminal, Optional<Error> failure) {
    failure.ifPresentOrElse(result::completeExceptionally, () -> result.complete(terminal));
  }
}
