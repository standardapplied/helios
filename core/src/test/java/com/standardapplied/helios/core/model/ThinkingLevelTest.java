/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ThinkingLevelTest {

  @Test
  void allThinkingLevels() {
    assertEquals(ThinkingLevel.NONE, ThinkingLevel.valueOf("NONE"));
    assertEquals(ThinkingLevel.MINIMAL, ThinkingLevel.valueOf("MINIMAL"));
    assertEquals(ThinkingLevel.LOW, ThinkingLevel.valueOf("LOW"));
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.valueOf("MEDIUM"));
    assertEquals(ThinkingLevel.HIGH, ThinkingLevel.valueOf("HIGH"));
    assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.valueOf("XHIGH"));
    assertEquals(ThinkingLevel.MAX, ThinkingLevel.valueOf("MAX"));
    assertEquals(7, ThinkingLevel.values().length);
  }
}
