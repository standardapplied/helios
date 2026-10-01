/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A non-negative USD cost accumulated across a session's model calls and tool invocations.
 *
 * <p>Storage is {@code long} in {@link #MICRO_USD_PER_USD micro-dollars} (1 microUSD = $10⁻⁶), the
 * Stripe-style integer-currency pattern: fixed-precision math on the hot path, no {@code
 * BigDecimal} allocation per turn, no floating-point drift. The maximum representable value is
 * {@code Long.MAX_VALUE} microUSD ≈ $9.2 trillion — comfortably above any realistic session spend.
 *
 * <p>For display or comparison ergonomics, {@link #usd()} converts back to {@link BigDecimal} at
 * scale 6.
 *
 * @param microUsd the cost expressed in micro-dollars; non-negative
 */
public record CostEstimate(long microUsd) {

  /** One US dollar in micro-USD. Use to convert between {@code microUsd} and full-dollar units. */
  public static final long MICRO_USD_PER_USD = 1_000_000L;

  private static final CostEstimate ZERO = new CostEstimate(0L);

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if {@code microUsd} is negative
   */
  public CostEstimate {
    if (microUsd < 0L) {
      throw new IllegalArgumentException("microUsd must be non-negative, got " + microUsd);
    }
  }

  /**
   * Returns the shared zero-cost singleton.
   *
   * @return a {@code CostEstimate} with {@code microUsd == 0}
   */
  public static CostEstimate zero() {
    return ZERO;
  }

  /**
   * Build a {@code CostEstimate} from a raw micro-dollar count. Production callers prefer this over
   * {@link #ofUsd(double)}.
   *
   * @param microUsd the cost in micro-dollars; non-negative
   * @return a {@code CostEstimate}
   * @throws IllegalArgumentException if {@code microUsd} is negative
   */
  public static CostEstimate ofMicroUsd(long microUsd) {
    return microUsd == 0L ? ZERO : new CostEstimate(microUsd);
  }

  /**
   * Build a {@code CostEstimate} from a {@code double} dollar amount. Convenience for tests and
   * scripts; production code should prefer {@link #ofMicroUsd(long)} to keep arithmetic exact.
   *
   * <p>Fractional micro-dollars are rounded half-up.
   *
   * @param usd the dollar amount; must be non-negative
   * @return a {@code CostEstimate}
   */
  public static CostEstimate ofUsd(double usd) {
    return ofMicroUsd(Math.round(usd * MICRO_USD_PER_USD));
  }

  /**
   * Sum this cost with another and return a fresh {@code CostEstimate}.
   *
   * @param other the cost to add; non-null
   * @return a new {@code CostEstimate} whose {@code microUsd} is {@code this.microUsd +
   *     other.microUsd}
   * @throws NullPointerException if {@code other} is null
   * @throws ArithmeticException on overflow (sum exceeds {@link Long#MAX_VALUE})
   */
  public CostEstimate plus(CostEstimate other) {
    if (other == null) {
      throw new NullPointerException("other must not be null");
    }
    return ofMicroUsd(Math.addExact(this.microUsd, other.microUsd));
  }

  /**
   * Return the cost as a {@link BigDecimal} dollar amount at scale 6 (one entry per micro-dollar).
   * Use for display, JSON serialization, or comparison against decimal limits.
   *
   * @return the cost in dollars, exact at scale 6
   */
  public BigDecimal usd() {
    return BigDecimal.valueOf(microUsd, 6);
  }

  /**
   * Pretty-printed dollar string, fixed at two decimal places — suitable for human-facing surfaces
   * (logs, dashboards, error messages).
   *
   * @return the cost as {@code "$X.XX"}
   */
  public String formatUsd() {
    return "$" + usd().setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
