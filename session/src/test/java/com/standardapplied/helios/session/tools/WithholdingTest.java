/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The one filter walking tools apply: skip and count what the call's policy denies. */
final class WithholdingTest {

  private static Withholding denying(String prefix) {
    return Withholding.of(
        new ToolContext(
            SessionContext.forTesting("withholding"),
            new CancellationToken(),
            Duration.ofMinutes(1),
            key -> key.startsWith(prefix)));
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
