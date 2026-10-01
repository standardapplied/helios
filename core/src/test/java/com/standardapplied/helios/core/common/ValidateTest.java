/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ValidateTest {

  @Test
  void notBlankReturnsValueWhenSet() {
    assertEquals("hello", Validate.notBlank("name", "hello"));
  }

  @Test
  void notBlankReturnsSameInstance() {
    var s = "preserve-identity";
    assertSame(s, Validate.notBlank("name", s));
  }

  @Test
  void notBlankThrowsNpeOnNull() {
    var ex = assertThrows(NullPointerException.class, () -> Validate.notBlank("script", null));
    assertEquals("script must not be null", ex.getMessage());
  }

  @Test
  void notBlankThrowsIaeOnEmpty() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Validate.notBlank("script", ""));
    assertEquals("script must not be blank", ex.getMessage());
  }

  @Test
  void notBlankThrowsIaeOnWhitespaceOnly() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> Validate.notBlank("script", "  \t\n"));
    assertEquals("script must not be blank", ex.getMessage());
  }

  @Test
  void positiveReturnsValueWhenAboveZero() {
    assertEquals(1, Validate.positive("n", 1));
    assertEquals(Integer.MAX_VALUE, Validate.positive("n", Integer.MAX_VALUE));
  }

  @Test
  void positiveThrowsOnZero() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Validate.positive("n", 0));
    assertEquals("n must be strictly positive, got 0", ex.getMessage());
  }

  @Test
  void positiveThrowsOnNegative() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Validate.positive("n", -5));
    assertEquals("n must be strictly positive, got -5", ex.getMessage());
  }

  @Test
  void atLeastReturnsValueAtBound() {
    assertEquals(1024, Validate.atLeast("bytes", 1024, 1024));
  }

  @Test
  void atLeastReturnsValueAboveBound() {
    assertEquals(2048, Validate.atLeast("bytes", 2048, 1024));
  }

  @Test
  void atLeastThrowsBelowBound() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> Validate.atLeast("bytes", 512, 1024));
    assertEquals("bytes must be at least 1024, got 512", ex.getMessage());
  }

  @Test
  void positiveDurationReturnsValue() {
    var d = Duration.ofSeconds(5);
    assertSame(d, Validate.positiveDuration("timeout", d));
  }

  @Test
  void positiveDurationThrowsOnNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> Validate.positiveDuration("timeout", null));
    assertEquals("timeout must not be null", ex.getMessage());
  }

  @Test
  void positiveDurationThrowsOnZero() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Validate.positiveDuration("timeout", Duration.ZERO));
    assertEquals("timeout must be strictly positive, got PT0S", ex.getMessage());
  }

  @Test
  void positiveDurationThrowsOnNegative() {
    var d = Duration.ofMillis(-1);
    var ex =
        assertThrows(IllegalArgumentException.class, () -> Validate.positiveDuration("timeout", d));
    assertEquals("timeout must be strictly positive, got PT-0.001S", ex.getMessage());
  }

  @Test
  void nonNegativeDurationAcceptsZero() {
    assertEquals(Duration.ZERO, Validate.nonNegativeDuration("duration", Duration.ZERO));
  }

  @Test
  void nonNegativeDurationAcceptsPositive() {
    var d = Duration.ofMillis(500);
    assertSame(d, Validate.nonNegativeDuration("duration", d));
  }

  @Test
  void nonNegativeDurationThrowsOnNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> Validate.nonNegativeDuration("duration", null));
    assertEquals("duration must not be null", ex.getMessage());
  }

  @Test
  void nonNegativeDurationThrowsOnNegative() {
    var d = Duration.ofMillis(-1);
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> Validate.nonNegativeDuration("duration", d));
    assertEquals("duration must not be negative, got PT-0.001S", ex.getMessage());
  }
}
