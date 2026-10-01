/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.runtime.SessionContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ToolContextTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var sc = SessionContext.forTesting("tc-1");
    var ctx = new ToolContext(sc, Duration.ofSeconds(5));
    assertSame(sc, ctx.sessionContext());
    assertEquals(Duration.ofSeconds(5), ctx.deadline());
    assertSame(sc.cancellation(), ctx.cancellation());
  }

  @Test
  void canonicalConstructorRejectsNullSessionContext() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new ToolContext(null, Duration.ofSeconds(1)));
    assertEquals("sessionContext must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullDeadline() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ToolContext(SessionContext.forTesting("x"), null));
    assertEquals("deadline must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNegativeDeadline() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ToolContext(SessionContext.forTesting("x"), Duration.ofMillis(-1)));
    assertTrue(ex.getMessage().startsWith("deadline must not be negative"));
  }

  @Test
  void canonicalConstructorAcceptsZeroDeadline() {
    var ctx = new ToolContext(SessionContext.forTesting("x"), Duration.ZERO);
    assertEquals(Duration.ZERO, ctx.deadline());
  }

  @Test
  void noopReturnsStableSingleton() {
    assertSame(ToolContext.noop(), ToolContext.noop());
  }

  @Test
  void noopHasNonNullSessionContextAndCancellation() {
    var n = ToolContext.noop();
    assertNotNull(n.sessionContext());
    assertNotNull(n.cancellation());
    assertEquals("noop-session", n.sessionContext().sessionId());
  }

  @Test
  void ofFactoryBuildsExpectedContext() {
    var sc = SessionContext.forTesting("from-of");
    var ctx = ToolContext.of(sc, Duration.ofSeconds(2));
    assertSame(sc, ctx.sessionContext());
    assertEquals(Duration.ofSeconds(2), ctx.deadline());
  }

  @Test
  void cancellationAccessorDelegatesToSessionContext() {
    var sc = SessionContext.forTesting("delegate");
    var ctx = ToolContext.of(sc, Duration.ofSeconds(1));
    assertSame(sc.cancellation(), ctx.cancellation());
  }
}
