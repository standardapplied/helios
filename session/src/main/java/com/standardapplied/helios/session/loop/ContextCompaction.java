/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.session.CompactionResult;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.hooks.CompactionPayload;
import com.standardapplied.helios.session.hooks.HookOutcome;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The context watermark and compaction step the {@link AgentLoop} runs before and after every turn:
 * counts the tokens in the history, fires {@link QueryEvent.ContextWarning} on the first crossing
 * of {@link #CONTEXT_WARNING_WATERMARK}, and on crossings of {@link #CONTEXT_COMPACT_WATERMARK}
 * runs the {@link ContextCompactor} between the {@link
 * com.standardapplied.helios.session.hooks.PreCompactHook} and {@link
 * com.standardapplied.helios.session.hooks.PostCompactHook}.
 */
final class ContextCompaction {

  private static final Logger LOGGER = Logger.getLogger(AgentLoop.class.getName());

  /**
   * Watermark fraction of {@link SessionLimits#maxContextTokens()} at which a {@link
   * QueryEvent.ContextWarning} fires for the first time. Sticky for the rest of the session until a
   * successful compaction clears the flag. 0.85 gives library users (and the compactor) ~15% of the
   * window to react before the model errors out.
   *
   * <p>The denominator is the configured {@code maxContextTokens} (or its model-aware resolution
   * when the caller left it at the auto sentinel). The numerator is the {@link TokenCounter}'s
   * estimate of <em>total tokens across every message currently in the conversation history</em> —
   * cumulative across all turns since the session started, never the size of a single turn. The
   * watermark check runs both before and after every turn so the trigger fires as soon as the
   * cumulative history crosses the threshold.
   */
  static final double CONTEXT_WARNING_WATERMARK = 0.85;

  /**
   * Watermark fraction of {@link SessionLimits#maxContextTokens()} at which the loop invokes the
   * configured {@link ContextCompactor}. 0.95 keeps a 5% safety margin against the underlying
   * context window; below the warning watermark we'd compact too eagerly.
   *
   * <p>Same denominator and numerator as {@link #CONTEXT_WARNING_WATERMARK} — cumulative across the
   * full conversation history, not per-turn.
   */
  static final double CONTEXT_COMPACT_WATERMARK = 0.95;

  /**
   * Output-token headroom subtracted from {@link
   * com.standardapplied.helios.core.model.Model#contextWindow()} when {@link
   * SessionLimits#maxContextTokens()} is left in its sentinel "auto" state. Mirrors Claude Code's
   * {@code nAK = 20000} output reservation — leaves enough budget for the next response after a
   * full-context input, so the watermark check trips before the provider rejects an oversized
   * request. Library users that need a tighter or looser headroom set an explicit {@link
   * SessionLimits.Builder#withMaxContextTokens(long)} cap.
   */
  static final long AUTO_RESERVED_OUTPUT_TOKENS = 20_000L;

  /**
   * Backstop window used when both {@link SessionLimits#maxContextTokens()} is in its "auto"
   * sentinel AND the provider does not report a {@link
   * com.standardapplied.helios.core.model.Model#contextWindow()}. Matches the pre-2.5.6 hardcoded
   * default. Sized for Sonnet-class models — narrow enough not to fail a conservatively-built
   * session, wide enough that simple multi-turn workflows don't compact prematurely.
   */
  static final long AUTO_FALLBACK_MAX_CONTEXT_TOKENS = 180_000L;

  private final LoopCollaborators collaborators;
  private final EventEmitter emitter;
  private final HookEffects effects;
  private final TurnRunner turnRunner;
  private final TokenCounter tokenCounter;
  private final ContextCompactor contextCompactor;

  ContextCompaction(
      LoopCollaborators collaborators,
      EventEmitter emitter,
      TurnRunner turnRunner,
      TokenCounter tokenCounter,
      ContextCompactor contextCompactor) {
    this.collaborators = collaborators;
    this.emitter = emitter;
    this.effects = new HookEffects(emitter, collaborators.steeringQueue());
    this.turnRunner = turnRunner;
    this.tokenCounter = tokenCounter;
    this.contextCompactor = contextCompactor;
  }

  /**
   * Count tokens in the current history, fire {@link QueryEvent.ContextWarning} on the first
   * crossing of {@link #CONTEXT_WARNING_WATERMARK}, and invoke the {@link ContextCompactor} on
   * crossings of {@link #CONTEXT_COMPACT_WATERMARK}. Successful compaction clears the warning flag
   * so a future re-climb fires the watermark again.
   */
  void check(SessionState state, SessionLimits limits) {
    var maxTokens = effectiveMaxContextTokens(limits);
    var tokens = tokenCounter.count(state.history().snapshot());
    var usagePct = (double) tokens / (double) maxTokens;
    if (usagePct >= CONTEXT_WARNING_WATERMARK && state.contextWatermark().tryFire()) {
      emitter.emit(
          state,
          new QueryEvent.ContextWarning(
              state.sessionId(),
              state.currentTurnIndex(),
              collaborators.clock().instant(),
              usagePct));
    }
    if (usagePct >= CONTEXT_COMPACT_WATERMARK) {
      compact(state, tokens);
    }
  }

  /**
   * Resolve the effective context-token ceiling for this session by reconciling the user-supplied
   * {@link SessionLimits#maxContextTokens()} cap with the model's documented {@link
   * com.standardapplied.helios.core.model.Model#contextWindow()}. Mirrors Claude Code's {@code
   * wc()} resolver:
   *
   * <ul>
   *   <li>Both known → take the smaller (user cap never permits more than the model actually
   *       supports).
   *   <li>User cap only → honour it as-is. Trusting the deployer is the contract.
   *   <li>Model window only → use {@code window - AUTO_RESERVED_OUTPUT_TOKENS}, leaving headroom
   *       for the next response.
   *   <li>Neither known → fall back to {@link #AUTO_FALLBACK_MAX_CONTEXT_TOKENS}. Last-resort path
   *       for providers that don't yet implement {@code contextWindow()}.
   * </ul>
   *
   * <p>Sentinel: {@code limits.maxContextTokens() == 0} signals "auto, resolve from model". This is
   * the default — library users that want a hard cap call {@link
   * SessionLimits.Builder#withMaxContextTokens(long)}.
   */
  long effectiveMaxContextTokens(SessionLimits limits) {
    var userCap = limits.maxContextTokens();
    var modelWindow = (long) turnRunner.modelContextWindow();
    var modelEffective =
        modelWindow > AUTO_RESERVED_OUTPUT_TOKENS ? modelWindow - AUTO_RESERVED_OUTPUT_TOKENS : 0L;
    if (userCap > 0L && modelEffective > 0L) {
      return Math.min(userCap, modelEffective);
    }
    if (userCap > 0L) {
      return userCap;
    }
    if (modelEffective > 0L) {
      return modelEffective;
    }
    return AUTO_FALLBACK_MAX_CONTEXT_TOKENS;
  }

  /**
   * Invoke the configured {@link ContextCompactor}, swap in the returned history, accumulate any
   * usage reported by the compactor (e.g. summary call spend) — priced against the compactor's own
   * {@link CompactionResult#modelId()} so a cheap summary model isn't billed at the main loop's
   * rate — fire the pre-compact hook before the compactor runs and the post-compact hook after a
   * successful shrink, and emit {@link QueryEvent.ContextEdited} last. A returned identity (same
   * instance) or no-shrink result is treated as a no-op — the compactor opted out for this turn and
   * the warning flag stays set. A throwing compactor is swallowed; the loop continues.
   */
  private void compact(SessionState state, long tokensBefore) {
    var historyBefore = beforeCompact(state, state.history().snapshot());
    CompactionResult result;
    try {
      result = contextCompactor.compact(historyBefore, state);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "context compactor threw; leaving history unchanged this turn", e);
      return;
    }
    if (result == null) {
      return;
    }
    var compactionUsage = result.usage();
    if (compactionUsage.inputTokens() > 0 || compactionUsage.outputTokens() > 0) {
      turnRunner.accounting().add(state, result.modelId(), compactionUsage);
    }
    var historyAfter = result.history();
    if (historyAfter == historyBefore || historyAfter.size() >= historyBefore.size()) {
      return;
    }
    state.history().replace(historyAfter);
    var tokensAfter = tokenCounter.count(state.history().snapshot());
    var removedBlocks = historyBefore.size() - historyAfter.size();
    state.contextWatermark().reset();
    var payload =
        new CompactionPayload(
            historyBefore, historyAfter, tokensBefore, tokensAfter, removedBlocks);
    if (afterCompactStops(state, payload)) {
      return;
    }
    emitter.emit(
        state,
        new QueryEvent.ContextEdited(
            state.sessionId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            removedBlocks,
            tokensBefore,
            tokensAfter));
  }

  /**
   * Fire the pre-compact hook and return the history the compactor should operate on. {@link
   * HookOutcome.MutateHistory} swaps in the rewritten history; other non-Continue outcomes are
   * logged and treated as Continue.
   */
  private List<Message> beforeCompact(SessionState state, List<Message> historyBefore) {
    var decision =
        collaborators.hooks().firePreCompact(historyBefore, collaborators.hookContext(state));
    var hookName = HookEffects.firingHookName(decision);
    return switch (decision.outcome()) {
      case HookOutcome.Continue ignored -> historyBefore;
      case HookOutcome.MutateHistory mutate -> {
        emitter.emitHookFired(state, hookName, "PreCompactHook", "MutateHistory");
        yield mutate.history();
      }
      default -> {
        LOGGER.log(
            Level.WARNING,
            "PreCompactHook ''{0}'' {1} is not honored at this phase; using original history",
            new Object[] {hookName, decision.outcome().getClass().getSimpleName()});
        yield historyBefore;
      }
    };
  }

  /**
   * Fire the post-compact hook. Returns {@code true} when the hook elected {@link HookOutcome.Stop}
   * so the caller skips the {@link QueryEvent.ContextEdited} emission and lets the loop terminate.
   * Other outcomes (including the unsupported Mutate / Block / Inject) are logged and treated as
   * Continue.
   */
  private boolean afterCompactStops(SessionState state, CompactionPayload payload) {
    var decision = collaborators.hooks().firePostCompact(payload, collaborators.hookContext(state));
    var hookName = HookEffects.firingHookName(decision);
    return switch (decision.outcome()) {
      case HookOutcome.Continue ignored -> false;
      case HookOutcome.Stop stop -> {
        effects.stop(state, hookName, "PostCompactHook", stop);
        yield true;
      }
      default -> {
        LOGGER.log(
            Level.WARNING,
            "PostCompactHook ''{0}'' {1} is not honored at this phase",
            new Object[] {hookName, decision.outcome().getClass().getSimpleName()});
        yield false;
      }
    };
  }
}
