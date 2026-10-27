/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

final class ToolCategoryTest {

  @Test
  void valuesAreTheFiveCategoriesInOrder() {
    assertArrayEquals(
        new ToolCategory[] {
          ToolCategory.READ,
          ToolCategory.WRITE,
          ToolCategory.EXECUTION,
          ToolCategory.NETWORK,
          ToolCategory.CONTROL
        },
        ToolCategory.values());
  }
}
