/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.Response.Usage;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The running totals of one run — usage, cost and grounding citations across every model call, and
 * the time since the run started — read by anyone and accumulated by the loop. The totals of a
 * subagent's child run roll up into those of the parent run whose {@code Agent} call opened it:
 * each usage and cost the child adds is added to the parent's as it is added, whatever the child's
 * terminal and however long its teardown takes, so the parent's result and budget check count it.
 * The parent's {@link Usage#promptTokens()} stays the size of its own last request, since the
 * child's requests are not its prompt.
 *
 * <h2>Thread-safety</h2>
 *
 * Each total is an {@link AtomicReference} updated atomically: the run's loop thread writes, and so
 * do the loop threads of its child runs; any thread may read. Readers see a consistent value per
 * total, not necessarily across totals.
 */
public final class SessionTotals {

  private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.of(0, 0));
  private final AtomicReference<CostEstimate> cost = new AtomicReference<>(CostEstimate.zero());
  private final AtomicReference<List<Citation>> citations = new AtomicReference<>(List.of());

  private final InstantSource clock;
  private final Instant startedAt;
  private final Optional<SessionTotals> parentRun;

  /** The totals of a run started now, rolling up into {@code parentRun}'s if present. */
  SessionTotals(InstantSource clock, Optional<SessionTotals> parentRun) {
    this.clock = clock;
    this.startedAt = clock.instant();
    this.parentRun = parentRun;
  }

  /**
   * The wall-clock duration since the run started.
   *
   * @return a non-negative duration
   */
  public Duration elapsed() {
    return Duration.between(startedAt, clock.instant());
  }

  /**
   * The accumulated usage across every model call in this run.
   *
   * @return the running usage; non-null
   */
  public Usage usage() {
    return usage.get();
  }

  /**
   * The accumulated cost across this run.
   *
   * @return the running cost; non-null
   */
  public CostEstimate cost() {
    return cost.get();
  }

  /**
   * The grounding citations accumulated across every model turn in this run, in document order with
   * exact duplicates suppressed.
   *
   * @return the running citations; non-null, immutable, may be empty
   */
  public List<Citation> citations() {
    return citations.get();
  }

  /**
   * Add the given usage delta to the running totals, and its token counts to the parent run's.
   *
   * @throws NullPointerException if {@code delta} is null
   */
  void accumulateUsage(Usage delta) {
    Objects.requireNonNull(delta, "delta must not be null");
    usage.updateAndGet(prev -> prev.plus(delta));
    parentRun.ifPresent(parent -> parent.accumulateUsage(withoutPrompt(delta)));
  }

  /**
   * Add the given cost delta to the running total, and to the parent run's.
   *
   * @throws NullPointerException if {@code delta} is null
   */
  void accumulateCost(CostEstimate delta) {
    Objects.requireNonNull(delta, "delta must not be null");
    cost.updateAndGet(prev -> prev.plus(delta));
    parentRun.ifPresent(parent -> parent.accumulateCost(delta));
  }

  /** {@code delta}'s token counts with no prompt size, which {@link Usage#plus} then keeps. */
  private static Usage withoutPrompt(Usage delta) {
    return new Usage(
        delta.inputTokens(),
        delta.outputTokens(),
        delta.cacheCreationInputTokens(),
        delta.cacheReadInputTokens(),
        delta.totalTokens(),
        0,
        delta.reasoningTokens());
  }

  /**
   * Append a turn's grounding citations to the running list, preserving document order and
   * suppressing exact duplicates (a citation already present by value is not re-added). Empty
   * deltas are a no-op. The running list is what {@link
   * com.standardapplied.helios.session.ResultMessage.Success#citations()} reports.
   *
   * @throws NullPointerException if {@code delta} or any element is null
   */
  void accumulateCitations(List<Citation> delta) {
    Objects.requireNonNull(delta, "delta must not be null");
    if (delta.isEmpty()) {
      return;
    }
    for (var c : delta) {
      Objects.requireNonNull(c, "delta must not contain null");
    }
    citations.updateAndGet(
        prev -> {
          var merged = new ArrayList<Citation>(prev.size() + delta.size());
          merged.addAll(prev);
          for (var c : delta) {
            if (!merged.contains(c)) {
              merged.add(c);
            }
          }
          return List.copyOf(merged);
        });
  }
}
