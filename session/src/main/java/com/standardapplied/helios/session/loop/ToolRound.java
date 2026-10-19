/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.hooks.HookDecision;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.permissions.Permission;
import java.util.List;
import java.util.Optional;

/**
 * Runs the tool calls of one model turn serially: runs the {@code PreToolUse} chain, authorizes the
 * call it leaves through {@link ToolAuthorization}, dispatches that exact call through {@link
 * ToolDispatch}, fires {@code PostToolUse}, and appends each result to the history. No hook runs
 * between authorization and dispatch, so the call that was authorized is the call that runs.
 *
 * <p>Each result's output, whether a tool produced it or the round substituted it, is redacted once
 * against the session's secret registry before its {@link QueryEvent.ToolResult} is emitted, so the
 * event, {@code PostToolUse} hooks and the history all see the redacted text. A {@code
 * MutateResult} output is new text and is redacted the same way.
 *
 * <p>An {@link Error} a tool threw leaves the round as itself and ends the session; no later call
 * of the round runs.
 *
 * <ul>
 *   <li><b>PreToolUse</b>: each {@code MutateArgs} of the chain emits {@link
 *       QueryEvent.ToolMutated}; {@code Block} emits {@link QueryEvent.ToolBlocked} and substitutes
 *       a {@link ToolResult#rejected rejected} result; {@code Inject} queues a synthetic user
 *       message and substitutes a rejected result; {@code Stop} terminates; with none of the three,
 *       the final call goes to authorization.
 *   <li><b>Authorization</b>: a refusal emits {@link QueryEvent.ToolBlocked} and substitutes a
 *       rejected result by {@link Permission#NAME}; an admitted call emits {@link
 *       QueryEvent.ToolUse} and is dispatched. A call naming no registered tool skips the stage,
 *       and dispatch answers it {@code NotFound}. A refusal's reason and a hook's {@code Block}
 *       reason are redacted and bounded by {@link ToolAuthorization#diagnostic}.
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
    var chain = collaborators.hooks().firePreToolUse(call, collaborators.hookContext(state));
    emitMutations(state, call, chain.mutations());
    var finalCall = chain.call();
    var hookName = chain.decision().firingHookName();
    if (chain.decision().outcome() instanceof HookOutcome.Stop stop) {
      effects.stop(state, hookName, PRE_TOOL_USE, stop);
      return true;
    }
    var planned =
        switch (chain.decision().outcome()) {
          case HookOutcome.Block block -> {
            emitter.emitHookFired(state, hookName, PRE_TOOL_USE, "Block");
            yield block(state, finalCall, hookName, block.reason());
          }
          case HookOutcome.Inject inject -> {
            effects.inject(state, hookName, PRE_TOOL_USE, inject);
            yield new PlannedCall(
                finalCall, ToolResult.rejected(hookName, "hook injected a user message"));
          }
          default -> authorize(state, finalCall);
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

  /**
   * Announce each argument mutation of the {@code PreToolUse} chain, in order: a {@link
   * QueryEvent.HookFired} and a {@link QueryEvent.ToolMutated} from the call as the hooks before
   * left it.
   */
  private void emitMutations(SessionState state, ToolCall call, List<HookDecision> mutations) {
    var before = call;
    for (var mutation : mutations) {
      var args = ((HookOutcome.MutateArgs) mutation.outcome()).args();
      emitter.emitHookFired(state, mutation.firingHookName(), PRE_TOOL_USE, "MutateArgs");
      emitter.emit(
          state,
          new QueryEvent.ToolMutated(
              state.sessionId(),
              state.currentTurnIndex(),
              collaborators.clock().instant(),
              before,
              mutation.firingHookName(),
              before.arguments(),
              args));
      before = new ToolCall(before.id(), before.name(), args);
    }
  }

  /**
   * Run the mandatory permission stage on the final call: a refusal is blocked {@link
   * Permission#NAME by the permission policy}; an admitted call is announced by its {@link
   * QueryEvent.ToolUse}.
   */
  private PlannedCall authorize(SessionState state, ToolCall call) {
    var refusal = refusal(call);
    if (refusal.isPresent()) {
      return block(state, call, Permission.NAME, refusal.orElseThrow());
    }
    emitter.emit(
        state,
        new QueryEvent.ToolUse(
            state.sessionId(), state.currentTurnIndex(), collaborators.clock().instant(), call));
    return new PlannedCall(call, null);
  }

  /**
   * Why the permission stage refuses {@code call}; empty for a call naming no tool of the
   * dispatcher's registry, which dispatch answers {@code NotFound}, policy or none.
   */
  private Optional<String> refusal(ToolCall call) {
    return collaborators.toolDispatch().registry().get(call.name()).isEmpty()
        ? Optional.empty()
        : ToolAuthorization.refusal(collaborators.authorization(), call);
  }

  private PlannedCall block(SessionState state, ToolCall call, String by, String reason) {
    var diagnostic = ToolAuthorization.diagnostic(collaborators.secrets(), reason);
    emitter.emit(
        state,
        new QueryEvent.ToolBlocked(
            state.sessionId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            call,
            by,
            diagnostic));
    return new PlannedCall(call, ToolResult.rejected(by, diagnostic));
  }

  private ToolResult dispatch(SessionState state, ToolCall call, SessionLimits limits) {
    try {
      return collaborators
          .toolDispatch()
          .dispatch(
              call, state.cancellation(), limits.toolTimeoutDefault(), limits.toolStopGrace());
    } catch (RuntimeException e) {
      return ToolResult.threw(call.name(), e);
    }
  }

  private boolean afterTool(SessionState state, ToolCall call, ToolResult result) {
    var decision =
        collaborators.hooks().firePostToolUse(call, result, collaborators.hookContext(state));
    var hookName = decision.firingHookName();
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
