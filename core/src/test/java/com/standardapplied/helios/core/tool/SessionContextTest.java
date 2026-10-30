/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.CancellationToken;
import java.time.Clock;
import org.junit.jupiter.api.Test;

final class SessionContextTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var token = new CancellationToken();
    var clock = Clock.systemUTC();
    var secrets = new SecretRegistry();
    var ctx = new SessionContext("s1", token, clock, secrets);
    assertEquals("s1", ctx.sessionId());
    assertSame(token, ctx.cancellation());
    assertSame(clock, ctx.clock());
    assertSame(secrets, ctx.secrets());
  }

  @Test
  void canonicalConstructorRejectsNullSessionId() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionContext(
                    null, new CancellationToken(), Clock.systemUTC(), new SecretRegistry()));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsBlankSessionId() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SessionContext(
                    "   ", new CancellationToken(), Clock.systemUTC(), new SecretRegistry()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsEmptySessionId() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SessionContext(
                    "", new CancellationToken(), Clock.systemUTC(), new SecretRegistry()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullCancellation() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new SessionContext("s1", null, Clock.systemUTC(), new SecretRegistry()));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullClock() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new SessionContext("s1", new CancellationToken(), null, new SecretRegistry()));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullSecrets() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new SessionContext("s1", new CancellationToken(), Clock.systemUTC(), null));
    assertEquals("secrets must not be null", ex.getMessage());
  }

  @Test
  void forTestingReturnsFreshNonNullValues() {
    var ctx = SessionContext.forTesting("test-id");
    assertEquals("test-id", ctx.sessionId());
    assertNotNull(ctx.cancellation());
    assertNotNull(ctx.clock());
    assertEquals(0, ctx.secrets().size());
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
