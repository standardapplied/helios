/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.hooks.HookOutcome;
import java.util.List;

/**
 * Runs the tool calls of one model turn serially: fires {@code PreToolUse}, dispatches through
 * {@link ToolDispatch}, fires {@code PostToolUse}, and appends each result to the history.
 *
 * <p>Each result's output, whether a tool produced it or the round substituted it, is redacted once
 * against the session's secret registry before its {@link QueryEvent.ToolResult} is emitted, so the
 * event, {@code PostToolUse} hooks and the history all see the redacted text. A {@code
 * MutateResult} output is new text and is redacted the same way.
 *
 * <ul>
 *   <li><b>PreToolUse</b>: {@code Continue} dispatches; {@code MutateArgs} emits {@link
 *       QueryEvent.ToolMutated} and dispatches with the replacement args; {@code Block} emits
 *       {@link QueryEvent.ToolBlocked} and substitutes a {@link ToolResult#rejected rejected}
 *       result; {@code Inject} queues a synthetic user message and substitutes a rejected result;
 *       {@code Stop} terminates.
 *   <li><b>PostToolUse</b>: {@code MutateResult} rewrites the tool result's output and keeps its
 *       outcome, since a hook changes what the model reads, not what happened; {@code Inject}
 *       queues a synthetic user message; {@code Stop} appends the result and terminates.
 * </ul>
 */
final class ToolRound {

  private static final String PRE_TOOL_USE = "PreToolUseHook";
  private static final String POST_TOOL_USE = "PostToolUseHook";

  /** The call to run after {@code PreToolUse}, and the result standing in for its dispatch. */
  private record PlannedCall(ToolCall call, ToolResult substitute) {}

  private final LoopCollaborators collaborators;
  private final EventEmitter emitter;
  private final HookEffects effects;

  ToolRound(LoopCollaborators collaborators, EventEmitter emitter, HookEffects effects) {
    this.collaborators = collaborators;
    this.emitter = emitter;
    this.effects = effects;
  }

  /**
   * Run every call in order.
   *
   * @return {@code true} if a hook terminated the session mid-round
   */
  boolean run(SessionState state, List<ToolCall> toolCalls, SessionLimits limits) {
    for (var call : toolCalls) {
      if (runOne(state, call, limits)) {
        return true;
      }
    }
    return false;
  }

  private boolean runOne(SessionState state, ToolCall call, SessionLimits limits) {
    var decision = collaborators.hooks().firePreToolUse(call, collaborators.hookContext(state));
    var hookName = HookEffects.firingHookName(decision);
    if (decision.outcome() instanceof HookOutcome.Stop stop) {
      effects.stop(state, hookName, PRE_TOOL_USE, stop);
      return true;
    }
    var planned =
        switch (decision.outcome()) {
          case HookOutcome.MutateArgs mutate -> mutateArgs(state, call, hookName, mutate);
          case HookOutcome.Block block -> block(state, call, hookName, block);
          case HookOutcome.Inject inject -> {
            effects.inject(state, hookName, PRE_TOOL_USE, inject);
            yield new PlannedCall(
                call, ToolResult.rejected(firingHook(hookName), "hook injected a user message"));
          }
          default -> new PlannedCall(emitToolUse(state, call), null);
        };
    var result =
        planned.substitute() == null
            ? dispatch(state, planned.call(), limits)
            : planned.substitute();
    return afterTool(state, planned.call(), emitRedacted(state, planned.call(), result));
  }

  /** Redact {@code raw}'s output, emit its {@link QueryEvent.ToolResult}, return it redacted. */
  private ToolResult emitRedacted(SessionState state, ToolCall call, ToolResult raw) {
    var redaction = collaborators.secrets().redactor().redact(raw.output());
    var output = redaction.counts().isEmpty() ? raw.output() : redaction.text();
    var result = raw.withOutput(output);
    emitter.emit(
        state,
        new QueryEvent.ToolResult(
            state.sessionId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            call,
            result,
            redaction.counts()));
    return result;
  }

  private ToolCall emitToolUse(SessionState state, ToolCall call) {
    emitter.emit(
        state,
        new QueryEvent.ToolUse(
            state.sessionId(), state.currentTurnIndex(), collaborators.clock().instant(), call));
    return call;
  }

  private PlannedCall mutateArgs(
      SessionState state, ToolCall call, String hookName, HookOutcome.MutateArgs mutate) {
    emitter.emitHookFired(state, hookName, PRE_TOOL_USE, "MutateArgs");
    emitter.emit(
        state,
        new QueryEvent.ToolMutated(
            state.sessionId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            call,
            firingHook(hookName),
            call.arguments(),
            mutate.args()));
    return new PlannedCall(
        emitToolUse(state, new ToolCall(call.id(), call.name(), mutate.args())), null);
  }

  private PlannedCall block(
      SessionState state, ToolCall call, String hookName, HookOutcome.Block block) {
    emitter.emitHookFired(state, hookName, PRE_TOOL_USE, "Block");
    emitter.emit(
        state,
        new QueryEvent.ToolBlocked(
            state.sessionId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            call,
            firingHook(hookName),
            block.reason()));
    return new PlannedCall(call, ToolResult.rejected(firingHook(hookName), block.reason()));
  }

  private static String firingHook(String hookName) {
    return hookName == null ? PRE_TOOL_USE : hookName;
  }

  private ToolResult dispatch(SessionState state, ToolCall call, SessionLimits limits) {
    try {
      return collaborators
          .toolDispatch()
          .dispatch(call, state.cancellation(), limits.toolTimeoutDefault());
    } catch (Throwable t) {
      return ToolResult.threw(call.name(), t);
    }
  }

  private boolean afterTool(SessionState state, ToolCall call, ToolResult result) {
    var decision =
        collaborators.hooks().firePostToolUse(call, result, collaborators.hookContext(state));
    var hookName = HookEffects.firingHookName(decision);
    var kept = result;
    switch (decision.outcome()) {
      case HookOutcome.MutateResult mutate ->
          kept = mutateResult(state, call, result, hookName, mutate);
      case HookOutcome.Inject inject -> effects.inject(state, hookName, POST_TOOL_USE, inject);
      case HookOutcome.Stop stop -> {
        emitter.emitHookFired(state, hookName, POST_TOOL_USE, "Stop");
        append(state, call, result);
        state.setTerminal(HookEffects.success(state, stop.result()));
        return true;
      }
      default -> {}
    }
    append(state, call, kept);
    return false;
  }

  private ToolResult mutateResult(
      SessionState state,
      ToolCall call,
      ToolResult result,
      String hookName,
      HookOutcome.MutateResult mutate) {
    emitter.emitHookFired(state, hookName, POST_TOOL_USE, "MutateResult");
    return emitRedacted(state, call, result.withOutput(mutate.output()));
  }

  /**
   * Append a tool result to history, plus any multimodal attachments the tool returned. The text
   * goes on the standard tool-result message; attachments ride a follow-up user message so every
   * provider's existing {@code Message.user(text, inlineFiles)} plumbing handles wire encoding
   * uniformly — no per-provider {@code tool_result} multimodal wiring required.
   *
   * <p>The follow-up text names what was attached so the model has a textual handle in conversation
   * history even without re-reading the binary content on every subsequent turn. Empty-attachments
   * results emit only the tool-result message — the synthetic user message is skipped to keep the
   * conversation shape unchanged when no multimodal payload exists.
   */
  private static void append(SessionState state, ToolCall call, ToolResult result) {
    state.history().append(Message.tool(call.id(), call.name(), result.output()));
    if (result.hasAttachments()) {
      var text =
          "[tool '"
              + call.name()
              + "' returned "
              + result.attachments().size()
              + " attachment(s) for inspection]";
      state.history().append(Message.user(text, result.attachments()));
    }
  }
}
