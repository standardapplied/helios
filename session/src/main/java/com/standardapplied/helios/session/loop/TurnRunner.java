/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolVisibilityContext;
import java.util.List;
import java.util.Objects;

/**
 * Drives one model turn end-to-end, in order: the {@code PreModelTurn} hook, the model call, the
 * tool round, usage and cost accounting, and the {@code PostModelTurn} hook. Each stage is its own
 * type — {@link TurnHooks}, {@link ModelCall}, {@link ToolRound}, {@link UsageAccounting} — and
 * this class only sequences them, appends the assistant message to {@link SessionState} history,
 * emits {@link QueryEvent.TurnEnded} and returns a {@link TurnOutcome} the agent loop hands to
 * {@link StopClassifier}.
 *
 * <h2>Hook outcomes</h2>
 *
 * <ul>
 *   <li><b>PreModelTurn</b>: {@code Continue} proceeds; {@code Inject} queues a synthetic user
 *       message and skips the model call (turn ends with finish reason {@code TOOL_CALLS} so the
 *       loop continues); {@code Stop} terminates the session with the given result.
 *   <li><b>PostModelTurn</b>: {@code Continue} proceeds; {@code Inject} queues a synthetic user
 *       message (loop continues); {@code Stop} terminates.
 *   <li><b>PreToolUse</b> and <b>PostToolUse</b>: see {@link ToolRound}.
 * </ul>
 *
 * <p>{@link QueryEvent.HookFired} fires for every non-{@code Continue} outcome. Every emission
 * fires the OnStreamEvent hooks.
 *
 * <h2>Thread-safety</h2>
 *
 * It has no mutable state of its own. Each {@link #runTurn(SessionState, SessionLimits)} invocation
 * reads the model's stream through a {@link TurnReader} of its own, on a thread of its own.
 */
public final class TurnRunner {

  private final LoopCollaborators collaborators;
  private final Model model;
  private final EventEmitter emitter;
  private final TurnHooks turnHooks;
  private final ModelCall modelCall;
  private final ToolRound toolRound;
  private final UsageAccounting accounting;

  /** {@code outputSchema} is null for a session without a structured-output constraint. */
  public TurnRunner(
      LoopCollaborators collaborators,
      Model model,
      CostCalculator costCalculator,
      OutputSchema<?> outputSchema) {
    this.collaborators = collaborators;
    this.model = model;
    this.emitter = collaborators.emitter();
    var effects = new HookEffects(emitter, collaborators.steeringQueue());
    this.turnHooks = new TurnHooks(collaborators, emitter, effects);
    this.modelCall = new ModelCall(collaborators, emitter, model, outputSchema);
    this.toolRound = new ToolRound(collaborators, emitter, effects);
    this.accounting = new UsageAccounting(model, costCalculator);
  }

  /**
   * Run one turn against the model and return its outcome.
   *
   * @param state the session state; non-null
   * @param limits the session limits; non-null
   * @return the outcome of the turn
   * @throws NullPointerException if {@code state} or {@code limits} is null
   */
  public TurnOutcome runTurn(SessionState state, SessionLimits limits) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");

    var preDecision = turnHooks.beforeModelTurn(state);
    if (preDecision == TurnHooks.Decision.TERMINATE) {
      return turnEnded(state, outcomeAfterTerminate(state));
    }
    if (preDecision == TurnHooks.Decision.SKIP_MODEL) {
      return turnEnded(state, new TurnOutcome(FinishReason.TOOL_CALLS, "", Usage.of(0, 0)));
    }

    var streamed = modelCall.stream(state, limits, visibleTools(state));
    accounting.add(state, streamed.usage());
    if (modelCall.selfCorrectSchema(state, streamed)) {
      return turnEnded(state, new TurnOutcome(FinishReason.TOOL_CALLS, "", state.totals().usage()));
    }

    var toolCalls =
        endedCleanly(streamed.finishReason()) ? streamed.toolCalls() : List.<ToolCall>of();

    if (streamed.finishReason() != FinishReason.ERROR
        && (!toolCalls.isEmpty() || !streamed.assistantContent().isEmpty())) {
      state
          .history()
          .append(Message.assistant(streamed.assistantContent(), toolCalls, streamed.metadata()));
    }
    if (!toolCalls.isEmpty() && toolRound.run(state, toolCalls, limits)) {
      return turnEnded(state, outcomeAfterTerminate(state));
    }
    state.totals().accumulateCitations(streamed.citations());

    var response =
        Response.newBuilder()
            .withContent(streamed.assistantContent())
            .withFinishReason(streamed.finishReason())
            .withUsage(streamed.usage())
            .withToolCalls(toolCalls)
            .withCitations(streamed.citations())
            .build();
    if (turnHooks.afterModelTurn(state, response) == TurnHooks.Decision.TERMINATE) {
      return turnEnded(state, outcomeAfterTerminate(state));
    }

    return turnEnded(
        state, toolCalls.isEmpty() ? streamed : streamed.withFinishReason(FinishReason.TOOL_CALLS));
  }

  /**
   * The usage and cost accounting, exposed to the {@link AgentLoop} so {@code
   * ContextCompactor}-reported summary-call spend is priced at the compactor's own model rate.
   */
  UsageAccounting accounting() {
    return accounting;
  }

  /**
   * Returns the session main model's documented context-window size in tokens. Exposed to the
   * {@link AgentLoop} so the watermark check can resolve an effective {@code maxContextTokens}
   * limit from the model when the caller didn't set an explicit cap. Returns {@code 0} when the
   * provider does not report a window — the loop falls back to {@link
   * SessionLimits#maxContextTokens()} in that case.
   */
  int modelContextWindow() {
    return model.contextWindow();
  }

  private List<Tool> visibleTools(SessionState state) {
    var visibilityCtx = new ToolVisibilityContext(state.sessionId(), state.currentTurnIndex());
    return collaborators.toolDispatch().registry().visible(visibilityCtx).stream()
        .map(ToolBinding::tool)
        .toList();
  }

  /**
   * Whether the turn ended cleanly enough to run the tool calls it emitted. A turn that failed, was
   * cancelled, lost its terminal event, was cut short or was refused leaves calls that may be
   * incomplete, so they are discarded and the turn keeps its own outcome.
   */
  private static boolean endedCleanly(FinishReason finishReason) {
    return finishReason == FinishReason.TOOL_CALLS || finishReason == FinishReason.STOP;
  }

  private static TurnOutcome outcomeAfterTerminate(SessionState state) {
    return new TurnOutcome(FinishReason.STOP, "", state.totals().usage());
  }

  private TurnOutcome turnEnded(SessionState state, TurnOutcome outcome) {
    emitter.emit(
        state,
        new QueryEvent.TurnEnded(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            outcome.finishReason()));
    return outcome;
  }
}
