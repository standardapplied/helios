/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.StopClassifier;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Start-up and shutdown of one session's runtime. Notifies the {@link ExecutionProvider} when the
 * session starts and ends, starts the agent-loop virtual thread on first use, arms the wall-clock
 * deadline on the scheduler it owns, and on the way out closes the event stream, stops the
 * scheduler and settles the result future.
 *
 * <p>The loop is started at most once: {@link #startIfNeeded} and {@link #closeBeforeStart} share
 * one compare-and-set, so exactly one of the two paths ends the runtime.
 */
final class SessionLifecycle {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final SessionState state;
  private final SessionContext sessionContext;
  private final ExecutionProvider executionProvider;
  private final SessionEventPublisher events;
  private final CompletableFuture<ResultMessage> result;
  private final ScheduledExecutorService deadlineScheduler;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final boolean providerAccepted;
  private volatile ScheduledFuture<?> wallClockDeadline;

  /**
   * Build the lifecycle and notify the execution provider that the session starts. A provider that
   * refuses settles {@code result} with {@link ResultMessage.ErrorProviderUnavailable} at once.
   */
  SessionLifecycle(
      SessionState state,
      SessionContext sessionContext,
      ExecutionProvider executionProvider,
      SessionEventPublisher events,
      CompletableFuture<ResultMessage> result) {
    this.state = state;
    this.sessionContext = sessionContext;
    this.executionProvider = executionProvider;
    this.events = events;
    this.result = result;
    this.deadlineScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "helios-deadline-" + state.sessionId());
              t.setDaemon(true);
              return t;
            });
    this.providerAccepted = invokeOnSessionStart();
  }

  /**
   * The session-scoped scheduler: the wall-clock deadline and the turn subscribers' stream-idle
   * timers run on it, and it is shut down with the session.
   */
  ScheduledExecutorService scheduler() {
    return deadlineScheduler;
  }

  /** The event publisher's executor, which the shutdown closes. */
  ExecutorService publisherExecutor() {
    return events.executor();
  }

  /**
   * Fire {@code executionProvider.onSessionStart(sessionContext)} and react to its outcome. When
   * the provider returns {@link SessionStartOutcome.Refuse}, settle the result future immediately
   * with {@link ResultMessage.ErrorProviderUnavailable} so subsequent {@code send} / {@code
   * interrupt} calls observe a terminal session.
   *
   * @return {@code true} when the provider accepted (so {@link #closeRuntime} must fire {@code
   *     onSessionEnd}); {@code false} when the session was refused
   */
  private boolean invokeOnSessionStart() {
    SessionStartOutcome outcome;
    try {
      outcome = executionProvider.onSessionStart(sessionContext);
    } catch (RuntimeException e) {
      markRefused(
          "onSessionStart threw "
              + e.getClass().getSimpleName()
              + ": "
              + (e.getMessage() == null ? "(no message)" : e.getMessage()),
          e);
      return false;
    }
    Objects.requireNonNull(outcome, "onSessionStart returned null");
    if (outcome instanceof SessionStartOutcome.Refuse refuse) {
      markRefused(refuse.reason(), refuse.cause());
      return false;
    }
    return true;
  }

  private void markRefused(String reason, Throwable cause) {
    var serialised = cause == null ? null : SerializedError.of(cause);
    var refusal =
        new ResultMessage.ErrorProviderUnavailable(
            state.sessionId(),
            executionProvider.getClass().getSimpleName(),
            reason,
            serialised,
            state.totals().usage(),
            state.totals().cost(),
            state.elapsed());
    state.setTerminal(refusal);
    result.complete(refusal);
  }

  /** Start the agent loop on its virtual thread and arm the wall-clock deadline, at most once. */
  void startIfNeeded(AgentLoop loop, SessionLimits limits) {
    if (started.compareAndSet(false, true)) {
      scheduleWallClockDeadline(limits);
      Thread.ofVirtual()
          .name("helios-agent-loop-" + state.sessionId())
          .start(() -> runLoop(loop, limits));
    }
  }

  /**
   * End the runtime of a session whose loop never started, writing a {@link
   * ResultMessage.Cancelled} terminal unless one is already recorded (a refused session set {@link
   * ResultMessage.ErrorProviderUnavailable} at construction). No-op once the loop started: the
   * running loop observes the cancellation and ends the runtime itself.
   */
  void closeBeforeStart() {
    if (started.compareAndSet(false, true)) {
      if (!state.isTerminal()) {
        var preStartResult =
            new ResultMessage.Cancelled(
                state.sessionId(),
                "session closed",
                state.totals().usage(),
                state.totals().cost(),
                state.elapsed());
        state.setTerminal(preStartResult);
        result.complete(preStartResult);
      }
      closeRuntime();
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
   * maxWallClock} and produces {@link ResultMessage.ErrorMaxWallClock}.
   *
   * <p>The future is captured so {@link #closeRuntime()} can cancel it before shutdown.
   */
  private void scheduleWallClockDeadline(SessionLimits limits) {
    var millis = limits.maxWallClock().toMillis();
    wallClockDeadline =
        deadlineScheduler.schedule(
            () -> state.cancellation().cancel("maxWallClock exceeded after " + millis + "ms"),
            millis,
            TimeUnit.MILLISECONDS);
  }

  /**
   * Drive the loop to terminal, drain the per-session publisher so every subscriber observes the
   * final {@link QueryEvent.LoopEnded}, then settle the result future. The ordering (closeRuntime
   * BEFORE the future resolves) is the happens-before guarantee that lets deployers read aggregates
   * set by a {@code LoopEnded} subscriber immediately after {@code result().get()} / {@code
   * runBlocking(...)} unblocks. Without it, a subscriber that captures usage / cost from {@code
   * LoopEnded} races against the caller's read and silently drops data — observed as the
   * matchmaking baseline's 3/24 viewers with {@code tokens=0/0 cost=$0.0000}.
   *
   * <p>{@link AgentLoop#run} catches {@code Exception} and {@link
   * com.standardapplied.helios.session.hooks.HookRegistry} catches {@code RuntimeException}; in
   * practice only {@link Error} subtypes (OOM, StackOverflow, LinkageError, AssertionError from a
   * hook) reach the outer {@code catch}. We still capture {@link Throwable} as defense-in-depth
   * against future contract drift — without it a RuntimeException escape would leave callers
   * blocked on {@code result().join()} forever. The failure is held across the publisher drain so
   * {@code closeRuntime} always runs; {@link #rethrowSneakily} preserves the original throwable
   * type without an {@code instanceof} cascade that would leave unreachable branches in coverage.
   */
  private void runLoop(AgentLoop loop, SessionLimits limits) {
    ResultMessage terminal = null;
    Throwable failure = null;
    try {
      terminal = loop.run(state, limits);
    } catch (Throwable t) {
      failure = t;
    }
    closeRuntime();
    if (failure == null) {
      result.complete(terminal);
      return;
    }
    result.completeExceptionally(failure);
    rethrowSneakily(failure);
  }

  /**
   * End the session's runtime: notify the execution provider, close the event stream and stop the
   * wall-clock deadline. Called from exactly one of two mutually-exclusive paths — {@link
   * #closeBeforeStart()}, or {@link #runLoop} (after the loop returns, BEFORE the result future
   * settles) — and never both, because the {@code started} CAS gates entry.
   *
   * <p>{@link SessionEventPublisher#close()} waits a bounded grace period for live subscribers to
   * drain, so by the time this method returns every responsive subscriber has observed every
   * emitted event including the terminal {@link QueryEvent.LoopEnded}.
   */
  private void closeRuntime() {
    if (providerAccepted) {
      try {
        executionProvider.onSessionEnd(sessionContext);
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "onSessionEnd threw — continuing shutdown", e);
      }
    }
    events.close();
    var deadline = wallClockDeadline;
    if (deadline != null) {
      deadline.cancel(false);
    }
    deadlineScheduler.shutdownNow();
  }

  /**
   * Throw any {@link Throwable} as if it were unchecked, without an {@code instanceof} cascade. The
   * cast is erased at runtime; the JVM rethrows the original type. Used by {@link #runLoop} to
   * propagate an escaping {@link Error} (the only realistic shape reaching it) without leaving
   * unreachable RuntimeException / checked-exception branches behind.
   */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void rethrowSneakily(Throwable t) throws E {
    throw (E) t;
  }
}
