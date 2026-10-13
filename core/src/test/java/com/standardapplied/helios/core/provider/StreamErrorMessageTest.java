/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StreamErrorMessageTest {

  @Test
  void joinsTheSummaryWithEachPartPresent() {
    assertEquals("Failed: code: detail", StreamErrorMessage.of("Failed", "code", "detail"));
    assertEquals("Failed: code", StreamErrorMessage.of("Failed", "code", null));
    assertEquals("Failed: detail", StreamErrorMessage.of("Failed", null, "detail"));
    assertEquals("Failed", StreamErrorMessage.of("Failed", null, null));
  }

  @Test
  void rejectsAMissingSummary() {
    var e = assertThrows(NullPointerException.class, () -> StreamErrorMessage.of(null, "c", "m"));

    assertEquals("summary must not be null", e.getMessage());
  }
}
