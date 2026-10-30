/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.StoredHistory;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Start-up and shutdown of one session. Acquires the session's event stream and notifies the {@link
 * ExecutionProvider}, if the session has one, that the session starts once everything else is built
 * ({@link SessionStart}, bounded by {@link SessionLimits#startTimeout()}); a provider that does not
 * accept fails the open. Its {@link SessionRuns} run the session's runs in between. Its {@link
 * SessionTeardown} releases what the session acquired, in reverse order, exactly once: on {@link
 * #close}, or on the thread of the last run once the session closed or a host {@link Error} failed
 * it. The session's ending is sealed once too, only once no run is left to fail with what it
 * recorded.
 */
final class SessionLifecycle {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final SessionState session;
  private final SessionLimits limits;
  private final SessionEventPublisher events;
  private final Optional<SessionStart> start;
  private final SessionTeardown teardown;
  private final SessionRuns runs;
  private final AtomicBoolean tearingDown = new AtomicBoolean();
  private final CompletableFuture<Void> tornDown = new CompletableFuture<>();
  private final AtomicBoolean sealed = new AtomicBoolean();
  private volatile Long closeDeadline;
  private AgentLoop loop;

  SessionLifecycle(
      SessionState session,
      SessionContext sessionContext,
      Optional<ExecutionProvider> executionProvider,
      SessionLimits limits,
      SteeringQueue queue,
      StoredHistory storedHistory) {
    this.session = session;
    this.limits = limits;
    this.events = new SessionEventPublisher(session.sessionId());
    this.teardown =
        new SessionTeardown(session, sessionContext, executionProvider, events, limits.stopGrace());
    this.runs = new SessionRuns(session, queue, limits, storedHistory, this::endAfterRun);
    this.start =
        executionProvider.map(
            provider ->
                new SessionStart(
                    session, sessionContext, provider, () -> teardown.end(loop.toolDispatch())));
  }

  /** The session's event stream, which the teardown closes. */
  SessionEventPublisher events() {
    return events;
  }

  /** The session's runs. */
  SessionRuns runs() {
    return runs;
  }

  /** Release what the lifecycle acquired, for a session whose construction failed. */
  void abandon() {
    events.close();
  }

  /**
   * Bind the loop and notify the execution provider, if any, that the session starts, the last step
   * of the session's construction, waiting for it within {@code startTimeout}. A start the provider
   * does not accept ({@link SessionStart}) releases what the lifecycle acquired and throws, and an
   * {@link Error} that reaches the session after that is logged. Once open, the first host {@code
   * Error} fails the session's runs ({@link SessionRuns#failed}). {@code onSessionEnd} is called
   * only if the provider accepts after the session stopped waiting, by the {@link SessionTeardown}
   * that ends that late acceptance.
   *
   * @throws SessionUnavailableException if the provider did not accept the session
   * @throws Error the {@link Error} {@code onSessionStart} threw, as itself
   */
  void open(AgentLoop loop, Optional<SessionTraceRecorder> recorder) {
    this.loop = loop;
    runs.bind(loop, recorder);
    try {
      start
          .flatMap(provider -> provider.await(limits.startTimeout()))
          .ifPresent(
              refusal -> {
                throw refusal;
              });
    } catch (RuntimeException | Error e) {
      session.ending().seal();
      abandon();
      throw e;
    }
    session.ending().onFirstFailure(runs::failed);
  }

  /**
   * Close the session: no run starts from now on, the session is cancelled, which cancels the run
   * in progress with the reason {@code "session closed"} before this returns, and that run's thread
   * tears the session down and then settles the run's future, and that of a next run the close
   * keeps from starting; the close waits for those threads to end, so both futures are settled when
   * it returns and none of their callbacks runs on the closing thread. Without a run in progress
   * the caller tears the session down. The run's end and the teardown share one deadline, {@code
   * stopGrace} from now; a step after one that used it up still runs but is not waited for. A run
   * still running at the deadline seals the session's ending itself once it was left, so an {@link
   * Error} it records after the close returned still fails its future. Called on a thread the run
   * in progress started, it returns once the session is cancelled. Never throws.
   */
  void close() {
    var deadline = BoundedTask.deadlineAfter(limits.stopGrace());
    closeDeadline = deadline;
    var run = runs.close();
    session.ending().cancel("session closed");
    run.ifPresent(Run::followSession);
    if (run.isPresent() && run.get().isThreadOfThisRun()) {
      return;
    }
    run.ifPresent(running -> running.awaitEnd(deadline));
    end(deadline);
    if (run.isEmpty()) {
      seal(Optional.empty());
    }
  }

  /** Expire the run in progress as its wall clock does, for tests. */
  void expireWallClock() {
    runs.activeRun().ifPresent(Run::expireWallClock);
  }

  /**
   * Tear down after the last run, which failed with {@code carried} if present, by the close's
   * deadline, or {@code stopGrace} from now, then seal the session's ending: once the run was left,
   * no {@link Error} recorded after that has a run to fail.
   */
  private void endAfterRun(Optional<Error> carried) {
    var deadline = closeDeadline;
    end(deadline != null ? deadline : BoundedTask.deadlineAfter(limits.stopGrace()));
    seal(carried);
  }

  /**
   * Run the teardown once, by {@code deadlineNanos}; a caller that finds it under way waits for it
   * until the same deadline.
   */
  private void end(long deadlineNanos) {
    if (!tearingDown.compareAndSet(false, true)) {
      BoundedTask.settlesBy(tornDown, deadlineNanos);
      return;
    }
    try {
      teardown.end(loop.toolDispatch(), deadlineNanos);
    } finally {
      tornDown.complete(null);
    }
  }

  /**
   * Seal the session's ending once. An {@link Error} it sealed that no run failed with, {@code
   * carried}, is logged, since no future carries it.
   */
  private void seal(Optional<Error> carried) {
    if (!sealed.compareAndSet(false, true)) {
      return;
    }
    teardown
        .seal()
        .filter(error -> carried.orElse(null) != error)
        .ifPresent(
            error ->
                LOGGER.log(
                    Level.SEVERE,
                    "an Error reached session " + session.sessionId() + " while it closed",
                    error));
  }
}
