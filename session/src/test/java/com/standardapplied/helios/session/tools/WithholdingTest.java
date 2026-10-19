/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.tools;

import static com.standardapplied.helios.session.test.BindingCalls.withholding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The one filter walking tools apply: skip and count what the call's policy denies. */
final class WithholdingTest {

  private static Withholding denying(String prefix) {
    return Withholding.of(withholding(key -> key.startsWith(prefix)));
  }

  @Test
  void aDeniedKeyIsWithheldAndCounted() {
    var withholding = denying("./secrets");
    assertTrue(withholding.withholds("./secrets"));
    assertTrue(withholding.withholds("./secrets/key"));
    assertFalse(withholding.withholds("./docs"));
    assertEquals("[withheld by permission policy: 2]\n", withholding.note());
  }

  @Test
  void nothingWithheldLeavesNoNote() {
    var withholding = denying("./secrets");
    assertFalse(withholding.withholds("./docs"));
    assertEquals("", withholding.note());
  }

  @Test
  void ofRejectsANullContext() {
    assertEquals(
        "context must not be null",
        assertThrows(NullPointerException.class, () -> Withholding.of(null)).getMessage());
  }
}
