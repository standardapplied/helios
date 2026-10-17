/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.ToolDispatch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The end of one session's runtime, in the reverse order of what the session acquired: the session
 * token is cancelled ({@link #cancel}, shared with whoever cancelled it first), which cancels and
 * interrupts every tool call still running, and the calls are given {@link
 * SessionLimits#teardownGrace()} to end; the execution provider's {@code onSessionEnd} is given the
 * same grace to return; the scheduler is shut down; event subscribers are given the same grace to
 * drain. A step that throws does not keep the next from running, and a tool call or {@code
 * onSessionEnd} still running when its grace has passed is logged as such, never taken for ended.
 *
 * <p>What the result settles with follows from the failures: the loop's {@link Error}, if it threw
 * one, else the first {@code Error} of a step, with every other failure of a step added as
 * suppressed. A step's {@link RuntimeException} is logged and fails nothing on its own. What a step
 * threw after its grace passed but before the result settles counts as well, as does every {@link
 * ToolDispatch#strayErrors() stray Error} of the session's work.
 */
final class SessionTeardown {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final SessionState state;
  private final SessionContext sessionContext;
  private final ExecutionProvider executionProvider;
  private final SessionEventPublisher events;
  private final ScheduledExecutorService scheduler;
  private final Duration grace;
  private final AtomicReference<Error> observedCallbackError = new AtomicReference<>();
  private CompletableFuture<Void> cancellation;

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
   * Cancel the session's token with {@code reason} once ({@link
   * com.standardapplied.helios.core.runtime.CancellationToken#cancelAsync}): the token is cancelled
   * when this returns, and its callbacks run on a thread of their own; a later call, the teardown's
   * included, returns the same completion. Neither {@link AgentSession#close()} nor the wall-clock
   * deadline is therefore held up by a cancellation callback that blocks or throws; the teardown
   * waits for the callbacks within its grace and settles the result with what they threw, an {@link
   * Error} included that a callback throws after that wait ended, while a later teardown step still
   * runs, or before a later callback that never returns.
   */
  synchronized CompletableFuture<Void> cancel(String reason) {
    if (cancellation == null) {
      cancellation =
          state
              .cancellation()
              .cancelAsync(reason, error -> observedCallbackError.compareAndSet(null, error));
    }
    return cancellation;
  }

  /**
   * End the runtime of a session whose provider accepted it, at start-up or after the session
   * stopped waiting for it; in the latter case the runtime was already released, and releasing it
   * again changes nothing.
   *
   * @param tools the session's tool dispatch, whose live calls are ended first
   * @param primary the {@link Error} the loop threw, or {@code null}
   * @return the failure the result settles with, or {@code null} to settle it with its terminal
   */
  Error run(ToolDispatch tools, Error primary) {
    var failures = new Failures(primary);
    endToolCalls(tools, failures);
    var end = start("onSessionEnd", () -> executionProvider.onSessionEnd(sessionContext));
    if (!settlesWithin("onSessionEnd", end, failures)) {
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
    if (end.isCompletedExceptionally()) {
      failures.add("onSessionEnd", end.exceptionNow());
    }
    tools.strayErrors().all().forEach(error -> failures.add("the session's work", error));
    return failures.settling(observedCallbackError.get());
  }

  /** Shut the scheduler down and close the event stream: what the session acquired first. */
  void release() {
    scheduler.shutdownNow();
    events.close(grace);
  }

  private void endToolCalls(ToolDispatch tools, Failures failures) {
    if (!settlesWithin("cancellation", cancel("session ended"), failures)) {
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
  }

  /**
   * Run {@code step} on a thread of its own, so a step that never returns holds nothing up. What it
   * throws is wrapped in a {@link CompletionException}, so a {@link
   * java.util.concurrent.CancellationException} it throws fails the step instead of cancelling it.
   */
  private CompletableFuture<Void> start(String name, Runnable step) {
    var done = new CompletableFuture<Void>();
    Thread.ofVirtual()
        .name("helios-teardown-" + name + "-" + state.sessionId())
        .start(
            () -> {
              try {
                step.run();
                done.complete(null);
              } catch (Throwable t) {
                done.completeExceptionally(new CompletionException(t));
              }
            });
    return done;
  }

  /**
   * Wait up to the grace for {@code step}. What it threw goes to {@code failures} under {@code
   * name}; an interrupt of the waiting thread ends the wait, its interrupt status kept.
   *
   * @return {@code true} if the step returned or threw within the grace
   */
  private boolean settlesWithin(String name, CompletableFuture<Void> step, Failures failures) {
    try {
      step.get(grace.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      failures.add(name, e.getCause());
    } catch (TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    return true;
  }

  /**
   * The loop's failure and those of the teardown's steps, in the order they happened, each kept
   * once: a step's failure may be collected both by its wait and at settlement.
   */
  private static final class Failures {

    private final Error primary;
    private final List<Throwable> steps = new ArrayList<>();

    Failures(Error primary) {
      this.primary = primary;
    }

    void add(String step, Throwable failure) {
      if (steps.stream().anyMatch(seen -> seen == failure)) {
        return;
      }
      if (failure instanceof RuntimeException) {
        LOGGER.log(Level.WARNING, step + " threw — continuing shutdown", failure);
      }
      steps.add(failure);
    }

    /**
     * @param observedCancellation the first {@link Error} a cancellation callback threw, read once
     *     every step has ended or had its grace, so one thrown while a later step still ran counts;
     *     {@code null} if none
     */
    Error settling(Error observedCancellation) {
      if (observedCancellation != null
          && steps.stream().noneMatch(failure -> failure == observedCancellation)) {
        steps.addFirst(observedCancellation);
      }
      var settling = primary != null ? primary : firstError();
      if (settling != null) {
        steps.stream().filter(failure -> failure != settling).forEach(settling::addSuppressed);
      }
      return settling;
    }

    private Error firstError() {
      return steps.stream()
          .filter(Error.class::isInstance)
          .map(Error.class::cast)
          .findFirst()
          .orElse(null);
    }
  }
}
