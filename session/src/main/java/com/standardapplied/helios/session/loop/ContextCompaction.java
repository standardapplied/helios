/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.session.CompactionResult;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.SessionLimits;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The context watermark and compaction step the {@link AgentLoop} runs before and after every turn:
 * counts the tokens of the next request ({@link ConversationHistory#tokens(TokenCounter)}), fires
 * {@link QueryEvent.ContextWarning} on the first crossing of {@link #CONTEXT_WARNING_WATERMARK},
 * and on crossings of {@link #CONTEXT_COMPACT_WATERMARK} runs the {@link ContextCompactor}.
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
   * when the caller left it at the auto sentinel). The numerator is the input size the provider
   * reported for the last request, system prompt and tool definitions included, plus the {@link
   * TokenCounter}'s estimate of the messages appended since; with no such report, the estimate of
   * <em>every message currently in the conversation history</em>. Either way it is cumulative
   * across all turns since the session started, never the size of a single turn. The watermark
   * check runs both before and after every turn so the trigger fires as soon as the cumulative
   * history crosses the threshold.
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
   * Most output-token headroom subtracted from a known {@link
   * com.standardapplied.helios.core.model.Model#contextWindow()}; a window under 80,000 tokens
   * gives up a quarter instead. Mirrors Claude Code's {@code nAK = 20000} output reservation —
   * leaves enough budget for the next response after a full-context input, so the watermark check
   * trips before the provider rejects an oversized request. Library users that need a tighter
   * headroom set an explicit {@link SessionLimits.Builder#withMaxContextTokens(long)} cap.
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
    this.turnRunner = turnRunner;
    this.tokenCounter = tokenCounter;
    this.contextCompactor = contextCompactor;
  }

  /**
   * Count the tokens of the next request, fire {@link QueryEvent.ContextWarning} on the first
   * crossing of {@link #CONTEXT_WARNING_WATERMARK}, and invoke the {@link ContextCompactor} on
   * crossings of {@link #CONTEXT_COMPACT_WATERMARK}. Successful compaction replaces the history,
   * which re-arms the warning so a future re-climb fires it again.
   */
  void check(SessionState state, SessionLimits limits) {
    var maxTokens = effectiveMaxContextTokens(limits);
    var tokens = state.history().tokens(tokenCounter);
    var usagePct = (double) tokens / (double) maxTokens;
    if (usagePct >= CONTEXT_WARNING_WATERMARK && state.history().tryFireContextWarning()) {
      emitter.emit(
          state,
          new QueryEvent.ContextWarning(
              state.sessionId(),
              state.runId(),
              state.currentTurnIndex(),
              collaborators.clock().instant(),
              usagePct));
    }
    if (usagePct >= CONTEXT_COMPACT_WATERMARK) {
      compact(state);
    }
  }

  /**
   * Resolve the effective context-token ceiling for this session by reconciling the user-supplied
   * {@link SessionLimits#maxContextTokens()} cap with the model's documented {@link
   * com.standardapplied.helios.core.model.Model#contextWindow()}. Mirrors Claude Code's {@code
   * wc()} resolver:
   *
   * <ul>
   *   <li>Both known → take the smaller of the cap and the window's limit, so a user cap never
   *       permits more than the model actually supports.
   *   <li>User cap only → honour it as-is. Trusting the deployer is the contract.
   *   <li>Model window only → use the window's limit: {@code window - min(
   *       AUTO_RESERVED_OUTPUT_TOKENS, window / 4)}, leaving headroom for the next response. The
   *       limit of any known window is positive and below it, however small the window.
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
    if (modelWindow <= 0L) {
      return userCap > 0L ? userCap : AUTO_FALLBACK_MAX_CONTEXT_TOKENS;
    }
    var modelLimit = modelWindow - Math.min(AUTO_RESERVED_OUTPUT_TOKENS, modelWindow / 4L);
    return userCap > 0L ? Math.min(userCap, modelLimit) : modelLimit;
  }

  /**
   * Invoke the configured {@link ContextCompactor}, swap in the returned history, account a result
   * that names a model (e.g. a summary call, even one reporting zero usage) as one model call timed
   * across the compactor's call — priced against the compactor's own {@link
   * CompactionResult#modelId()} so a cheap summary model isn't billed at the main loop's rate — and
   * emit {@link QueryEvent.ContextEdited} after a successful shrink. A result shrinks the history
   * when the {@link TokenCounter}'s estimate of it is below the estimate of the history the
   * compactor was given: both sides are the same measure, whatever the provider reported. A
   * returned identity (same instance) or no-shrink result is treated as a no-op — the compactor
   * opted out for this turn and the warning flag stays set. A throwing compactor is swallowed; the
   * loop continues, unless what it threw carries an {@link Error} ({@link SessionEnding#failWith}),
   * which ends the session.
   */
  private void compact(SessionState state) {
    var historyBefore = state.history().snapshot();
    var started = collaborators.clock().instant();
    CompactionResult result;
    try {
      result = contextCompactor.compact(historyBefore, collaborators.hookContext(state));
    } catch (RuntimeException e) {
      if (!state.ending().failWith(e)) {
        LOGGER.log(
            Level.WARNING,
            "context compactor threw; leaving history unchanged this turn",
            SerializedError.redactedForLog(e, collaborators.secrets().redactor()));
      }
      return;
    }
    if (result == null) {
      return;
    }
    var compactionUsage = result.usage();
    if (!result.modelId().isBlank()) {
      turnRunner
          .accounting()
          .accountCompaction(
              state, result.modelId(), compactionUsage, started, collaborators.clock().instant());
    }
    var historyAfter = result.history();
    if (historyAfter == historyBefore) {
      return;
    }
    var tokensBefore = tokenCounter.count(historyBefore);
    var tokensAfter = tokenCounter.count(historyAfter);
    if (tokensAfter >= tokensBefore) {
      return;
    }
    state.history().replace(historyAfter);
    var removedBlocks = Math.max(0, historyBefore.size() - historyAfter.size());
    emitter.emit(
        state,
        new QueryEvent.ContextEdited(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            removedBlocks,
            tokensBefore,
            tokensAfter));
  }
}
