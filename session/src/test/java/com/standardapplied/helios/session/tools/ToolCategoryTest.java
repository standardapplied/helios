/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

final class ToolCategoryTest {

  @Test
  void valuesEnumeratesEveryCategory() {
    assertEquals(7, ToolCategory.values().length);
  }

  @Test
  void valueOfRoundTrip() {
    for (var c : ToolCategory.values()) {
      assertSame(c, ToolCategory.valueOf(c.name()));
    }
  }

  @Test
  void expectedCategoriesPresent() {
    assertSame(ToolCategory.READ, ToolCategory.valueOf("READ"));
    assertSame(ToolCategory.WRITE, ToolCategory.valueOf("WRITE"));
    assertSame(ToolCategory.SEARCH, ToolCategory.valueOf("SEARCH"));
    assertSame(ToolCategory.EXECUTION, ToolCategory.valueOf("EXECUTION"));
    assertSame(ToolCategory.CONTROL, ToolCategory.valueOf("CONTROL"));
    assertSame(ToolCategory.NETWORK, ToolCategory.valueOf("NETWORK"));
    assertSame(ToolCategory.DELEGATION, ToolCategory.valueOf("DELEGATION"));
  }
}
