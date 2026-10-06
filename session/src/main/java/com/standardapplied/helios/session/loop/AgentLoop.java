/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Top-level orchestrator that drives one open-ended session to a terminal {@link ResultMessage}.
 *
 * <p>Every iteration:
 *
 * <ol>
 *   <li>Drain the {@link SteeringQueue}. Each pending {@link UserMessage} fires {@link
 *       HookRegistry#fireOnUserMessage on-user-message hooks} — outcomes can drop the message
 *       ({@link HookOutcome.Block Block}), rewrite its text ({@link HookOutcome.MutateText
 *       MutateText}), or terminate the session ({@link HookOutcome.Stop Stop}). Surviving messages
 *       emit {@link QueryEvent.UserMessageReceived} and compose into a single user-role message
 *       appended to history.
 *   <li>If history is still empty (no message has ever been observed and the queue was empty),
 *       terminate with {@link ResultMessage.ErrorDuringExecution}.
 *   <li>Advance the turn counter and call {@link TurnRunner#runTurn(SessionState, SessionLimits)}.
 *   <li>If the turn left a terminal value on {@link SessionState} (hook-driven stop), respect it.
 *       Otherwise hand the outcome to {@link StopClassifier}; if it returns terminal, fire {@link
 *       HookRegistry#firePreStop pre-stop hooks} — {@link HookOutcome.Inject Inject} cancels the
 *       termination and queues a synthetic user message; {@link HookOutcome.Stop Stop} overrides
 *       the success text; {@link HookOutcome.Continue Continue} confirms the stop.
 *   <li>Otherwise iterate.
 * </ol>
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
 * One AgentLoop instance per session. {@link #run(SessionState, SessionLimits)} runs on a single
 * virtual thread. Shared collaborators ({@link TurnRunner}, {@link StopClassifier}, {@link
 * HookRegistry}) are reusable across sessions; per-session collaborators ({@link SessionState},
 * {@link SteeringQueue}, {@link ToolDispatch}) are owned by the caller.
 */
public final class AgentLoop {

  private final LoopCollaborators collaborators;
  private final TurnRunner turnRunner;
  private final StopClassifier classifier;
  private final EventEmitter emitter;
  private final SteeringDrain steeringDrain;
  private final ContextCompaction compaction;
  private final PreStopDecision preStop;

  /**
   * Build an agent loop.
   *
   * @param collaborators the collaborators shared with the {@link TurnRunner}; non-null
   * @param turnRunner the per-turn worker; non-null
   * @param classifier terminal-result classifier; non-null
   * @param tokenCounter estimator used before and after each model turn to detect when running
   *     history approaches the context window; non-null. {@link TokenCounter#charBased()} is the
   *     default
   * @param contextCompactor invoked when usage crosses the compaction watermark; non-null. Pass
   *     {@link ContextCompactor#disabled()} to opt out of automatic compaction
   * @throws NullPointerException if any argument is null
   */
  public AgentLoop(
      LoopCollaborators collaborators,
      TurnRunner turnRunner,
      StopClassifier classifier,
      TokenCounter tokenCounter,
      ContextCompactor contextCompactor) {
    this.collaborators = Objects.requireNonNull(collaborators, "collaborators must not be null");
    this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner must not be null");
    this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
    Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
    Objects.requireNonNull(contextCompactor, "contextCompactor must not be null");
    this.emitter = collaborators.emitter();
    this.steeringDrain =
        new SteeringDrain(
            collaborators, emitter, new HookEffects(emitter, collaborators.steeringQueue()));
    this.compaction =
        new ContextCompaction(collaborators, emitter, turnRunner, tokenCounter, contextCompactor);
    this.preStop = new PreStopDecision(collaborators, emitter);
  }

  /**
   * Run the loop to terminal.
   *
   * @param state per-session mutable state; non-null
   * @param limits per-session limits; non-null
   * @return the terminal {@link ResultMessage}; never null
   * @throws NullPointerException if {@code state} or {@code limits} is null
   */
  public ResultMessage run(SessionState state, SessionLimits limits) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    try {
      Optional<ResultMessage> terminal = Optional.empty();
      while (terminal.isEmpty()) {
        terminal = iterate(state, limits);
      }
      return terminal.orElseThrow();
    } catch (Exception e) {
      // Restricted to Exception — Error subtypes (OOM, StackOverflow, LinkageError) leave the JVM
      // in an inconsistent state, so we let them escape and the host process die cleanly.
      return crashTerminate(state, e);
    }
  }

  private static ResultMessage crashTerminate(SessionState state, Exception e) {
    var failure =
        new ResultMessage.ErrorDuringExecution(
            state.sessionId(),
            SerializedError.of(e),
            state.totals().usage(),
            state.totals().cost(),
            state.elapsed());
    state.setTerminal(failure);
    return failure;
  }

  /** One iteration of the loop: the terminal it ended with, or empty to iterate again. */
  private Optional<ResultMessage> iterate(SessionState state, SessionLimits limits) {
    if (state.isTerminal()) {
      return Optional.of(terminateWithExistingTerminal(state));
    }
    steeringDrain.drainInto(state);
    if (state.isTerminal()) {
      return Optional.of(terminateWithExistingTerminal(state));
    }
    if (state.history().snapshot().isEmpty()) {
      return Optional.of(terminate(state, emptyHistoryError(state)));
    }
    state.beginTurn();
    compaction.check(state, limits);
    if (state.isTerminal()) {
      return Optional.of(terminateWithExistingTerminal(state));
    }
    var outcome = turnRunner.runTurn(state, limits);
    if (state.isTerminal()) {
      return Optional.of(terminateWithExistingTerminal(state));
    }
    compaction.check(state, limits);
    if (state.isTerminal()) {
      return Optional.of(terminateWithExistingTerminal(state));
    }
    var hasQueuedMessages = collaborators.steeringQueue().size() > 0;
    return classifier
        .classify(state, limits, outcome, hasQueuedMessages)
        .flatMap(verdict -> preStop.resolve(state, verdict, outcome))
        .map(resolved -> terminate(state, resolved));
  }

  private ResultMessage terminate(SessionState state, ResultMessage result) {
    state.setTerminal(result);
    emitter.emit(
        state,
        new QueryEvent.LoopEnded(
            state.sessionId(), state.currentTurnIndex(), collaborators.clock().instant(), result));
    return result;
  }

  private ResultMessage terminateWithExistingTerminal(SessionState state) {
    var existing = state.terminal().orElseThrow();
    emitter.emit(
        state,
        new QueryEvent.LoopEnded(
            state.sessionId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            existing));
    return existing;
  }

  private static ResultMessage emptyHistoryError(SessionState state) {
    return new ResultMessage.ErrorDuringExecution(
        state.sessionId(),
        SerializedError.of(
            "EmptyHistory",
            "AgentLoop.run requires at least one user message in the steering queue before "
                + "starting"),
        state.totals().usage(),
        state.totals().cost(),
        state.elapsed());
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
