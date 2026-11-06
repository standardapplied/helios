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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnthropicPricingTest {

  private static Usage usage(int input, int cacheWrite, int cacheRead, int output) {
    return Usage.of(input, output, cacheWrite, cacheRead);
  }

  private static void assertListPrice(
      AnthropicModelId model, long input, long output, long cacheWrite5m, long cacheRead) {
    assertEquals(
        new Pricing(input, output, cacheWrite5m, cacheRead),
        AnthropicPricing.pricing(model, CachePolicy.shortLived()),
        model.name());
  }

  @Test
  void everyModelCarriesItsPublishedListPrice() {
    assertListPrice(
        AnthropicModelId.CLAUDE_FABLE_5_1, 10_000_000L, 50_000_000L, 12_500_000L, 250_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_MYTHOS_5_1, 10_000_000L, 50_000_000L, 12_500_000L, 250_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_FABLE_5, 10_000_000L, 50_000_000L, 12_500_000L, 1_000_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_MYTHOS_5, 10_000_000L, 50_000_000L, 12_500_000L, 1_000_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_OPUS_5_5, 4_000_000L, 20_000_000L, 5_000_000L, 200_000L);
    assertListPrice(AnthropicModelId.CLAUDE_OPUS_5, 5_000_000L, 25_000_000L, 6_250_000L, 500_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_SONNET_5_5, 2_000_000L, 10_000_000L, 2_500_000L, 200_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_SONNET_5, 2_000_000L, 10_000_000L, 2_500_000L, 200_000L);
    assertListPrice(AnthropicModelId.CLAUDE_HAIKU_5_5, 500_000L, 2_500_000L, 625_000L, 50_000L);
  }

  @Test
  void haiku55IsPricedAtItsLongPromptRate() {
    var shortLived = AnthropicPricing.calculator(CachePolicy.shortLived());
    var longLived = AnthropicPricing.calculator(CachePolicy.longLived());

    assertEquals(
        CostEstimate.ofMicroUsd(500_000L),
        shortLived.cost("claude-haiku-5-5", usage(1_000_000, 0, 0, 0)));
    assertEquals(
        CostEstimate.ofMicroUsd(2_500_000L),
        shortLived.cost("claude-haiku-5-5", usage(0, 0, 0, 1_000_000)));
    assertEquals(
        CostEstimate.ofMicroUsd(50_000L),
        shortLived.cost("claude-haiku-5-5", usage(0, 0, 1_000_000, 0)));
    assertEquals(
        CostEstimate.ofMicroUsd(625_000L),
        shortLived.cost("claude-haiku-5-5", usage(0, 1_000_000, 0, 0)));
    assertEquals(
        CostEstimate.ofMicroUsd(1_000_000L),
        longLived.cost("claude-haiku-5-5", usage(0, 1_000_000, 0, 0)));
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
        CostEstimate.ofMicroUsd(370_835L),
        calculator.cost("claude-sonnet-5-5", usage(26, 67_977, 361_356, 12_857)));
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
  }

  @Test
  void calculatorPricesEveryCataloguedModelAtItsListPrice() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());
    var usage = usage(1_000_000, 1_000_000, 1_000_000, 1_000_000);

    for (var model : AnthropicModelId.values()) {
      assertEquals(
          AnthropicPricing.pricing(model, CachePolicy.shortLived()).cost(usage),
          calculator.cost(model.id(), usage),
          model.name());
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
