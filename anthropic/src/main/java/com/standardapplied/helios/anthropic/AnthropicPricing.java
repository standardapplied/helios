/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.CostEstimate;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Objects;

/**
 * Anthropic's published list prices for every model in {@link AnthropicModelId}, as a ready-made
 * {@link CostCalculator}. Wire it with {@code
 * SessionOptions.Builder.withCostCalculator(AnthropicPricing.calculator(cachePolicy))} so {@code
 * ResultMessage.cost()} and {@code SessionLimits.maxBudgetMicroUsd} work without a hand-maintained
 * rate table.
 *
 * <p>Rates are the Claude API's standard prices: global routing, no Batch API discount, no fast
 * mode premium. Amazon Bedrock and Google Cloud set their own prices; build a {@link
 * CostCalculator#staticTable(java.util.Map)} for those. Prices change without a Helios release —
 * compare {@link #AS_OF} against the pricing page before trusting the numbers for billing.
 *
 * <p>The calculator prices the token usage a turn reports and nothing else: per-request server-tool
 * fees (web search) are not included, and a refusal the API does not bill still reports usage, so
 * the estimate errs high rather than low.
 */
public final class AnthropicPricing {

  /** The date these rates were last checked against Anthropic's pricing page. */
  public static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

  private AnthropicPricing() {}

  /**
   * A calculator pricing every catalogued model, including its dated snapshots, at list price. A
   * model id outside the catalogue costs {@link CostEstimate#zero()}, matching {@link
   * CostCalculator#staticTable(java.util.Map)}: an unknown rate is a configuration gap, not
   * something to guess at.
   *
   * @param cachePolicy the policy the session's model was created with through {@link
   *     AnthropicProvider}; selects the five-minute or one-hour cache-write rate. Non-null
   * @return a calculator backed by an immutable snapshot of the rate card
   * @throws NullPointerException if {@code cachePolicy} is null
   */
  public static CostCalculator calculator(CachePolicy cachePolicy) {
    var rateCard = new EnumMap<AnthropicModelId, Pricing>(AnthropicModelId.class);
    for (var model : AnthropicModelId.values()) {
      rateCard.put(model, pricing(model, cachePolicy));
    }
    return (modelId, usage) -> {
      Objects.requireNonNull(modelId, "modelId must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
      var model = AnthropicModelId.fromWireId(modelId);
      return model == null ? CostEstimate.zero() : rateCard.get(model).cost(usage);
    };
  }

  /**
   * The list price of one model.
   *
   * @param model the model to price; non-null
   * @param cachePolicy selects the cache-write rate: 2× base input for {@link
   *     CachePolicy#longLived()}, 1.25× otherwise. Non-null
   * @return the per-million-token rates in micro-USD
   * @throws NullPointerException if either argument is null
   */
  public static Pricing pricing(AnthropicModelId model, CachePolicy cachePolicy) {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(cachePolicy, "cachePolicy must not be null");
    return switch (model) {
      case CLAUDE_FABLE_5_1, CLAUDE_MYTHOS_5_1 ->
          pricing(10_000_000L, 50_000_000L, 250_000L, cachePolicy);
      case CLAUDE_FABLE_5, CLAUDE_MYTHOS_5 ->
          pricing(10_000_000L, 50_000_000L, 1_000_000L, cachePolicy);
      case CLAUDE_OPUS_5_5 -> pricing(4_000_000L, 20_000_000L, 200_000L, cachePolicy);
      case CLAUDE_OPUS_5, CLAUDE_OPUS_4_8, CLAUDE_OPUS_4_7, CLAUDE_OPUS_4_6 ->
          pricing(5_000_000L, 25_000_000L, 500_000L, cachePolicy);
      case CLAUDE_SONNET_5_5, CLAUDE_SONNET_5 ->
          pricing(2_000_000L, 10_000_000L, 200_000L, cachePolicy);
      case CLAUDE_SONNET_4_6 -> pricing(3_000_000L, 15_000_000L, 300_000L, cachePolicy);
      case CLAUDE_HAIKU_4_5 -> pricing(1_000_000L, 5_000_000L, 100_000L, cachePolicy);
    };
  }

  private static Pricing pricing(long input, long output, long cacheRead, CachePolicy cachePolicy) {
    var cacheWrite = cachePolicy instanceof CachePolicy.LongLived ? input * 2 : input * 5 / 4;
    return new Pricing(input, output, cacheWrite, cacheRead);
  }
}
