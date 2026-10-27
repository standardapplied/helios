/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.session.AgentSessionImpl;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.store.SessionStoreException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Top-level orchestrator that drives one run of a session to a terminal {@link ResultMessage}.
 *
 * <p>A run starts with {@link QueryEvent.RunStarted}, then every iteration:
 *
 * <ol>
 *   <li>Drain the {@link SteeringQueue}. Each pending {@link UserMessage} fires {@link
 *       HookRegistry#fireOnUserMessage on-user-message hooks} — outcomes can drop the message
 *       ({@link HookOutcome.Block Block}), rewrite its text ({@link HookOutcome.MutateText
 *       MutateText}), or end the run ({@link HookOutcome.Stop Stop}). Surviving messages emit
 *       {@link QueryEvent.UserMessageReceived} and compose into a single user-role message appended
 *       to history.
 *   <li>Before the run's first turn, if no message reached the history (every one was blocked) and
 *       none was sent meanwhile, end with {@link ResultMessage.ErrorDuringExecution} of kind {@code
 *       NoUserMessage}: a run calls the model only after a user message of its own.
 *   <li>Advance the turn counter and call {@link TurnRunner#runTurn(SessionState, SessionLimits)},
 *       then append what the turn added to the session's store, if it has one ({@link
 *       StoredHistory}).
 *   <li>If the turn left a terminal value on {@link SessionState} (hook-driven stop), respect it.
 *       Otherwise hand the outcome to {@link StopClassifier}; if it returns terminal, fire {@link
 *       HookRegistry#firePreStop pre-stop hooks} — {@link HookOutcome.Inject Inject} cancels the
 *       termination and queues a synthetic user message, and the turn is classified again as one
 *       with a message queued, so the run's limits still apply; {@link HookOutcome.Stop Stop}
 *       overrides the success text; {@link HookOutcome.Continue Continue} confirms the stop.
 *   <li>Otherwise iterate.
 * </ol>
 *
 * <p>Every terminal is decided through the session's {@link RunBoundary}, under the lock {@code
 * send} takes: a {@code Success} that holds only while no message is queued gives way to a message
 * sent meanwhile, and the run goes on with it. Once decided, the run ends: an assistant message
 * whose tool calls are not all answered gets a "not run" tool message per unanswered call, in call
 * order, so the next run's history is one a provider accepts; what the history gained is appended
 * to the session's store, a refused write ending the run with {@link
 * ResultMessage.ErrorDuringExecution} of kind {@code SessionConflict} or {@code SessionStoreFailed}
 * instead; the run's token is cancelled, and its callbacks and the tool calls still running are
 * awaited until one deadline, {@link SessionLimits#stopGrace()} away, each one still running then
 * logged; then {@link QueryEvent.RunEnded} is emitted.
 *
 * <p>Every emitted {@link QueryEvent} fires {@link HookRegistry#fireOnStreamEvent stream-event
 * hooks} for observe-only consumers.
 *
 * <p>Draining the queue is {@link SteeringDrain}, the watermark and compaction step before and
 * after every turn is {@link ContextCompaction}, and the pre-stop decision is {@link
 * PreStopDecision}; this class keeps the iteration.
 *
 * <h2>Thread-safety</h2>
 *
 * One AgentLoop instance per session, with collaborators built for that session: {@link
 * StopClassifier} and {@link HookRegistry} hold its secret registry. {@link #run(SessionState,
 * SessionLimits, RunBoundary)} runs one run at a time on a single virtual thread; {@link
 * SessionState}, {@link SteeringQueue} and {@link ToolDispatch} are owned by the caller.
 */
public final class AgentLoop {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  static final String NOT_RUN = "[not run: the run ended before this call ran]";

  private static final String NO_USER_MESSAGE =
      "the run's first turn needs a user message: every message queued for it was blocked";

  private final LoopCollaborators collaborators;
  private final TurnRunner turnRunner;
  private final StopClassifier classifier;
  private final EventEmitter emitter;
  private final SteeringDrain steeringDrain;
  private final ContextCompaction compaction;
  private final PreStopDecision preStop;
  private final StoredHistory storedHistory;

  public AgentLoop(
      LoopCollaborators collaborators,
      TurnRunner turnRunner,
      StopClassifier classifier,
      TokenCounter tokenCounter,
      ContextCompactor contextCompactor,
      StoredHistory storedHistory) {
    this.collaborators = collaborators;
    this.storedHistory = storedHistory;
    this.turnRunner = turnRunner;
    this.classifier = classifier;
    this.emitter = collaborators.emitter();
    this.steeringDrain =
        new SteeringDrain(
            collaborators, emitter, new HookEffects(emitter, collaborators.steeringQueue()));
    this.compaction =
        new ContextCompaction(collaborators, emitter, turnRunner, tokenCounter, contextCompactor);
    this.preStop = new PreStopDecision(collaborators, emitter);
  }

  /**
   * Run one run to terminal and end it.
   *
   * @param state the run's state; non-null
   * @param limits the session's limits, {@code maxTurns}, {@code maxBudgetMicroUsd} and {@code
   *     maxWallClock} counted for this run; non-null
   * @param boundary decides the run's end under the lock the session's {@code send} takes; non-null
   * @return the terminal {@link ResultMessage}; never null, {@link
   *     ResultMessage.ErrorDuringExecution} for any {@link Exception} that reached the loop; one
   *     that carries an {@link Error} as a joined future's cause is also recorded by the session's
   *     {@link SessionEnding#failWith}, so the run fails with that {@code Error}
   * @throws NullPointerException if an argument is null
   * @throws Error an {@link Error} the model, a tool or a hook threw, as itself: a host failure,
   *     never a terminal
   */
  public ResultMessage run(SessionState state, SessionLimits limits, RunBoundary boundary) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(boundary, "boundary must not be null");
    emitter.emit(
        state,
        new QueryEvent.RunStarted(
            state.sessionId(), state.runId(), collaborators.clock().instant()));
    ResultMessage terminal;
    try {
      Optional<ResultMessage> ended = Optional.empty();
      while (ended.isEmpty()) {
        ended = iterate(state, limits, boundary);
      }
      terminal = ended.orElseThrow();
    } catch (Exception e) {
      state.ending().failWith(e);
      var redactor = collaborators.secrets().redactor();
      terminal =
          decided(
              state,
              limits,
              boundary,
              e instanceof SessionStoreException refused
                  ? StoredHistory.refusal(state, refused, redactor)
                  : StopClassifier.errorDuringExecution(
                      state, SerializedError.of(e).redactedBy(redactor)));
    }
    return end(state, limits, terminal);
  }

  /** One iteration of the loop: the terminal it ended with, or empty to iterate again. */
  private Optional<ResultMessage> iterate(
      SessionState state, SessionLimits limits, RunBoundary boundary) {
    if (state.isTerminal()) {
      return Optional.of(decided(state, limits, boundary, state.terminal().orElseThrow()));
    }
    var received = steeringDrain.drainInto(state);
    if (state.isTerminal()) {
      return Optional.of(decided(state, limits, boundary, state.terminal().orElseThrow()));
    }
    if (!received && state.currentTurnIndex() == 0) {
      if (!boundary.end(true)) {
        return Optional.empty();
      }
      var noUserMessage = SerializedError.of("NoUserMessage", NO_USER_MESSAGE);
      return Optional.of(
          finished(state, limits, StopClassifier.errorDuringExecution(state, noUserMessage)));
    }
    state.beginTurn();
    compaction.check(state, limits);
    var outcome = turnRunner.runTurn(state, limits);
    storedHistory.write(state, collaborators.clock().instant());
    if (state.isTerminal()) {
      return Optional.of(decided(state, limits, boundary, state.terminal().orElseThrow()));
    }
    compaction.check(state, limits);
    return classify(state, limits, boundary, outcome);
  }

  /**
   * The classifier's and the pre-stop hooks' verdict on a turn, decided through {@code boundary}: a
   * {@code Success} that holds only while no message is queued gives way to a message sent
   * meanwhile, and the turn is classified again as one with a message queued.
   */
  private Optional<ResultMessage> classify(
      SessionState state, SessionLimits limits, RunBoundary boundary, TurnOutcome outcome) {
    var hasQueuedMessages = collaborators.steeringQueue().size() > 0;
    var verdict = classifier.classify(state, limits, outcome, hasQueuedMessages);
    if (verdict.isEmpty()) {
      return Optional.empty();
    }
    var resolved =
        preStop
            .resolve(state, verdict.get(), outcome)
            .or(() -> classifier.classify(state, limits, outcome, true));
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    var natural = resolved.get() == verdict.get() && verdict.get() instanceof ResultMessage.Success;
    if (natural && !boundary.end(true)) {
      return classifier
          .classify(state, limits, outcome, true)
          .map(limit -> decided(state, limits, boundary, limit));
    }
    return Optional.of(
        natural
            ? finished(state, limits, resolved.get())
            : decided(state, limits, boundary, resolved.get()));
  }

  /**
   * Decide through {@code boundary} that the run ends with {@code result}, a non-natural stop: a
   * hook's {@code Stop}, a limit, a run with no user message or a failure.
   */
  private ResultMessage decided(
      SessionState state, SessionLimits limits, RunBoundary boundary, ResultMessage result) {
    boundary.end(false);
    return finished(state, limits, result);
  }

  /**
   * Record the run's final terminal: {@code result}, unless a cancellation that arrived before the
   * boundary decided the end, while a hook or the turn ran, imposes its own.
   */
  private ResultMessage finished(SessionState state, SessionLimits limits, ResultMessage result) {
    var terminal =
        state.cancellation().isCancelled()
            ? classifier.imposed(state, limits).orElseThrow()
            : result;
    state.setFinalTerminal(terminal);
    return terminal;
  }

  /**
   * End the decided run: close an open tool round, append what the history gained to the store,
   * cancel the run's token, wait up to {@code stopGrace} for its tool calls still running, then
   * emit {@link QueryEvent.RunEnded}.
   */
  private ResultMessage end(SessionState state, SessionLimits limits, ResultMessage decided) {
    // An interrupt host code left on this thread is no request to cut the run's waits short.
    Thread.interrupted();
    state.history().closeOpenRound(NOT_RUN);
    var terminal =
        storedHistory.written(
            state, collaborators.clock().instant(), decided, collaborators.secrets().redactor());
    var deadline = System.nanoTime() + limits.stopGrace().toNanos();
    var run = "run " + state.runId() + " of session " + state.sessionId();
    if (!settlesBy(state.cancel("run ended"), deadline)) {
      LOGGER.warning(
          () ->
              "a callback of the cancellation of "
                  + run
                  + " did not return within "
                  + limits.stopGrace());
    }
    collaborators.toolDispatch().endLiveCalls(deadline, limits.stopGrace(), run);
    emitter.emit(
        state,
        new QueryEvent.RunEnded(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            terminal));
    return terminal;
  }

  /**
   * Whether {@code cancelling} completes by {@code deadlineNanos}; an interrupt ends the wait, its
   * status kept.
   */
  private static boolean settlesBy(CompletableFuture<Void> cancelling, long deadlineNanos) {
    try {
      cancelling.get(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
      return true;
    } catch (ExecutionException | TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** The context watermark and compaction step, exposed so tests can drive its resolver. */
  ContextCompaction compaction() {
    return compaction;
  }

  /** Internal accessor for tests so they can verify clock injection. */
  Instant nowForTests() {
    return collaborators.clock().instant();
  }

  /**
   * The bound {@link ToolDispatch}.
   *
   * @return the tool dispatch instance
   */
  public ToolDispatch toolDispatch() {
    return collaborators.toolDispatch();
  }
}
