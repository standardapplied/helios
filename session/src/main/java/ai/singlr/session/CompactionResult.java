/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response.Usage;
import java.util.List;
import java.util.Objects;

/**
 * Result of one {@link ContextCompactor#compact(List, ai.singlr.session.loop.SessionState)} call.
 *
 * <p>Carries the new history alongside the usage consumed by the compaction itself. Compactors that
 * make a model call (e.g. {@link DropMiddleToolResultsCompactor}) report the summary call's usage
 * so the agent loop can accumulate it into the session totals and apply the configured {@code
 * CostCalculator} — without this, compaction spend is invisible to {@code
 * SessionLimits.maxBudgetMicroUsd} gating. Compactors that don't call the model return {@link
 * #noOp(List)} or supply {@link Usage#of(int, int) Usage.of(0, 0)}.
 *
 * @param history the new conversation history; non-null. May be the supplied {@code history}
 *     unchanged when nothing was dropped — the loop detects the no-shrink and does not emit {@code
 *     ContextEdited}
 * @param usage tokens consumed by the compaction step itself; non-null. {@link Usage#of(int, int)
 *     Usage.of(0, 0)} for compactors that don't invoke a model
 */
public record CompactionResult(List<Message> history, Usage usage) {

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
   */
  public CompactionResult {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
  }

  /**
   * No-op result: returns the supplied history unchanged with zero usage. Used by compactors that
   * declined to shrink for this turn.
   *
   * @param history the (unchanged) history; non-null
   * @return a fresh result
   * @throws NullPointerException if {@code history} is null
   */
  public static CompactionResult noOp(List<Message> history) {
    return new CompactionResult(history, Usage.of(0, 0));
  }
}
