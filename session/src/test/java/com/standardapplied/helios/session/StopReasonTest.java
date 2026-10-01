/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

final class StopReasonTest {

  @Test
  void valuesEnumeratesEveryReason() {
    assertEquals(6, StopReason.values().length);
  }

  @Test
  void valueOfRoundTripsEveryConstant() {
    for (var r : StopReason.values()) {
      assertSame(r, StopReason.valueOf(r.name()));
    }
  }

  @Test
  void expectedConstantsPresent() {
    assertSame(StopReason.END_TURN, StopReason.valueOf("END_TURN"));
    assertSame(StopReason.TOOL_USE, StopReason.valueOf("TOOL_USE"));
    assertSame(StopReason.MAX_TOKENS, StopReason.valueOf("MAX_TOKENS"));
    assertSame(StopReason.INTERRUPTED, StopReason.valueOf("INTERRUPTED"));
    assertSame(StopReason.REFUSAL, StopReason.valueOf("REFUSAL"));
    assertSame(StopReason.ERROR, StopReason.valueOf("ERROR"));
  }
}
