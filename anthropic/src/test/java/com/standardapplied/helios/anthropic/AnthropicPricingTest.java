/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnthropicPricingTest {

  private static Usage usage(int input, int cacheWrite, int cacheRead, int output) {
    return Usage.newBuilder()
        .withInputTokens(input)
        .withOutputTokens(output)
        .withCacheCreationInputTokens(cacheWrite)
        .withCacheReadInputTokens(cacheRead)
        .build();
  }

  private record ListPrice(long input, long write5m, long write1h, long cacheRead, long output) {}

  private static final Map<AnthropicModelId, ListPrice> LIST_PRICES =
      Map.ofEntries(
          Map.entry(
              AnthropicModelId.CLAUDE_FABLE_5_2,
              new ListPrice(8_000_000L, 10_000_000L, 16_000_000L, 200_000L, 40_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_MYTHOS_5_2,
              new ListPrice(8_000_000L, 10_000_000L, 16_000_000L, 200_000L, 40_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_FABLE_5_1,
              new ListPrice(10_000_000L, 12_500_000L, 20_000_000L, 250_000L, 50_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_MYTHOS_5_1,
              new ListPrice(10_000_000L, 12_500_000L, 20_000_000L, 250_000L, 50_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_FABLE_5,
              new ListPrice(10_000_000L, 12_500_000L, 20_000_000L, 1_000_000L, 50_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_MYTHOS_5,
              new ListPrice(10_000_000L, 12_500_000L, 20_000_000L, 1_000_000L, 50_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_OPUS_5_5,
              new ListPrice(4_000_000L, 5_000_000L, 8_000_000L, 200_000L, 20_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_OPUS_5,
              new ListPrice(5_000_000L, 6_250_000L, 10_000_000L, 500_000L, 25_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_SONNET_5_5,
              new ListPrice(2_000_000L, 2_500_000L, 4_000_000L, 100_000L, 10_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_SONNET_5,
              new ListPrice(2_000_000L, 2_500_000L, 4_000_000L, 200_000L, 10_000_000L)),
          Map.entry(
              AnthropicModelId.CLAUDE_HAIKU_5_5,
              new ListPrice(100_000L, 125_000L, 200_000L, 10_000L, 500_000L)));

  private static final Pricing HAIKU_LONG_PROMPT_SHORT_LIVED =
      new Pricing(500_000L, 2_500_000L, 625_000L, 50_000L);

  @Test
  void everyModelCarriesItsPublishedListPrice() {
    assertEquals(EnumSet.allOf(AnthropicModelId.class), LIST_PRICES.keySet());

    for (var model : AnthropicModelId.values()) {
      var price = LIST_PRICES.get(model);
      assertEquals(
          new Pricing(price.input(), price.output(), price.write5m(), price.cacheRead()),
          AnthropicPricing.pricing(model, CachePolicy.shortLived()),
          model.name());
      assertEquals(
          new Pricing(price.input(), price.output(), price.write1h(), price.cacheRead()),
          AnthropicPricing.pricing(model, CachePolicy.longLived()),
          model.name());
    }
  }

  @Test
  void haiku55PricesAPromptOverOneHundredThousandTokensAtItsLongPromptRate() {
    var usage = usage(1_000_000, 1_000_000, 1_000_000, 1_000_000);

    assertEquals(
        CostEstimate.ofMicroUsd(3_675_000L),
        AnthropicPricing.calculator(CachePolicy.shortLived()).cost("claude-haiku-5-5", usage));
    assertEquals(
        CostEstimate.ofMicroUsd(4_050_000L),
        AnthropicPricing.calculator(CachePolicy.longLived()).cost("claude-haiku-5-5", usage));
  }

  @Test
  void haiku55PricesTheReportedFiftyThousandTokenPromptAtItsShortPromptRate() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());

    assertEquals(
        CostEstimate.ofMicroUsd(5_000L), calculator.cost("claude-haiku-5-5", Usage.of(50_000, 0)));
  }

  @Test
  void haiku55TiersOnPromptTokensWithOneHundredThousandStillShort() {
    var atLimit =
        Usage.newBuilder()
            .withInputTokens(40_000)
            .withOutputTokens(1_000)
            .withCacheCreationInputTokens(10_000)
            .withCacheReadInputTokens(50_000)
            .build();
    var overLimit =
        Usage.newBuilder()
            .withInputTokens(40_000)
            .withOutputTokens(1_000)
            .withCacheCreationInputTokens(10_000)
            .withCacheReadInputTokens(50_001)
            .build();
    var shortLived = AnthropicPricing.calculator(CachePolicy.shortLived());
    var longLived = AnthropicPricing.calculator(CachePolicy.longLived());

    assertEquals(CostEstimate.ofMicroUsd(6_250L), shortLived.cost("claude-haiku-5-5", atLimit));
    assertEquals(CostEstimate.ofMicroUsd(31_250L), shortLived.cost("claude-haiku-5-5", overLimit));
    assertEquals(CostEstimate.ofMicroUsd(7_000L), longLived.cost("claude-haiku-5-5", atLimit));
    assertEquals(CostEstimate.ofMicroUsd(35_000L), longLived.cost("claude-haiku-5-5", overLimit));
  }

  @Test
  void haiku55PricesAPausedTurnAtTheTierOfItsLastRequest() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());
    var lastShort = Usage.of(60_000, 0).plus(Usage.of(70_000, 0));
    var lastLong = Usage.of(60_000, 0).plus(Usage.of(110_000, 0));

    assertEquals(CostEstimate.ofMicroUsd(13_000L), calculator.cost("claude-haiku-5-5", lastShort));
    assertEquals(CostEstimate.ofMicroUsd(85_000L), calculator.cost("claude-haiku-5-5", lastLong));
  }

  @Test
  void haiku55PricesAUsageWithoutAPromptAtItsShortPromptRate() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());

    assertEquals(
        CostEstimate.ofMicroUsd(500_000L),
        calculator.cost("claude-haiku-5-5", Usage.of(0, 1_000_000)));
  }

  @Test
  void cacheWriteRateFollowsTheCachePolicyTtl() {
    var model = AnthropicModelId.CLAUDE_OPUS_5_5;

    assertEquals(
        5_000_000L,
        AnthropicPricing.pricing(model, CachePolicy.shortLived()).cacheWriteMicroUsdPerMillion());
    assertEquals(
        8_000_000L,
        AnthropicPricing.pricing(model, CachePolicy.longLived()).cacheWriteMicroUsdPerMillion());
    assertEquals(
        5_000_000L,
        AnthropicPricing.pricing(model, CachePolicy.disabled()).cacheWriteMicroUsdPerMillion());
  }

  @Test
  void calculatorReproducesMeasuredRunCostsAtListPrice() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());

    assertEquals(
        CostEstimate.ofMicroUsd(514_200L),
        calculator.cost("claude-opus-5-5", usage(16, 56_757, 249_257, 9_025)));
    assertEquals(
        CostEstimate.ofMicroUsd(334_699L),
        calculator.cost("claude-sonnet-5-5", usage(26, 67_977, 361_356, 12_857)));
    assertEquals(
        CostEstimate.ofMicroUsd(370_835L),
        calculator.cost("claude-sonnet-5", usage(26, 67_977, 361_356, 12_857)));
  }

  @Test
  void calculatorPricesOneHourCacheWritesAtTwiceBaseInput() {
    var calculator = AnthropicPricing.calculator(CachePolicy.longLived());

    assertEquals(
        CostEstimate.ofMicroUsd(8_000_000L),
        calculator.cost("claude-opus-5-5", usage(0, 1_000_000, 0, 0)));
  }

  @Test
  void calculatorPricesDatedSnapshotsAtTheirFamilyRate() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());

    assertEquals(
        CostEstimate.ofMicroUsd(500_000L),
        calculator.cost("claude-haiku-5-5-20261101", usage(1_000_000, 0, 0, 0)));
    assertEquals(
        CostEstimate.ofMicroUsd(5_000L),
        calculator.cost("claude-haiku-5-5-20261101", usage(50_000, 0, 0, 0)));
  }

  @Test
  void calculatorPricesEveryCataloguedModelAtItsListPrice() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());
    var shortPrompt = usage(40_000, 10_000, 50_000, 1_000);
    var longPrompt = usage(1_000_000, 1_000_000, 1_000_000, 1_000_000);

    for (var model : AnthropicModelId.values()) {
      var listPrice = AnthropicPricing.pricing(model, CachePolicy.shortLived());
      var longPromptPrice =
          model == AnthropicModelId.CLAUDE_HAIKU_5_5 ? HAIKU_LONG_PROMPT_SHORT_LIVED : listPrice;
      assertEquals(
          listPrice.cost(shortPrompt), calculator.cost(model.id(), shortPrompt), model.name());
      assertEquals(
          longPromptPrice.cost(longPrompt), calculator.cost(model.id(), longPrompt), model.name());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"gpt-6-sol", "claude-opus-5-7", "anthropic.claude-opus-5-5"})
  void calculatorRefusesModelsOutsideTheCatalogue(String modelId) {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());
    var usage = usage(1_000_000, 0, 0, 1_000_000);

    var ex = assertThrows(IllegalArgumentException.class, () -> calculator.cost(modelId, usage));

    assertEquals(
        "no price for model '" + modelId + "'; add it to the pricing table", ex.getMessage());
  }

  @Test
  void nullArgumentsAreRejected() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());

    assertThrows(NullPointerException.class, () -> AnthropicPricing.calculator(null));
    assertThrows(
        NullPointerException.class, () -> AnthropicPricing.pricing(null, CachePolicy.shortLived()));
    assertThrows(
        NullPointerException.class,
        () -> AnthropicPricing.pricing(AnthropicModelId.CLAUDE_OPUS_5_5, null));
    assertThrows(NullPointerException.class, () -> calculator.cost(null, usage(1, 0, 0, 1)));
    assertThrows(NullPointerException.class, () -> calculator.cost("claude-opus-5-5", null));
  }
}
