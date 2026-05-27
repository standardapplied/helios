/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.fault.Backoff;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class StreamRetryPolicyTest {

  @Test
  void defaultsThreeAttemptsExponentialWithJitter() {
    var p = StreamRetryPolicy.defaults();
    assertEquals(3, p.maxAttempts());
    assertNotNull(p.backoff());
    assertEquals(0.25, p.jitter(), 0.0);
    assertTrue(p.enabled());
  }

  @Test
  void defaultsReturnsSameSingleton() {
    assertSame(StreamRetryPolicy.defaults(), StreamRetryPolicy.defaults());
  }

  @Test
  void disabledHasSingleAttemptAndZeroBackoff() {
    var p = StreamRetryPolicy.disabled();
    assertEquals(1, p.maxAttempts());
    assertFalse(p.enabled());
    assertEquals(Duration.ZERO, p.nextDelay(1));
  }

  @Test
  void rejectsZeroMaxAttempts() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new StreamRetryPolicy(0, Backoff.fixed(Duration.ofMillis(1)), 0.0));
    assertEquals("maxAttempts must be >= 1, got 0", ex.getMessage());
  }

  @Test
  void rejectsNegativeMaxAttempts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StreamRetryPolicy(-1, Backoff.fixed(Duration.ofMillis(1)), 0.0));
  }

  @Test
  void rejectsNullBackoff() {
    var ex = assertThrows(NullPointerException.class, () -> new StreamRetryPolicy(1, null, 0.0));
    assertEquals("backoff must not be null", ex.getMessage());
  }

  @Test
  void rejectsJitterBelowZero() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new StreamRetryPolicy(1, Backoff.fixed(Duration.ofMillis(1)), -0.1));
    assertEquals("jitter must be in [0.0, 1.0], got -0.1", ex.getMessage());
  }

  @Test
  void rejectsJitterAboveOne() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StreamRetryPolicy(1, Backoff.fixed(Duration.ofMillis(1)), 1.5));
  }

  @Test
  void acceptsJitterAtZeroAndOne() {
    new StreamRetryPolicy(1, Backoff.fixed(Duration.ofMillis(1)), 0.0);
    new StreamRetryPolicy(1, Backoff.fixed(Duration.ofMillis(1)), 1.0);
  }

  @Test
  void nextDelayUsesBackoffAndJitter() {
    var p = new StreamRetryPolicy(5, Backoff.fixed(Duration.ofMillis(50)), 0.0);
    assertEquals(Duration.ofMillis(50), p.nextDelay(1));
    assertEquals(Duration.ofMillis(50), p.nextDelay(2));
  }

  @Test
  void nextDelayRejectsNonPositiveFailedAttempt() {
    var p = new StreamRetryPolicy(5, Backoff.fixed(Duration.ofMillis(50)), 0.0);
    var ex = assertThrows(IllegalArgumentException.class, () -> p.nextDelay(0));
    assertEquals("failedAttempt must be >= 1, got 0", ex.getMessage());
  }

  @Test
  void enabledSingleAttemptIsDisabled() {
    assertFalse(new StreamRetryPolicy(1, Backoff.fixed(Duration.ZERO), 0.0).enabled());
    assertTrue(new StreamRetryPolicy(2, Backoff.fixed(Duration.ZERO), 0.0).enabled());
  }
}
