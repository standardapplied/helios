/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import org.junit.jupiter.api.Test;

final class SessionContextTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var token = new CancellationToken();
    var clock = Clock.systemUTC();
    var ctx = new SessionContext("s1", token, clock);
    assertEquals("s1", ctx.sessionId());
    assertSame(token, ctx.cancellation());
    assertSame(clock, ctx.clock());
  }

  @Test
  void canonicalConstructorRejectsNullSessionId() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new SessionContext(null, new CancellationToken(), Clock.systemUTC()));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsBlankSessionId() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SessionContext("   ", new CancellationToken(), Clock.systemUTC()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsEmptySessionId() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SessionContext("", new CancellationToken(), Clock.systemUTC()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullCancellation() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new SessionContext("s1", null, Clock.systemUTC()));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullClock() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new SessionContext("s1", new CancellationToken(), null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void forTestingReturnsFreshNonNullValues() {
    var ctx = SessionContext.forTesting("test-id");
    assertEquals("test-id", ctx.sessionId());
    assertNotNull(ctx.cancellation());
    assertNotNull(ctx.clock());
  }

  @Test
  void forTestingRejectsNullId() {
    var ex = assertThrows(NullPointerException.class, () -> SessionContext.forTesting(null));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void forTestingRejectsBlankId() {
    var ex = assertThrows(IllegalArgumentException.class, () -> SessionContext.forTesting(""));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void forTestingProducesUncancelledToken() {
    var ctx = SessionContext.forTesting("alive");
    assertEquals(false, ctx.cancellation().isCancelled());
  }
}
