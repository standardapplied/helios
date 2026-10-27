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
 *   <li>Before the run's first turn, if no message reached the history (every one was blocked), end
 *       with {@link ResultMessage.ErrorDuringExecution} of kind {@code NoUserMessage}: a run calls
 *       the model only after a user message of its own.
 *   <li>Advance the turn counter and call {@link TurnRunner#runTurn(SessionState, SessionLimits)}.
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
 * order, so the next run's history is one a provider accepts; the run's token is cancelled, and its
 * callbacks and the tool calls still running are awaited until one deadline, {@link
 * SessionLimits#stopGrace()} away, each one still running then logged; then {@link
 * QueryEvent.RunEnded} is emitted.
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

  public AgentLoop(
      LoopCollaborators collaborators,
      TurnRunner turnRunner,
      StopClassifier classifier,
      TokenCounter tokenCounter,
      ContextCompactor contextCompactor) {
    this.collaborators = collaborators;
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
      terminal =
          decided(
              state,
              boundary,
              StopClassifier.errorDuringExecution(
                  state, SerializedError.of(e).redactedBy(collaborators.secrets().redactor())));
    }
    return end(state, limits, terminal);
  }

  /** One iteration of the loop: the terminal it ended with, or empty to iterate again. */
  private Optional<ResultMessage> iterate(
      SessionState state, SessionLimits limits, RunBoundary boundary) {
    if (state.isTerminal()) {
      return Optional.of(decided(state, boundary, state.terminal().orElseThrow()));
    }
    var received = steeringDrain.drainInto(state);
    if (state.isTerminal()) {
      return Optional.of(decided(state, boundary, state.terminal().orElseThrow()));
    }
    if (!received && state.currentTurnIndex() == 0) {
      var noUserMessage = SerializedError.of("NoUserMessage", NO_USER_MESSAGE);
      return Optional.of(
          decided(state, boundary, StopClassifier.errorDuringExecution(state, noUserMessage)));
    }
    state.beginTurn();
    compaction.check(state, limits);
    var outcome = turnRunner.runTurn(state, limits);
    if (state.isTerminal()) {
      return Optional.of(decided(state, boundary, state.terminal().orElseThrow()));
    }
    compaction.check(state, limits);
    return classify(state, limits, boundary, outcome);
  }

  /**
   * The classifier's and the pre-stop hooks' verdict on a turn, decided through {@code boundary}: a
   * {@code Success} that holds only while no message is queued gives way to a message sent
   * meanwhile, and the turn is classified again as one with a message queued. A cancellation that
   * arrived while the hooks ran, or before the boundary decided the end, overrides their verdict.
   */
  private Optional<ResultMessage> classify(
      SessionState state, SessionLimits limits, RunBoundary boundary, TurnOutcome outcome) {
    var hasQueuedMessages = collaborators.steeringQueue().size() > 0;
    var verdict = classifier.classify(state, limits, outcome, hasQueuedMessages);
    if (verdict.isEmpty()) {
      return Optional.empty();
    }
    var hookVerdict = preStop.resolve(state, verdict.get(), outcome);
    var resolved =
        unlessCancelled(
            state,
            limits,
            outcome,
            hookVerdict.or(() -> classifier.classify(state, limits, outcome, true)));
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    var natural = resolved.get() == verdict.get() && verdict.get() instanceof ResultMessage.Success;
    if (natural && !boundary.end(true)) {
      return classifier
          .classify(state, limits, outcome, true)
          .map(limit -> decided(state, boundary, limit));
    }
    if (!natural) {
      boundary.end(false);
    }
    return Optional.of(
        terminal(state, unlessCancelled(state, limits, outcome, resolved).orElseThrow()));
  }

  /** {@code verdict}, or the classifier's verdict on the turn once the run was cancelled. */
  private Optional<ResultMessage> unlessCancelled(
      SessionState state,
      SessionLimits limits,
      TurnOutcome outcome,
      Optional<ResultMessage> verdict) {
    return state.cancellation().isCancelled()
        ? classifier.classify(state, limits, outcome, true)
        : verdict;
  }

  /** Decide through {@code boundary} that the run ends with {@code result}, a non-natural stop. */
  private static ResultMessage decided(
      SessionState state, RunBoundary boundary, ResultMessage result) {
    boundary.end(false);
    return terminal(state, result);
  }

  private static ResultMessage terminal(SessionState state, ResultMessage result) {
    state.setTerminal(result);
    return state.terminal().orElseThrow();
  }

  /**
   * End the decided run: close an open tool round, cancel the run's token, wait up to {@code
   * stopGrace} for its tool calls still running, then emit {@link QueryEvent.RunEnded}.
   */
  private ResultMessage end(SessionState state, SessionLimits limits, ResultMessage terminal) {
    // An interrupt host code left on this thread is no request to cut the run's waits short.
    Thread.interrupted();
    state.history().closeOpenRound(NOT_RUN);
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
