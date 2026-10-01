/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.time.Duration;
import java.util.Objects;

/**
 * Concise validators for builder setters and record compact constructors. Every helper takes the
 * parameter {@code name} as its first argument and the candidate {@code value} as the second; on
 * accept the value is returned for chaining ({@code this.x = Validate.notBlank("x", x);}), on
 * reject a {@link NullPointerException} or {@link IllegalArgumentException} is thrown with a
 * message naming the offending parameter.
 *
 * <p>Centralising these check patterns shrinks the repeated {@code Objects.requireNonNull(...) + if
 * (Strings.isBlank(...)) throw new IllegalArgumentException(...)} bodies that otherwise grow
 * proportionally with the number of builder setters. Each setter collapses to a single statement,
 * the error messages stay uniform across the framework, and the validators themselves carry the
 * exhaustive unit coverage so each call site can skip the test cases for "null name throws" /
 * "blank value throws" / "zero throws" branches.
 */
public final class Validate {

  private Validate() {}

  /**
   * Returns {@code value} if non-null and not {@link String#isBlank blank}; otherwise throws.
   *
   * @param name parameter name to embed in the error message; non-null
   * @param value the candidate
   * @return the validated value
   * @throws NullPointerException if {@code value} is null
   * @throws IllegalArgumentException if {@code value} is blank
   */
  public static String notBlank(String name, String value) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  /**
   * Returns {@code value} if strictly positive ({@code value > 0}); otherwise throws.
   *
   * @param name parameter name to embed in the error message; non-null
   * @param value the candidate
   * @return the validated value
   * @throws IllegalArgumentException if {@code value <= 0}
   */
  public static int positive(String name, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be strictly positive, got " + value);
    }
    return value;
  }

  /**
   * Returns {@code value} if at least {@code min}; otherwise throws.
   *
   * @param name parameter name to embed in the error message; non-null
   * @param value the candidate
   * @param min inclusive lower bound
   * @return the validated value
   * @throws IllegalArgumentException if {@code value < min}
   */
  public static int atLeast(String name, int value, int min) {
    if (value < min) {
      throw new IllegalArgumentException(name + " must be at least " + min + ", got " + value);
    }
    return value;
  }

  /**
   * Returns {@code value} if non-null and strictly positive ({@code !isZero && !isNegative});
   * otherwise throws.
   *
   * @param name parameter name to embed in the error message; non-null
   * @param value the candidate
   * @return the validated value
   * @throws NullPointerException if {@code value} is null
   * @throws IllegalArgumentException if {@code value} is zero or negative
   */
  public static Duration positiveDuration(String name, Duration value) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be strictly positive, got " + value);
    }
    return value;
  }

  /**
   * Returns {@code value} if non-null and not negative; otherwise throws. Zero is permitted —
   * intended for elapsed-duration fields that may legitimately be {@link Duration#ZERO} when a call
   * refused before doing any work.
   *
   * @param name parameter name to embed in the error message; non-null
   * @param value the candidate
   * @return the validated value
   * @throws NullPointerException if {@code value} is null
   * @throws IllegalArgumentException if {@code value} is negative
   */
  public static Duration nonNegativeDuration(String name, Duration value) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative, got " + value);
    }
    return value;
  }
}
