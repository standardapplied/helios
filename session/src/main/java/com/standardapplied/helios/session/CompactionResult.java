/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response.Usage;
import java.util.List;
import java.util.Objects;

/**
 * Result of one {@link ContextCompactor#compact(List, ai.singlr.session.loop.SessionState)} call.
 *
 * <p>Carries the new history, the usage consumed by the compaction step itself, and the identifier
 * of the model the compactor invoked to spend that usage. Compactors that make a model call (e.g.
 * {@link DropMiddleToolResultsCompactor}) report the summary call's usage so the agent loop can
 * accumulate it into the session totals and apply the configured {@code CostCalculator} — without
 * this, compaction spend is invisible to {@code SessionLimits.maxBudgetMicroUsd} gating.
 *
 * <p>The {@code modelId} is what lets the loop price compaction spend at the right rate. Pinning
 * compaction cost to the main session model would overcharge any deployment that wired a cheap
 * summary model (e.g. Haiku 4.5) while keeping a premium model (e.g. Opus 4.7) as the main loop —
 * the whole point of letting the summary model be configurable. Pure-trim compactors return {@link
 * #noOp(List)} (empty {@code modelId} and zero usage); the loop then has no cost to attribute.
 *
 * @param history the new conversation history; non-null. May be the supplied {@code history}
 *     unchanged when nothing was dropped — the loop detects the no-shrink and does not emit {@code
 *     ContextEdited}
 * @param usage tokens consumed by the compaction step itself; non-null. {@link Usage#of(int, int)
 *     Usage.of(0, 0)} for compactors that don't invoke a model
 * @param modelId identifier of the model the compactor spent {@code usage} on; non-null. Must be
 *     non-blank when {@code usage} carries any tokens (input or output) — otherwise the loop has no
 *     correct rate to price the spend against. Pure-trim / no-op results use the empty string
 */
public record CompactionResult(List<Message> history, Usage usage, String modelId) {

  /**
   * Canonical constructor.
   *
   * <p>{@code history} is NOT defensively copied here — {@code AgentLoop} calls {@link
   * ai.singlr.session.loop.SessionState#replaceHistory(List)} which performs its own copy, and
   * leaving identity intact lets compactors signal "no-op" by returning the supplied list
   * unchanged. Implementations that build a fresh history should return an immutable list (e.g. via
   * {@link List#copyOf(java.util.Collection)}).
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code usage} reports non-zero tokens but {@code modelId}
   *     is blank — usage without a model id cannot be priced correctly
   */
  public CompactionResult {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(modelId, "modelId must not be null");
    if ((usage.inputTokens() > 0 || usage.outputTokens() > 0) && Strings.isBlank(modelId)) {
      throw new IllegalArgumentException(
          "modelId must be non-blank when usage reports any tokens; got input="
              + usage.inputTokens()
              + " output="
              + usage.outputTokens());
    }
  }

  /**
   * No-op result: returns the supplied history unchanged with zero usage and an empty {@code
   * modelId}. Used by compactors that declined to shrink for this turn or by trim-only compactors
   * that never invoke a model.
   *
   * @param history the (unchanged) history; non-null
   * @return a fresh result
   * @throws NullPointerException if {@code history} is null
   */
  public static CompactionResult noOp(List<Message> history) {
    return new CompactionResult(history, Usage.of(0, 0), "");
  }
}
