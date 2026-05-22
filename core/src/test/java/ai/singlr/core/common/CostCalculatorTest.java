/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator.Pricing;
import ai.singlr.core.model.Response.Usage;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CostCalculatorTest {

  // ── ZERO sentinel ────────────────────────────────────────────────────────

  @Test
  void zeroSentinelAlwaysReturnsZeroCost() {
    assertSame(CostEstimate.zero(), CostCalculator.ZERO.cost("any-model", Usage.of(1_000, 500)));
    assertSame(CostEstimate.zero(), CostCalculator.ZERO.cost("other", Usage.of(0, 0)));
  }

  // ── Pricing record ───────────────────────────────────────────────────────

  @Test
  void pricingComputesInputPlusOutputDividedByMillion() {
    // Opus-style: $3 input ($15 input)/M, $15 output/M.
    var p = new Pricing(3_000_000L, 15_000_000L);
    // 1 000 input * 3 microUSD = 3 000 microUSD;  500 output * 15 = 7 500;  total 10 500 microUSD.
    assertEquals(10_500L, p.cost(Usage.of(1_000, 500)).microUsd());
  }

  @Test
  void pricingZeroUsageProducesZeroCost() {
    var p = new Pricing(3_000_000L, 15_000_000L);
    assertSame(CostEstimate.zero(), p.cost(Usage.of(0, 0)));
  }

  @Test
  void pricingTruncatesSubMicroUsdPerToken() {
    // Haiku-ish: $0.80 input/M = 800 000 microUSD/M = 0.8 microUSD/token.
    // 1 token alone truncates to 0; 10 tokens = 8 microUSD; 1000 tokens = 800 microUSD.
    var p = new Pricing(800_000L, 0L);
    assertEquals(0L, p.cost(Usage.of(1, 0)).microUsd());
    assertEquals(8L, p.cost(Usage.of(10, 0)).microUsd());
    assertEquals(800L, p.cost(Usage.of(1_000, 0)).microUsd());
  }

  @Test
  void pricingOfFactoryRoundsHalfUp() {
    var p = Pricing.ofUsdPerMillion(3.0, 15.0);
    assertEquals(3_000_000L, p.inputMicroUsdPerMillion());
    assertEquals(15_000_000L, p.outputMicroUsdPerMillion());
  }

  @Test
  void pricingRejectsNegativeInputRate() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Pricing(-1L, 0L));
    assertEquals("inputMicroUsdPerMillion must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void pricingRejectsNegativeOutputRate() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Pricing(0L, -1L));
    assertEquals("outputMicroUsdPerMillion must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void pricingRejectsNullUsage() {
    var p = Pricing.ofUsdPerMillion(1.0, 1.0);
    var ex = assertThrows(NullPointerException.class, () -> p.cost(null));
    assertEquals("usage must not be null", ex.getMessage());
  }

  @Test
  void pricingOverflowOnExtremeRate() {
    // Rate at near-Long.MAX_VALUE microUSD/M with non-trivial token count overflows.
    var p = new Pricing(Long.MAX_VALUE, 0L);
    assertThrows(ArithmeticException.class, () -> p.cost(Usage.of(2, 0)));
  }

  // ── staticTable factory ──────────────────────────────────────────────────

  @Test
  void staticTableEmptyMapReturnsZeroForAnyModel() {
    var calc = CostCalculator.staticTable(Map.of());
    assertSame(CostEstimate.zero(), calc.cost("any-model", Usage.of(1_000, 1_000)));
  }

  @Test
  void staticTableLookupAppliesPricingWhenModelMatches() {
    var calc =
        CostCalculator.staticTable(
            Map.of(
                "model-A", Pricing.ofUsdPerMillion(3.0, 15.0),
                "model-B", Pricing.ofUsdPerMillion(0.3, 2.5)));
    // 1 M input * $3/M = $3 = 3_000_000 microUSD.
    assertEquals(3_000_000L, calc.cost("model-A", Usage.of(1_000_000, 0)).microUsd());
    // 1 M output * $2.5/M = $2.5 = 2_500_000 microUSD.
    assertEquals(2_500_000L, calc.cost("model-B", Usage.of(0, 1_000_000)).microUsd());
  }

  @Test
  void staticTableMissingModelReturnsZero() {
    var calc = CostCalculator.staticTable(Map.of("known", Pricing.ofUsdPerMillion(1.0, 1.0)));
    assertSame(CostEstimate.zero(), calc.cost("unknown", Usage.of(1_000_000, 1_000_000)));
  }

  @Test
  void staticTableDefensivelyCopiesPricing() {
    var mutable = new HashMap<String, Pricing>();
    mutable.put("m", Pricing.ofUsdPerMillion(3.0, 15.0));
    var calc = CostCalculator.staticTable(mutable);
    mutable.clear();
    // Calculator still sees the original entry — proves the snapshot was taken.
    assertEquals(3_000_000L, calc.cost("m", Usage.of(1_000_000, 0)).microUsd());
  }

  @Test
  void staticTableRejectsNullPricingMap() {
    var ex = assertThrows(NullPointerException.class, () -> CostCalculator.staticTable(null));
    assertEquals("pricing must not be null", ex.getMessage());
  }

  @Test
  void staticTableLookupRejectsNullModelId() {
    var calc = CostCalculator.staticTable(Map.of());
    var ex = assertThrows(NullPointerException.class, () -> calc.cost(null, Usage.of(1, 1)));
    assertEquals("modelId must not be null", ex.getMessage());
  }

  @Test
  void staticTableLookupRejectsNullUsage() {
    var calc = CostCalculator.staticTable(Map.of());
    var ex = assertThrows(NullPointerException.class, () -> calc.cost("m", null));
    assertEquals("usage must not be null", ex.getMessage());
  }

  // ── cache-aware Pricing (hv2-bug2 Issue 1) ────────────────────────────────

  @Test
  void pricingTwoArgConstructorDefaultsCacheRatesToInputRate() {
    var p = new Pricing(3_000_000L, 15_000_000L);
    assertEquals(
        3_000_000L,
        p.cacheWriteMicroUsdPerMillion(),
        "two-arg ctor: cache-write rate must default to the base input rate");
    assertEquals(
        3_000_000L,
        p.cacheReadMicroUsdPerMillion(),
        "two-arg ctor: cache-read rate must default to the base input rate");
  }

  @Test
  void pricingFourArgCanonicalConstructorCarriesEachRate() {
    // input $15/M, output $75/M, cache write $18.75/M (1.25x), cache read $1.50/M (0.10x).
    var p = new Pricing(15_000_000L, 75_000_000L, 18_750_000L, 1_500_000L);
    assertEquals(15_000_000L, p.inputMicroUsdPerMillion());
    assertEquals(75_000_000L, p.outputMicroUsdPerMillion());
    assertEquals(18_750_000L, p.cacheWriteMicroUsdPerMillion());
    assertEquals(1_500_000L, p.cacheReadMicroUsdPerMillion());
  }

  @Test
  void pricingAnthropicCachingFactoryAppliesPublishedRatios() {
    var p = Pricing.anthropicCaching(15.0, 75.0);
    assertEquals(15_000_000L, p.inputMicroUsdPerMillion());
    assertEquals(75_000_000L, p.outputMicroUsdPerMillion());
    assertEquals(
        18_750_000L,
        p.cacheWriteMicroUsdPerMillion(),
        "Anthropic cache writes bill at 1.25x base input");
    assertEquals(
        1_500_000L,
        p.cacheReadMicroUsdPerMillion(),
        "Anthropic cache reads bill at 0.10x base input");
  }

  @Test
  void pricingCostIncludesEveryTokenClassWeighted() {
    // Mirror the matchmaking baseline shape: $15/M input, $75/M output, plus cache ratios.
    var p = Pricing.anthropicCaching(15.0, 75.0);
    var usage = Usage.of(1_000_000, 1_000_000, 1_000_000, 1_000_000);
    // input  $15.00 + output $75.00 + cache write $18.75 + cache read $1.50 = $110.25
    assertEquals(110_250_000L, p.cost(usage).microUsd());
  }

  @Test
  void pricingCostMatchesMatchmakingBaselineMinusCacheReadDiscount() {
    // hv2-bug2 Issue 1 regression: a representative 24-viewer baseline run with prompt caching
    // shifts a large share of input tokens from "input" (full price) to "cache_read" (0.10x).
    // The pre-fix calculation billed every input token at full price; the post-fix calculation
    // applies the discount to cache reads.
    var p = Pricing.anthropicCaching(15.0, 75.0);

    // No-caching baseline (pre-fix): 12_279_022 input tokens × $15/M + 684_700 × $75/M.
    var baseline = Usage.of(12_279_022, 684_700);
    var baselineCost = p.cost(baseline).microUsd();
    // Integer math: 12_279_022 × 15 / 1 = 184_185_330 microUSD; 684_700 × 75 / 1 = 51_352_500.
    assertEquals(184_185_330L + 51_352_500L, baselineCost, "baseline matches matchmaking trace");

    // Post-fix: assume 80% of input tokens hit cache (typical agent loop with a stable system+
    // tools prefix). cache_read = 9_823_217, cache_create = 0, input = 2_455_805.
    // Total tokens identical; only the class mix changes.
    var withCache =
        Usage.of(
            2_455_805, // uncached input
            684_700, // output
            0, // cache creation (steady state)
            9_823_217); // cache read
    var withCacheCost = p.cost(withCache).microUsd();

    // Cache-aware cost: 2_455_805 × 15 = 36_837_075; 684_700 × 75 = 51_352_500;
    // 9_823_217 × 1_500_000 / 1_000_000 = 14_734_825 (integer trunc of 14_734_825.5).
    var expectedCached = 36_837_075L + 51_352_500L + 14_734_825L;
    assertEquals(expectedCached, withCacheCost, "cache-aware cost includes the 0.10× discount");
    assertTrue(
        withCacheCost < baselineCost,
        "cache-aware billing must be cheaper than no-cache for the same total token mix");
  }

  @Test
  void pricingRejectsNegativeCacheWriteRate() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Pricing(0L, 0L, -1L, 0L));
    assertEquals("cacheWriteMicroUsdPerMillion must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void pricingRejectsNegativeCacheReadRate() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Pricing(0L, 0L, 0L, -1L));
    assertEquals("cacheReadMicroUsdPerMillion must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void pricingOfUsdPerMillionFourArgFactory() {
    var p = Pricing.ofUsdPerMillion(15.0, 75.0, 18.75, 1.5);
    assertEquals(15_000_000L, p.inputMicroUsdPerMillion());
    assertEquals(75_000_000L, p.outputMicroUsdPerMillion());
    assertEquals(18_750_000L, p.cacheWriteMicroUsdPerMillion());
    assertEquals(1_500_000L, p.cacheReadMicroUsdPerMillion());
  }

  @Test
  void pricingTwoArgFactoryProducesIdenticalRatesForCacheClasses() {
    // Verifies the conservative fallback: a caller who hasn't configured cache rates pays the
    // same per-token amount on cache tokens as on uncached input. Caching cannot UNDER-bill
    // relative to the no-cache run.
    var p = Pricing.ofUsdPerMillion(3.0, 15.0);
    assertEquals(p.inputMicroUsdPerMillion(), p.cacheWriteMicroUsdPerMillion());
    assertEquals(p.inputMicroUsdPerMillion(), p.cacheReadMicroUsdPerMillion());
    var usage = Usage.of(100, 100, 100, 100);
    // Per-class integer division: each multiply-then-divide truncates separately. 100 tokens
    // each at the four respective rates (3M, 15M, 3M, 3M microUSD/M) yields 300, 1500, 300, 300
    // microUSD per class — sum 2400 microUSD = $0.0024.
    assertEquals(300L + 1_500L + 300L + 300L, p.cost(usage).microUsd());
    // 1_000_000 tokens of each — clean integer math, no truncation.
    var bigUsage = Usage.of(1_000_000, 1_000_000, 1_000_000, 1_000_000);
    assertEquals(3_000_000L + 15_000_000L + 3_000_000L + 3_000_000L, p.cost(bigUsage).microUsd());
  }
}
