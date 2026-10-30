/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.loop.SessionState;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The execution provider's {@code onSessionStart}, run on a thread of its own and waited for within
 * a bound. A provider that refuses, returns {@code null}, throws an {@link Exception}, or has not
 * returned when the bound passes or the waiting thread is interrupted, refuses the session with a
 * {@link SessionUnavailableException}; an {@link Error} it throws, or carries as the cause of a
 * joined future's exception, is recorded by the session's {@link
 * com.standardapplied.helios.session.loop.SessionEnding#failWith} and thrown as itself. A start
 * that settles after the session stopped waiting is logged as a {@code WARNING}, and a late
 * acceptance is then ended by the session's bounded teardown, so the provider is never left holding
 * a session nobody ends. That teardown runs on a thread of its own, which the interrupt that gave
 * up on {@code onSessionStart} never reaches, so it waits until its own {@code stopGrace} deadline.
 */
final class SessionStart {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final SessionState state;
  private final SessionContext sessionContext;
  private final ExecutionProvider executionProvider;
  private final Runnable lateTeardown;

  /**
   * @param lateTeardown ends a provider that accepted after the session stopped waiting; run on a
   *     thread of its own
   */
  SessionStart(
      SessionState state,
      SessionContext sessionContext,
      ExecutionProvider executionProvider,
      Runnable lateTeardown) {
    this.state = state;
    this.sessionContext = sessionContext;
    this.executionProvider = executionProvider;
    this.lateTeardown = lateTeardown;
  }

  /**
   * Run {@code onSessionStart} and wait up to {@code bound} for it.
   *
   * @return the refusal to throw, its reason redacted against the session's secret registry, or
   *     empty if the provider accepted
   * @throws Error the {@link Error} {@code onSessionStart} threw, as itself
   */
  Optional<SessionUnavailableException> await(Duration bound) {
    var task =
        BoundedTask.start(
            "helios-session-start-" + state.sessionId(),
            () -> executionProvider.onSessionStart(sessionContext),
            state.ending()::failWith);
    if (task.settlesBy(BoundedTask.deadlineAfter(bound))) {
      state
          .ending()
          .first()
          .ifPresent(
              error -> {
                throw error;
              });
      return judged(task.outcome().handle(SessionStart::settled).join());
    }
    var reason =
        Thread.currentThread().isInterrupted()
            ? "interrupted while waiting for onSessionStart"
            : "onSessionStart did not return within " + bound;
    task.interrupt();
    task.outcome().whenComplete((started, thrown) -> lateOutcome(settled(started, thrown)));
    return Optional.of(refusal(reason, null));
  }

  /** What {@code onSessionStart} did, a throw as a refusal carrying it. */
  private static SessionStartOutcome settled(SessionStartOutcome started, Throwable thrown) {
    if (thrown == null) {
      return started;
    }
    return SessionStartOutcome.refuse(
        "onSessionStart threw "
            + thrown.getClass().getSimpleName()
            + ": "
            + (thrown.getMessage() == null ? "(no message)" : thrown.getMessage()),
        thrown);
  }

  private void lateOutcome(SessionStartOutcome started) {
    var accepted = started instanceof SessionStartOutcome.Accept;
    LOGGER.log(
        Level.WARNING,
        "onSessionStart settled after "
            + abandonment()
            + (accepted ? "; its acceptance is ended by the session's teardown" : ""),
        started instanceof SessionStartOutcome.Refuse refuse ? refuse.cause() : null);
    if (accepted) {
      Thread.ofVirtual()
          .name("helios-session-late-teardown-" + state.sessionId())
          .start(lateTeardown);
    }
  }

  private String abandonment() {
    return "session "
        + state.sessionId()
        + " stopped waiting for "
        + executionProvider.getClass().getSimpleName();
  }

  private Optional<SessionUnavailableException> judged(SessionStartOutcome outcome) {
    return switch (outcome) {
      case null -> Optional.of(refusal("onSessionStart returned null", null));
      case SessionStartOutcome.Refuse refuse ->
          Optional.of(refusal(refuse.reason(), refuse.cause()));
      case SessionStartOutcome.Accept accept -> Optional.empty();
    };
  }

  private SessionUnavailableException refusal(String reason, Throwable cause) {
    return new SessionUnavailableException(
        executionProvider.getClass().getSimpleName()
            + " refused session "
            + state.sessionId()
            + ": "
            + sessionContext.secrets().redactor().redact(reason).text(),
        cause);
  }
}
