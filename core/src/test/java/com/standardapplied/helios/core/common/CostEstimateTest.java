/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CostEstimateTest {

  @Test
  void canonicalConstructorReadsMicroUsdBack() {
    var cost = new CostEstimate(1_230_000L);
    assertEquals(1_230_000L, cost.microUsd());
  }

  @Test
  void zeroIsAllowed() {
    var cost = new CostEstimate(0L);
    assertEquals(0L, cost.microUsd());
  }

  @Test
  void zeroFactoryReturnsSharedInstance() {
    assertSame(CostEstimate.zero(), CostEstimate.zero());
    assertEquals(0L, CostEstimate.zero().microUsd());
  }

  @Test
  void ofMicroUsdFactoryAccepts() {
    var cost = CostEstimate.ofMicroUsd(50_000L);
    assertEquals(50_000L, cost.microUsd());
  }

  @Test
  void ofMicroUsdZeroReturnsSharedZero() {
    assertSame(CostEstimate.zero(), CostEstimate.ofMicroUsd(0L));
  }

  @Test
  void ofMicroUsdRejectsNegative() {
    var ex = assertThrows(IllegalArgumentException.class, () -> CostEstimate.ofMicroUsd(-1L));
    assertEquals("microUsd must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void ofUsdFactoryRoundsHalfUp() {
    assertEquals(50_000L, CostEstimate.ofUsd(0.05).microUsd());
    assertEquals(1L, CostEstimate.ofUsd(0.0000005).microUsd());
    assertEquals(0L, CostEstimate.ofUsd(0.0000004).microUsd());
  }

  @Test
  void ofUsdAcceptsZero() {
    assertSame(CostEstimate.zero(), CostEstimate.ofUsd(0.0));
  }

  @Test
  void canonicalConstructorRejectsNegative() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new CostEstimate(-1L));
    assertEquals("microUsd must be non-negative, got -1", ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        -1.0,
        -1e-7,
        1e300,
        0x1p63 / 1e6
      })
  void ofUsdRejectsAnAmountThatIsNotAFiniteNonNegativeLongOfMicroUsd(double usd) {
    var ex = assertThrows(IllegalArgumentException.class, () -> CostEstimate.ofUsd(usd));
    assertEquals(
        "USD amount must be finite, non-negative and fit in a long of micro-USD, got " + usd,
        ex.getMessage());
  }

  @Test
  void ofUsdAcceptsAnAmountNearTheLongBound() {
    assertEquals(9_200_000_000_000_000_000L, CostEstimate.ofUsd(9.2e12).microUsd());
  }

  @Test
  void plusAddsTwoCosts() {
    var sum = new CostEstimate(1_500_000L).plus(new CostEstimate(2_250_000L));
    assertEquals(3_750_000L, sum.microUsd());
  }

  @Test
  void plusWithZeroIsIdentity() {
    var c = new CostEstimate(100_000L);
    assertEquals(c.microUsd(), c.plus(CostEstimate.zero()).microUsd());
  }

  @Test
  void plusNullThrowsNullPointerException() {
    var c = new CostEstimate(100_000L);
    var ex = assertThrows(NullPointerException.class, () -> c.plus(null));
    assertEquals("other must not be null", ex.getMessage());
  }

  @Test
  void plusOverflowThrowsArithmeticException() {
    var c = new CostEstimate(Long.MAX_VALUE);
    assertThrows(ArithmeticException.class, () -> c.plus(new CostEstimate(1L)));
  }

  @Test
  void usdAccessorReturnsBigDecimalAtScale6() {
    var cost = new CostEstimate(1_500_000L);
    assertEquals(new BigDecimal("1.500000"), cost.usd());
  }

  @Test
  void usdAccessorForZero() {
    assertEquals(new BigDecimal("0.000000"), CostEstimate.zero().usd());
  }

  @Test
  void formatUsdRoundsToTwoDecimals() {
    assertEquals("$1.50", new CostEstimate(1_500_000L).formatUsd());
    assertEquals("$0.01", new CostEstimate(5_000L).formatUsd());
    assertEquals("$0.00", CostEstimate.zero().formatUsd());
    // 0.005 → rounds half-up to 0.01
    assertEquals("$0.01", new CostEstimate(5_000L).formatUsd());
  }
}
