/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

  @Test
  void fixedBackoffConstantDelay() {
    var backoff = Backoff.fixed(Duration.ofSeconds(1));

    assertEquals(Duration.ofSeconds(1), backoff.delay(1));
    assertEquals(Duration.ofSeconds(1), backoff.delay(2));
    assertEquals(Duration.ofSeconds(1), backoff.delay(3));
    assertEquals(Duration.ofSeconds(1), backoff.delay(10));
  }

  @Test
  void fixedBackoffWithJitter() {
    var backoff = Backoff.fixed(Duration.ofSeconds(1));

    for (int i = 0; i < 100; i++) {
      var delay = backoff.delay(1, 0.1);
      assertTrue(delay.toMillis() >= 900, "Delay too small: " + delay);
      assertTrue(delay.toMillis() <= 1100, "Delay too large: " + delay);
    }
  }

  @Test
  void exponentialBackoffIncreasesDelay() {
    var backoff = Backoff.exponential(Duration.ofMillis(100), 2.0);

    assertEquals(Duration.ofMillis(100), backoff.delay(1));
    assertEquals(Duration.ofMillis(200), backoff.delay(2));
    assertEquals(Duration.ofMillis(400), backoff.delay(3));
    assertEquals(Duration.ofMillis(800), backoff.delay(4));
  }

  @Test
  void exponentialBackoffRespectsCap() {
    var backoff = Backoff.exponential(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5));

    assertEquals(Duration.ofSeconds(1), backoff.delay(1));
    assertEquals(Duration.ofSeconds(2), backoff.delay(2));
    assertEquals(Duration.ofSeconds(4), backoff.delay(3));
    assertEquals(Duration.ofSeconds(5), backoff.delay(4));
    assertEquals(Duration.ofSeconds(5), backoff.delay(5));
    assertEquals(Duration.ofSeconds(5), backoff.delay(10));
  }

  @Test
  void exponentialBackoffDefaultMaxDelay() {
    var backoff = Backoff.exponential(Duration.ofMinutes(1), 2.0);

    assertEquals(Duration.ofMinutes(1), backoff.delay(1));
    assertEquals(Duration.ofMinutes(2), backoff.delay(2));
    assertEquals(Duration.ofMinutes(4), backoff.delay(3));
    assertEquals(Duration.ofMinutes(5), backoff.delay(4));
    assertEquals(Duration.ofMinutes(5), backoff.delay(100));
  }

  @Test
  void exponentialBackoffWithJitter() {
    var backoff = Backoff.exponential(Duration.ofSeconds(1), 2.0);

    for (int i = 0; i < 100; i++) {
      var delay = backoff.delay(2, 0.1);
      assertTrue(delay.toMillis() >= 1800, "Delay too small: " + delay);
      assertTrue(delay.toMillis() <= 2200, "Delay too large: " + delay);
    }
  }

  @Test
  void zeroJitterNoRandomness() {
    var backoff = Backoff.fixed(Duration.ofSeconds(1));

    assertEquals(Duration.ofSeconds(1), backoff.delay(1, 0.0));
    assertEquals(Duration.ofSeconds(1), backoff.delay(1, -0.5));
  }

  @Test
  void jitterCappedAtOne() {
    var backoff = Backoff.fixed(Duration.ofSeconds(1));

    for (int i = 0; i < 100; i++) {
      var delay = backoff.delay(1, 2.0);
      assertTrue(delay.toMillis() >= 0, "Delay negative: " + delay);
      assertTrue(delay.toMillis() <= 2000, "Delay too large: " + delay);
    }
  }

  @Test
  void patternMatchingOnBackoff() {
    Backoff fixed = Backoff.fixed(Duration.ofSeconds(1));
    Backoff exponential = Backoff.exponential(Duration.ofMillis(100), 2.0);

    var fixedResult =
        switch (fixed) {
          case Backoff.Fixed(var d) -> "fixed: " + d;
          case Backoff.Exponential(var init, var mult, var max) -> "exponential";
        };

    var expResult =
        switch (exponential) {
          case Backoff.Fixed(var d) -> "fixed";
          case Backoff.Exponential(var init, var mult, var max) -> "exp: " + mult;
        };

    assertEquals("fixed: PT1S", fixedResult);
    assertEquals("exp: 2.0", expResult);
  }

  @Test
  void fixedBackoffRecordAccessors() {
    var backoff = Backoff.fixed(Duration.ofMillis(500));

    assertEquals(Duration.ofMillis(500), backoff.delay());
  }

  @Test
  void exponentialBackoffRecordAccessors() {
    var backoff = Backoff.exponential(Duration.ofMillis(100), 1.5, Duration.ofSeconds(10));

    assertEquals(Duration.ofMillis(100), backoff.initialDelay());
    assertEquals(1.5, backoff.multiplier());
    assertEquals(Duration.ofSeconds(10), backoff.maxDelay());
  }

  @Test
  void fixedRejectsNullDelay() {
    assertThrows(IllegalArgumentException.class, () -> Backoff.fixed(null));
  }

  @Test
  void fixedRejectsNegativeDelay() {
    assertThrows(IllegalArgumentException.class, () -> Backoff.fixed(Duration.ofMillis(-1)));
  }

  @Test
  void fixedAllowsZeroDelay() {
    var backoff = Backoff.fixed(Duration.ZERO);
    assertEquals(Duration.ZERO, backoff.delay(1));
  }

  @Test
  void exponentialRejectsNullInitialDelay() {
    assertThrows(IllegalArgumentException.class, () -> Backoff.exponential(null, 2.0));
  }

  @Test
  void exponentialRejectsNegativeInitialDelay() {
    assertThrows(
        IllegalArgumentException.class, () -> Backoff.exponential(Duration.ofMillis(-1), 2.0));
  }

  @Test
  void exponentialRejectsMultiplierLessThanOne() {
    assertThrows(
        IllegalArgumentException.class, () -> Backoff.exponential(Duration.ofMillis(100), 0.5));
  }

  @Test
  void exponentialRejectsNullMaxDelay() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Backoff.exponential(Duration.ofMillis(100), 2.0, null));
  }

  @Test
  void exponentialRejectsNegativeMaxDelay() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Backoff.exponential(Duration.ofMillis(100), 2.0, Duration.ofMillis(-1)));
  }
}
