/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ConcurrencyLimitsTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var l = new ConcurrencyLimits(16, 2, 1, 128);
    assertEquals(16, l.maxConcurrentToolCalls());
    assertEquals(2, l.maxConcurrentFileWrites());
    assertEquals(1, l.maxConcurrentExecutions());
    assertEquals(128, l.maxQueuedUserMessages());
  }

  @Test
  void defaultsExposesExpectedValues() {
    var d = ConcurrencyLimits.defaults();
    assertEquals(32, d.maxConcurrentToolCalls());
    assertEquals(4, d.maxConcurrentFileWrites());
    assertEquals(2, d.maxConcurrentExecutions());
    assertEquals(256, d.maxQueuedUserMessages());
  }

  @Test
  void defaultsReturnsSameSingleton() {
    assertSame(ConcurrencyLimits.defaults(), ConcurrencyLimits.defaults());
  }

  // ── maxConcurrentToolCalls ────────────────────────────────────────────────

  @Test
  void maxConcurrentToolCallsZeroRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimits(0, 1, 1, 1));
    assertEquals("maxConcurrentToolCalls must be positive, got 0", ex.getMessage());
  }

  @Test
  void maxConcurrentToolCallsNegativeRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimits(-1, 1, 1, 1));
    assertEquals("maxConcurrentToolCalls must be positive, got -1", ex.getMessage());
  }

  // ── maxConcurrentFileWrites ───────────────────────────────────────────────

  @Test
  void maxConcurrentFileWritesZeroRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimits(1, 0, 1, 1));
    assertEquals("maxConcurrentFileWrites must be positive, got 0", ex.getMessage());
  }

  @Test
  void maxConcurrentFileWritesNegativeRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimits(1, -2, 1, 1));
    assertEquals("maxConcurrentFileWrites must be positive, got -2", ex.getMessage());
  }

  // ── maxConcurrentExecutions ───────────────────────────────────────────────

  @Test
  void maxConcurrentExecutionsZeroRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimits(1, 1, 0, 1));
    assertEquals("maxConcurrentExecutions must be positive, got 0", ex.getMessage());
  }

  @Test
  void maxConcurrentExecutionsNegativeRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimits(1, 1, -3, 1));
    assertEquals("maxConcurrentExecutions must be positive, got -3", ex.getMessage());
  }

  // ── maxQueuedUserMessages ─────────────────────────────────────────────────

  @Test
  void maxQueuedUserMessagesZeroRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimits(1, 1, 1, 0));
    assertEquals("maxQueuedUserMessages must be positive, got 0", ex.getMessage());
  }

  @Test
  void maxQueuedUserMessagesNegativeRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimits(1, 1, 1, -4));
    assertEquals("maxQueuedUserMessages must be positive, got -4", ex.getMessage());
  }
}
