/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
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
 * <p>Claude Haiku 5.5 is priced by the length of each request's prompt, as Anthropic bills it: a
 * request whose prompt ({@code Usage.promptTokens()}, input, cache writes and cache reads together)
 * is over 100,000 tokens pays $0.50 / $2.50 per million input / output tokens for all of its
 * tokens, a shorter one $0.10 / $0.50; a {@code pause_turn} turn is priced at the tier of its last
 * request.
 *
 * <p>The calculator prices the token usage a turn reports and nothing else: per-request server-tool
 * fees (web search) are not included, and a refusal the API does not bill still reports usage, so
 * the estimate errs high rather than low.
 */
public final class AnthropicPricing {

  /** The date these rates were last checked against Anthropic's pricing page. */
  public static final LocalDate AS_OF = LocalDate.of(2026, 11, 11);

  private static final long HAIKU_SHORT_PROMPT_LIMIT = 100_000L;

  private AnthropicPricing() {}

  /**
   * A calculator pricing every catalogued model, including its dated snapshots, at list price. A
   * model id outside the catalogue throws, matching {@link
   * CostCalculator#staticTable(java.util.Map)}: an unknown rate is a configuration gap, never a
   * price of zero.
   *
   * @param cachePolicy the policy the session's model was created with through {@link
   *     AnthropicProvider}; selects the five-minute or one-hour cache-write rate. Non-null
   * @return a calculator backed by an immutable snapshot of the rate card, whose {@code cost}
   *     throws {@link IllegalArgumentException} for an id {@link AnthropicModelId#fromWireId} does
   *     not resolve
   * @throws NullPointerException if {@code cachePolicy} is null
   */
  public static CostCalculator calculator(CachePolicy cachePolicy) {
    var rateCard = new EnumMap<AnthropicModelId, Pricing>(AnthropicModelId.class);
    for (var model : AnthropicModelId.values()) {
      rateCard.put(model, pricing(model, cachePolicy));
    }
    var haikuLongPrompt = pricing(500_000L, 2_500_000L, 50_000L, cachePolicy);
    return (modelId, usage) -> {
      Objects.requireNonNull(modelId, "modelId must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
      var model = AnthropicModelId.fromWireId(modelId);
      if (model == null) {
        throw new IllegalArgumentException(
            "no price for model '" + modelId + "'; add it to the pricing table");
      }
      var longHaikuPrompt =
          model == AnthropicModelId.CLAUDE_HAIKU_5_5
              && usage.promptTokens() > HAIKU_SHORT_PROMPT_LIMIT;
      return (longHaikuPrompt ? haikuLongPrompt : rateCard.get(model)).cost(usage);
    };
  }

  /**
   * The list price of one model. For Claude Haiku 5.5 this is the rate of a request whose prompt is
   * at most 100,000 tokens; {@link #calculator} prices a longer one at five times every rate.
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
      case CLAUDE_FABLE_5_2, CLAUDE_MYTHOS_5_2 ->
          pricing(8_000_000L, 40_000_000L, 200_000L, cachePolicy);
      case CLAUDE_FABLE_5_1, CLAUDE_MYTHOS_5_1 ->
          pricing(10_000_000L, 50_000_000L, 250_000L, cachePolicy);
      case CLAUDE_FABLE_5, CLAUDE_MYTHOS_5 ->
          pricing(10_000_000L, 50_000_000L, 1_000_000L, cachePolicy);
      case CLAUDE_OPUS_5_5 -> pricing(4_000_000L, 20_000_000L, 200_000L, cachePolicy);
      case CLAUDE_OPUS_5 -> pricing(5_000_000L, 25_000_000L, 500_000L, cachePolicy);
      case CLAUDE_SONNET_5_5 -> pricing(2_000_000L, 10_000_000L, 100_000L, cachePolicy);
      case CLAUDE_SONNET_5 -> pricing(2_000_000L, 10_000_000L, 200_000L, cachePolicy);
      case CLAUDE_HAIKU_5_5 -> pricing(100_000L, 500_000L, 10_000L, cachePolicy);
    };
  }

  private static Pricing pricing(long input, long output, long cacheRead, CachePolicy cachePolicy) {
    var cacheWrite = cachePolicy instanceof CachePolicy.LongLived ? input * 2 : input * 5 / 4;
    return new Pricing(input, output, cacheWrite, cacheRead);
  }
}
