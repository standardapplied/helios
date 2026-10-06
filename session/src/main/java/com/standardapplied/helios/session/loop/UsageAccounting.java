/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response.Usage;
import java.util.Objects;

/**
 * Adds a model call's usage to the session totals and prices it through the session's {@link
 * CostCalculator}: the main model's turns at its own rate, and a {@link
 * com.standardapplied.helios.session.ContextCompactor}'s summary call at the compactor's model rate
 * — without that, a cheap Haiku compactor wired against an Opus main loop would have its tokens
 * priced at Opus rates.
 */
final class UsageAccounting {

  private final Model model;
  private final CostCalculator costCalculator;

  UsageAccounting(Model model, CostCalculator costCalculator) {
    this.model = model;
    this.costCalculator = costCalculator;
  }

  /**
   * Accumulate a main-model turn's {@code usage} and price it against {@link Model#id()}.
   *
   * @param usage non-null; {@link Usage#of(int, int) Usage.of(0, 0)} is a legal no-op
   */
  void add(SessionState state, Usage usage) {
    add(state, model.id(), usage);
  }

  /**
   * Accumulate {@code usage} and price it against {@code modelId}; a blank {@code modelId} falls
   * back to the main model, appropriate when {@code usage} carries zero tokens.
   *
   * @throws NullPointerException if any argument is null
   */
  void add(SessionState state, String modelId, Usage usage) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(modelId, "modelId must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    state.totals().accumulateUsage(usage);
    var effectiveId = modelId.isBlank() ? model.id() : modelId;
    state.totals().accumulateCost(costCalculator.cost(effectiveId, usage));
  }
}
