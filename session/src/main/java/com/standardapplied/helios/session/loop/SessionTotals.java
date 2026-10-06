/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.Response.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The running totals of one session — usage, cost and grounding citations across every model call —
 * read by anyone and accumulated only by the loop.
 *
 * <h2>Thread-safety</h2>
 *
 * Each total is an {@link AtomicReference}: the loop thread writes, any thread may read. Readers
 * see a consistent value per total, not necessarily across totals.
 */
public final class SessionTotals {

  private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.of(0, 0));
  private final AtomicReference<CostEstimate> cost = new AtomicReference<>(CostEstimate.zero());
  private final AtomicReference<List<Citation>> citations = new AtomicReference<>(List.of());

  SessionTotals() {}

  /**
   * The accumulated usage across every model call in this session.
   *
   * @return the running usage; non-null
   */
  public Usage usage() {
    return usage.get();
  }

  /**
   * The accumulated cost across this session.
   *
   * @return the running cost; non-null
   */
  public CostEstimate cost() {
    return cost.get();
  }

  /**
   * The grounding citations accumulated across every model turn in this session, in document order
   * with exact duplicates suppressed.
   *
   * @return the running citations; non-null, immutable, may be empty
   */
  public List<Citation> citations() {
    return citations.get();
  }

  /**
   * Add the given usage delta to the running totals.
   *
   * @throws NullPointerException if {@code delta} is null
   */
  void accumulateUsage(Usage delta) {
    Objects.requireNonNull(delta, "delta must not be null");
    usage.updateAndGet(prev -> prev.plus(delta));
  }

  /**
   * Add the given cost delta to the running total.
   *
   * @throws NullPointerException if {@code delta} is null
   */
  void accumulateCost(CostEstimate delta) {
    Objects.requireNonNull(delta, "delta must not be null");
    cost.updateAndGet(prev -> prev.plus(delta));
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
