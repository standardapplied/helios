/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.ToolDispatch;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The end of one session's runtime, in the reverse order of what the session acquired, by one
 * deadline, {@link SessionLimits#stopGrace()} from the start of the session's close: the session is
 * cancelled ({@link com.standardapplied.helios.session.loop.SessionEnding#cancel}), which cancels
 * the run in progress and so cancels and interrupts every tool call still running, and the token's
 * callbacks and the calls are waited for until the deadline; the execution provider's {@code
 * onSessionEnd}, if the session has a provider, runs on a thread of its own and is waited for until
 * the same deadline; the event streams are ended, never waited for, since readers drain them on
 * their own threads. Every step runs, but one after a step that used the deadline up is not waited
 * for. A tool call or {@code onSessionEnd} still running at the deadline is logged as such, never
 * taken for ended.
 *
 * <p>An {@link Error} {@code onSessionEnd} throws, or carries as the cause of a joined future's
 * exception, is recorded by the session's {@link
 * com.standardapplied.helios.session.loop.SessionEnding#failWith}; any other exception is logged
 * and fails nothing on its own. Sealing the session's ending returns the first recorded {@code
 * Error}, carrying every later one, and an exception of {@code onSessionEnd}, as suppressed.
 */
final class SessionTeardown {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final SessionState state;
  private final SessionContext sessionContext;
  private final Optional<ExecutionProvider> executionProvider;
  private final SessionEventPublisher events;
  private final Duration grace;
  private volatile Exception sessionEndFailure;

  SessionTeardown(
      SessionState state,
      SessionContext sessionContext,
      Optional<ExecutionProvider> executionProvider,
      SessionEventPublisher events,
      Duration grace) {
    this.state = state;
    this.sessionContext = sessionContext;
    this.executionProvider = executionProvider;
    this.events = events;
    this.grace = grace;
  }

  /**
   * End the runtime by {@code deadlineNanos}, a {@link System#nanoTime()} instant, then seal the
   * session's ending.
   *
   * @param tools the session's tool dispatch, whose live calls are ended first
   * @param deadlineNanos when every wait of the teardown ends
   * @return the first {@link Error} recorded, carrying every later one and an exception of {@code
   *     onSessionEnd} as suppressed, or empty when none was recorded
   */
  Optional<Error> endAndSeal(ToolDispatch tools, long deadlineNanos) {
    end(tools, deadlineNanos);
    var settling = state.ending().seal();
    var failure = sessionEndFailure;
    if (failure != null) {
      settling.ifPresent(error -> error.addSuppressed(failure));
    }
    return settling;
  }

  /**
   * End the runtime of a session whose provider accepted it, at start-up or after the session
   * stopped waiting for it, by one {@code stopGrace} deadline from now; in the latter case the
   * runtime was already released, and releasing it again changes nothing.
   */
  void end(ToolDispatch tools) {
    end(tools, BoundedTask.deadlineAfter(grace));
  }

  /**
   * End the runtime by {@code deadlineNanos}, a {@link System#nanoTime()} instant. An exception
   * {@code onSessionEnd} throws that the session's ending does not record is kept for {@link
   * #endAndSeal}.
   */
  void end(ToolDispatch tools, long deadlineNanos) {
    if (!BoundedTask.settlesBy(state.ending().cancel("session ended"), deadlineNanos)) {
      LOGGER.warning(
          () ->
              "a callback of the cancellation of session "
                  + state.sessionId()
                  + " did not return within "
                  + grace);
    }
    tools.endLiveCalls(deadlineNanos, grace, "session " + state.sessionId());
    executionProvider.ifPresent(provider -> endProvider(provider, deadlineNanos));
    events.close();
  }

  private void endProvider(ExecutionProvider provider, long deadlineNanos) {
    var sessionEnd =
        BoundedTask.start(
            "helios-teardown-onSessionEnd-" + state.sessionId(),
            () -> onSessionEnd(provider),
            state.ending()::failWith);
    if (!sessionEnd.settlesBy(deadlineNanos)) {
      LOGGER.warning(
          () ->
              "onSessionEnd of "
                  + provider.getClass().getSimpleName()
                  + " did not return within "
                  + grace
                  + "; the session's provider resources are not known to be released, session "
                  + state.sessionId());
    }
  }

  /**
   * Call {@code onSessionEnd}, logging and keeping an exception the session's ending does not
   * record; an {@link Error} is the {@link BoundedTask}'s to record.
   */
  private Void onSessionEnd(ExecutionProvider provider) {
    try {
      provider.onSessionEnd(sessionContext);
    } catch (Exception e) {
      if (!state.ending().failWith(e)) {
        LOGGER.log(
            Level.WARNING,
            "onSessionEnd threw — continuing shutdown",
            SerializedError.redactedForLog(e, sessionContext.secrets().redactor()));
        sessionEndFailure = e;
      }
    }
    return null;
  }
}
