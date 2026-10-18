/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.ToolDispatch;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The end of one session's runtime, in the reverse order of what the session acquired: the session
 * is cancelled ({@link com.standardapplied.helios.session.loop.SessionEnding#cancel}), which
 * cancels and interrupts every tool call still running, and the token's callbacks and the calls are
 * given {@link SessionLimits#teardownGrace()} to end; the execution provider's {@code onSessionEnd}
 * runs on a thread of its own and is given the same grace to return; the scheduler is shut down;
 * event subscribers are given the same grace to drain. A tool call or {@code onSessionEnd} still
 * running when its grace has passed is logged as such, never taken for ended.
 *
 * <p>An {@link Error} {@code onSessionEnd} throws, or carries as the cause of a joined future's
 * exception, is recorded by the session's {@link
 * com.standardapplied.helios.session.loop.SessionEnding#failWith}; any other {@link
 * RuntimeException} is logged and fails nothing on its own. The result settles with what sealing
 * the session's ending returns: the first recorded {@code Error}, carrying every later one, and a
 * {@code RuntimeException} of {@code onSessionEnd}, as suppressed.
 */
final class SessionTeardown {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final SessionState state;
  private final SessionContext sessionContext;
  private final ExecutionProvider executionProvider;
  private final SessionEventPublisher events;
  private final ScheduledExecutorService scheduler;
  private final Duration grace;

  SessionTeardown(
      SessionState state,
      SessionContext sessionContext,
      ExecutionProvider executionProvider,
      SessionEventPublisher events,
      ScheduledExecutorService scheduler,
      Duration grace) {
    this.state = state;
    this.sessionContext = sessionContext;
    this.executionProvider = executionProvider;
    this.events = events;
    this.scheduler = scheduler;
    this.grace = grace;
  }

  /**
   * End the runtime, then seal the session's ending.
   *
   * @param tools the session's tool dispatch, whose live calls are ended first
   * @return the {@link Error} the result settles with, or empty to settle it with its terminal
   */
  Optional<Error> run(ToolDispatch tools) {
    var sessionEnd = end(tools);
    var settling = state.ending().seal();
    var stepFailure = sessionEnd.getNow(null);
    if (stepFailure != null) {
      settling.ifPresent(error -> error.addSuppressed(stepFailure));
    }
    return settling;
  }

  /**
   * End the runtime of a session whose provider accepted it, at start-up or after the session
   * stopped waiting for it; in the latter case the runtime was already released, and releasing it
   * again changes nothing.
   *
   * @return completes with the {@link RuntimeException} {@code onSessionEnd} threw, or {@code null}
   */
  CompletableFuture<RuntimeException> end(ToolDispatch tools) {
    if (!settlesWithin(state.ending().cancel("session ended"))) {
      LOGGER.warning(
          () ->
              "a callback of the cancellation of session "
                  + state.sessionId()
                  + " did not return within "
                  + grace);
    }
    for (var call : tools.awaitLiveCalls(grace)) {
      LOGGER.warning(
          () ->
              "tool '"
                  + call.name()
                  + "' (call "
                  + call.id()
                  + ") still runs "
                  + grace
                  + " after session "
                  + state.sessionId()
                  + " ended; its effects may land after the result settled");
    }
    var sessionEnd = onSessionEnd();
    if (!settlesWithin(sessionEnd)) {
      LOGGER.warning(
          () ->
              "onSessionEnd of "
                  + executionProvider.getClass().getSimpleName()
                  + " did not return within "
                  + grace
                  + "; the session's provider resources are not known to be released, session "
                  + state.sessionId());
    }
    release();
    return sessionEnd;
  }

  /** Shut the scheduler down and close the event stream: what the session acquired first. */
  void release() {
    scheduler.shutdownNow();
    events.close(grace);
  }

  /**
   * Run {@code onSessionEnd} on a thread of its own, so one that never returns holds nothing up.
   */
  private CompletableFuture<RuntimeException> onSessionEnd() {
    var done = new CompletableFuture<RuntimeException>();
    Thread.ofVirtual()
        .name("helios-teardown-onSessionEnd-" + state.sessionId())
        .start(
            () -> {
              try {
                executionProvider.onSessionEnd(sessionContext);
                done.complete(null);
              } catch (RuntimeException | Error e) {
                if (state.ending().failWith(e)) {
                  done.complete(null);
                } else {
                  LOGGER.log(Level.WARNING, "onSessionEnd threw — continuing shutdown", e);
                  done.complete((RuntimeException) e);
                }
              }
            });
    return done;
  }

  /**
   * Wait up to the grace for {@code step}; an interrupt of the waiting thread ends the wait, its
   * interrupt status kept.
   *
   * @return {@code true} if the step completed within the grace
   */
  private boolean settlesWithin(CompletableFuture<?> step) {
    try {
      step.get(grace.toNanos(), TimeUnit.NANOSECONDS);
      return true;
    } catch (ExecutionException | TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
