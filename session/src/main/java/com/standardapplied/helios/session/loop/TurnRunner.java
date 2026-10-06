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
import com.standardapplied.helios.session.StopReason;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolVisibilityContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

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
 * creates its own subscriber state.
 */
public final class TurnRunner {

  private final LoopCollaborators collaborators;
  private final Model model;
  private final EventEmitter emitter;
  private final TurnHooks turnHooks;
  private final ModelCall modelCall;
  private final ToolRound toolRound;
  private final UsageAccounting accounting;

  /**
   * Build a turn runner.
   *
   * @param collaborators the collaborators shared with the {@link AgentLoop}; non-null
   * @param model the model providing {@link Model#chatStream(List, List,
   *     com.standardapplied.helios.core.runtime.CancellationToken)}; non-null
   * @param costCalculator converts per-turn {@link Usage} into a {@link
   *     com.standardapplied.helios.core.common.CostEstimate}; non-null. Use {@link
   *     CostCalculator#ZERO} to disable cost tracking
   * @param outputSchema the schema the model's text output must conform to, or {@code null} when
   *     the session has no structured-output constraint. When non-null, the loop dispatches {@link
   *     Model#chatStream(List, List, OutputSchema,
   *     com.standardapplied.helios.core.runtime.CancellationToken)} on every turn so the schema
   *     rides the provider's native channel (Gemini {@code response_format.schema}, OpenAI {@code
   *     text.format=json_schema}, Anthropic {@code system_instruction} text). The schema is dormant
   *     on tool-calling turns (tool arguments validate against the tool's own schema) and activates
   *     on text-output turns. When {@code null}, the loop dispatches {@link Model#chatStream(List,
   *     List, com.standardapplied.helios.core.runtime.CancellationToken)} and the model is free to
   *     produce arbitrary text
   * @param scheduler shared per-session scheduler used by {@link TurnSubscriber} to enforce the
   *     per-chunk {@code streamIdleTimeout}; non-null. The caller owns its lifetime — {@link
   *     com.standardapplied.helios.session.AgentSessionImpl} passes a session-scoped scheduler it
   *     shuts down with the session
   * @throws NullPointerException if any non-{@code outputSchema} argument is null
   */
  public TurnRunner(
      LoopCollaborators collaborators,
      Model model,
      CostCalculator costCalculator,
      OutputSchema<?> outputSchema,
      ScheduledExecutorService scheduler) {
    this.collaborators = Objects.requireNonNull(collaborators, "collaborators must not be null");
    this.model = Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(costCalculator, "costCalculator must not be null");
    Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.emitter = collaborators.emitter();
    var effects = new HookEffects(emitter, collaborators.steeringQueue());
    this.turnHooks = new TurnHooks(collaborators, emitter, effects);
    this.modelCall = new ModelCall(collaborators, emitter, model, outputSchema, scheduler);
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

    var attempts = modelCall.stream(state, limits, visibleTools(state));
    var streamed = attempts.turn();
    if (modelCall.selfCorrectSchema(state, streamed)) {
      return turnEnded(state, new TurnOutcome(FinishReason.TOOL_CALLS, "", state.totals().usage()));
    }

    var streamOutcome = streamed.toOutcome(attempts.count());
    var toolCalls =
        isRefused(streamOutcome.finishReason()) ? List.<ToolCall>of() : streamed.toolCalls();

    if (!toolCalls.isEmpty()) {
      state
          .history()
          .append(
              Message.assistant(
                  streamOutcome.assistantContent(), toolCalls, streamOutcome.metadata()));
      if (toolRound.run(state, toolCalls, limits)) {
        return turnEnded(state, outcomeAfterTerminate(state));
      }
    } else if (streamOutcome.finishReason() != FinishReason.ERROR
        && !streamOutcome.assistantContent().isEmpty()) {
      state
          .history()
          .append(
              Message.assistant(
                  streamOutcome.assistantContent(), List.of(), streamOutcome.metadata()));
    }
    accounting.add(state, streamOutcome.usage());
    state.totals().accumulateCitations(streamed.citations());

    var response =
        Response.newBuilder()
            .withContent(streamOutcome.assistantContent())
            .withFinishReason(streamOutcome.finishReason())
            .withUsage(streamOutcome.usage())
            .withToolCalls(toolCalls)
            .withCitations(streamed.citations())
            .build();
    if (turnHooks.afterModelTurn(state, response) == TurnHooks.Decision.TERMINATE) {
      return turnEnded(state, outcomeAfterTerminate(state));
    }

    var finalOutcome =
        toolCalls.isEmpty()
            ? streamOutcome
            : new TurnOutcome(
                FinishReason.TOOL_CALLS, streamOutcome.assistantContent(), streamOutcome.usage());
    return turnEnded(state, finalOutcome);
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
   * Whether the provider declined the turn. Everything a refused turn produced before the refusal
   * is incomplete output to discard, so tool calls it had already emitted never run.
   */
  private static boolean isRefused(FinishReason finishReason) {
    return finishReason == FinishReason.REFUSAL || finishReason == FinishReason.CONTENT_FILTER;
  }

  private static TurnOutcome outcomeAfterTerminate(SessionState state) {
    return new TurnOutcome(FinishReason.STOP, "", state.totals().usage());
  }

  private TurnOutcome turnEnded(SessionState state, TurnOutcome outcome) {
    emitter.emit(
        state,
        new QueryEvent.TurnEnded(
            state.sessionId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            stopReasonOf(outcome.finishReason())));
    return outcome;
  }

  private static StopReason stopReasonOf(FinishReason r) {
    return switch (r) {
      case STOP -> StopReason.END_TURN;
      case TOOL_CALLS -> StopReason.TOOL_USE;
      case LENGTH -> StopReason.MAX_TOKENS;
      case CONTENT_FILTER, REFUSAL -> StopReason.REFUSAL;
      case ERROR -> StopReason.ERROR;
    };
  }
}
