/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ToolContextTest {

  private static final SessionContext SESSION = SessionContext.forTesting("tc");
  private static final CancellationToken CALL = new CancellationToken();

  @Test
  void canonicalConstructorKeepsTheCallTokenApartFromTheSessionToken() {
    var ctx = new ToolContext(SESSION, CALL, Duration.ofSeconds(5));
    assertSame(SESSION, ctx.sessionContext());
    assertSame(CALL, ctx.cancellation());
    assertNotSame(SESSION.cancellation(), ctx.cancellation());
    assertEquals(Duration.ofSeconds(5), ctx.deadline());
  }

  @Test
  void canonicalConstructorRejectsNullSessionContext() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new ToolContext(null, CALL, Duration.ofSeconds(1)));
    assertEquals("sessionContext must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullCancellation() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ToolContext(SESSION, null, Duration.ofSeconds(1)));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullDeadline() {
    var ex = assertThrows(NullPointerException.class, () -> new ToolContext(SESSION, CALL, null));
    assertEquals("deadline must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNegativeDeadline() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ToolContext(SESSION, CALL, Duration.ofMillis(-1)));
    assertEquals("deadline must not be negative, got PT-0.001S", ex.getMessage());
  }

  @Test
  void canonicalConstructorAcceptsZeroDeadline() {
    var ctx = new ToolContext(SESSION, CALL, Duration.ZERO);
    assertEquals(Duration.ZERO, ctx.deadline());
  }

  @Test
  void noopReturnsStableSingleton() {
    assertSame(ToolContext.noop(), ToolContext.noop());
  }

  @Test
  void noopUsesItsSessionTokenAsTheCallToken() {
    var n = ToolContext.noop();
    assertEquals("noop-session", n.sessionContext().sessionId());
    assertSame(n.sessionContext().cancellation(), n.cancellation());
    assertEquals(Duration.ofDays(1), n.deadline());
  }
}
