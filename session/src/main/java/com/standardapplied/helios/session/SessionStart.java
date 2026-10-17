/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.loop.SessionState;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The execution provider's {@code onSessionStart}, run on a thread of its own and waited for within
 * a bound. A provider that refuses, returns {@code null}, throws a {@link RuntimeException}, or has
 * not returned when the bound passes or the waiting thread is interrupted, refuses the session; one
 * that throws an {@link Error} fails it with that error. A start that settles after the session
 * stopped waiting is logged as a {@code WARNING}, and a late acceptance is then ended by the
 * session's bounded teardown, so the provider is never left holding a session nobody ends.
 */
final class SessionStart {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final SessionState state;
  private final SessionContext sessionContext;
  private final ExecutionProvider executionProvider;
  private final Runnable lateTeardown;

  /**
   * @param lateTeardown ends a provider that accepted after the session stopped waiting; what it
   *     throws is logged
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
   * @return the refusal the session ends with, its reason and cause redacted against the session's
   *     secret registry, or empty if the provider accepted
   * @throws Error the error {@code onSessionStart} threw, as itself
   */
  Optional<ResultMessage.ErrorProviderUnavailable> await(Duration bound) {
    var outcome = new CompletableFuture<SessionStartOutcome>();
    var worker =
        Thread.ofVirtual()
            .name("helios-session-start-" + state.sessionId())
            .start(() -> run(outcome));
    waitFor(outcome, bound)
        .ifPresent(
            reason -> {
              outcome.complete(SessionStartOutcome.refuse(reason));
              worker.interrupt();
            });
    try {
      return judged(outcome.join());
    } catch (CompletionException e) {
      return thrown(e.getCause());
    }
  }

  /**
   * Wait up to {@code bound} for the provider to settle the start.
   *
   * @return why the session stopped waiting, or empty if the provider settled it in time
   */
  private static Optional<String> waitFor(
      CompletableFuture<SessionStartOutcome> outcome, Duration bound) {
    try {
      outcome.get(bound.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      return Optional.empty();
    } catch (TimeoutException e) {
      return Optional.of("onSessionStart did not return within " + bound);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of("interrupted while waiting for onSessionStart");
    }
    return Optional.empty();
  }

  /**
   * Settle {@code outcome} with what {@code onSessionStart} did; if the session stopped waiting
   * first, report what came too late and end a late acceptance. What it threw fails {@code outcome}
   * as {@link SessionTeardown#asCompletion}: a {@link java.util.concurrent.CancellationException}
   * refuses the session instead of cancelling {@code outcome}, and an {@link Error} a {@code join}
   * carries fails the session as itself.
   */
  private void run(CompletableFuture<SessionStartOutcome> outcome) {
    SessionStartOutcome started;
    try {
      started = executionProvider.onSessionStart(sessionContext);
    } catch (Throwable t) {
      if (!outcome.completeExceptionally(SessionTeardown.asCompletion(t))) {
        LOGGER.log(Level.WARNING, "onSessionStart threw after " + abandonment(), t);
      }
      return;
    }
    if (!outcome.complete(started)) {
      lateOutcome(started instanceof SessionStartOutcome.Accept);
    }
  }

  private void lateOutcome(boolean accepted) {
    LOGGER.warning(
        () ->
            "onSessionStart returned after "
                + abandonment()
                + (accepted ? "; its acceptance is ended by the session's teardown" : ""));
    if (accepted) {
      // The interrupt that gave up on onSessionStart must not cut the teardown's waits short.
      Thread.interrupted();
      try {
        lateTeardown.run();
      } catch (Throwable t) {
        LOGGER.log(
            Level.WARNING, "the teardown of a late acceptance threw after " + abandonment(), t);
      }
    }
  }

  private String abandonment() {
    return "session "
        + state.sessionId()
        + " stopped waiting for "
        + executionProvider.getClass().getSimpleName();
  }

  private Optional<ResultMessage.ErrorProviderUnavailable> judged(SessionStartOutcome outcome) {
    return switch (outcome) {
      case null -> Optional.of(refusal("onSessionStart returned null", null));
      case SessionStartOutcome.Refuse refuse ->
          Optional.of(refusal(refuse.reason(), refuse.cause()));
      case SessionStartOutcome.Accept accept -> Optional.empty();
    };
  }

  private Optional<ResultMessage.ErrorProviderUnavailable> thrown(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    return Optional.of(
        refusal(
            "onSessionStart threw "
                + failure.getClass().getSimpleName()
                + ": "
                + (failure.getMessage() == null ? "(no message)" : failure.getMessage()),
            failure));
  }

  private ResultMessage.ErrorProviderUnavailable refusal(String reason, Throwable cause) {
    var redactor = sessionContext.secrets().redactor();
    var serialised = cause == null ? null : SerializedError.of(cause).redactedBy(redactor);
    return new ResultMessage.ErrorProviderUnavailable(
        state.sessionId(),
        executionProvider.getClass().getSimpleName(),
        redactor.redact(reason).text(),
        serialised,
        state.totals().usage(),
        state.totals().cost(),
        state.elapsed());
  }
}
