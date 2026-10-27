/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.SessionState;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
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
 * it.
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
  private volatile Long closeDeadline;
  private AgentLoop loop;

  SessionLifecycle(
      SessionState session,
      SessionContext sessionContext,
      Optional<ExecutionProvider> executionProvider,
      SessionLimits limits,
      SteeringQueue queue) {
    this.session = session;
    this.limits = limits;
    this.events = new SessionEventPublisher(session.sessionId());
    this.teardown =
        new SessionTeardown(session, sessionContext, executionProvider, events, limits.stopGrace());
    this.runs = new SessionRuns(session, queue, limits, this::endAfterRun);
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
  void open(AgentLoop loop) {
    this.loop = loop;
    runs.bind(loop);
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
   * in progress with the reason {@code "session closed"}, and the session is torn down once that
   * run ended. The run's end and the teardown share one deadline, {@code stopGrace} from now; a
   * step after one that used it up still runs but is not waited for. Called on a thread the
   * session's runs started, it returns once the session is cancelled, and the run's own thread
   * finishes the teardown. Never throws.
   */
  void close() {
    var deadline = BoundedTask.deadlineAfter(limits.stopGrace());
    closeDeadline = deadline;
    var run = runs.close();
    session.ending().cancel("session closed");
    Optional<Error> carried = Optional.empty();
    if (run.isPresent()) {
      if (runs.isWorkerThread()) {
        return;
      }
      var result = run.get().result();
      if (BoundedTask.settlesBy(result, deadline)
          && result.state() == Future.State.FAILED
          && result.exceptionNow() instanceof Error error) {
        carried = Optional.of(error);
      }
    }
    tearDown(deadline, carried);
  }

  /** Expire the run in progress as its wall clock does, for tests. */
  void expireWallClock() {
    runs.activeRun().ifPresent(Run::expireWallClock);
  }

  /**
   * Tear down after the last run, which failed with {@code carried} if present, by the close's
   * deadline, or {@code stopGrace} from now.
   */
  private void endAfterRun(Optional<Error> carried) {
    var deadline = closeDeadline;
    tearDown(deadline != null ? deadline : BoundedTask.deadlineAfter(limits.stopGrace()), carried);
  }

  /**
   * Run the teardown once, by {@code deadlineNanos}; a caller that finds it under way waits for it
   * until the same deadline. An {@link Error} the teardown sealed that no run failed with, {@code
   * carried}, is logged, since no future carries it.
   */
  private void tearDown(long deadlineNanos, Optional<Error> carried) {
    if (!tearingDown.compareAndSet(false, true)) {
      BoundedTask.settlesBy(tornDown, deadlineNanos);
      return;
    }
    try {
      teardown
          .endAndSeal(loop.toolDispatch(), deadlineNanos)
          .filter(error -> carried.orElse(null) != error)
          .ifPresent(
              error ->
                  LOGGER.log(
                      Level.SEVERE,
                      "an Error reached session " + session.sessionId() + " while it closed",
                      error));
    } finally {
      tornDown.complete(null);
    }
  }
}
