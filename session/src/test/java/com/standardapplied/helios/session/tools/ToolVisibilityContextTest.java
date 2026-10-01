/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ToolVisibilityContextTest {

  @Test
  void canonicalConstructorRetainsFields() {
    var ctx = new ToolVisibilityContext("sess-1", 7);
    assertEquals("sess-1", ctx.sessionId());
    assertEquals(7, ctx.turnIndex());
  }

  @Test
  void zeroTurnIndexAllowed() {
    var ctx = new ToolVisibilityContext("sess-1", 0);
    assertEquals(0, ctx.turnIndex());
  }

  @Test
  void rejectsNullSessionId() {
    var ex = assertThrows(NullPointerException.class, () -> new ToolVisibilityContext(null, 0));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void rejectsBlankSessionId() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ToolVisibilityContext("  ", 0));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void rejectsNegativeTurnIndex() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> new ToolVisibilityContext("sess", -1));
    assertEquals("turnIndex must be non-negative, got -1", ex.getMessage());
  }
}
