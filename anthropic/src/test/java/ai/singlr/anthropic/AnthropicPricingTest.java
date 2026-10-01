/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.common.CostCalculator.Pricing;
import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Response.Usage;
import org.junit.jupiter.api.Test;

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
        AnthropicModelId.CLAUDE_OPUS_4_8, 5_000_000L, 25_000_000L, 6_250_000L, 500_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_OPUS_4_7, 5_000_000L, 25_000_000L, 6_250_000L, 500_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_OPUS_4_6, 5_000_000L, 25_000_000L, 6_250_000L, 500_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_SONNET_5_5, 2_000_000L, 10_000_000L, 2_500_000L, 200_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_SONNET_5, 2_000_000L, 10_000_000L, 2_500_000L, 200_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_SONNET_4_6, 3_000_000L, 15_000_000L, 3_750_000L, 300_000L);
    assertListPrice(
        AnthropicModelId.CLAUDE_HAIKU_4_5, 1_000_000L, 5_000_000L, 1_250_000L, 100_000L);
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
    assertEquals(
        CostEstimate.ofMicroUsd(2_121_095L),
        calculator.cost("claude-opus-4-7", usage(28, 107_322, 1_642_336, 25_161)));
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
        CostEstimate.ofMicroUsd(1_000_000L),
        calculator.cost("claude-haiku-4-5-20251001", usage(1_000_000, 0, 0, 0)));
  }

  @Test
  void calculatorChargesNothingForModelsOutsideTheCatalogue() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());
    var usage = usage(1_000_000, 0, 0, 1_000_000);

    assertEquals(CostEstimate.zero(), calculator.cost("claude-opus-5-7", usage));
    assertEquals(CostEstimate.zero(), calculator.cost("anthropic.claude-opus-5-5", usage));
    assertEquals(CostEstimate.zero(), calculator.cost("gpt-6-astra", usage));
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
