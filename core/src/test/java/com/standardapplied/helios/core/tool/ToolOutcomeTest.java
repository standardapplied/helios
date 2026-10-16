/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ToolOutcomeTest {

  @Test
  void threwRejectsAMissingExceptionType() {
    assertRejects(NullPointerException.class, "exceptionType must not be null", () -> threw(null));
    assertRejects(
        IllegalArgumentException.class, "exceptionType must not be blank", () -> threw(" "));
  }

  @Test
  void timedOutRejectsAMissingOrNonPositiveLimit() {
    assertRejectsAMissingOrNonPositiveLimit(ToolOutcomeTest::timedOut);
  }

  @Test
  void unknownRejectsAMissingOrNonPositiveLimit() {
    assertRejectsAMissingOrNonPositiveLimit(ToolOutcomeTest::unknown);
  }

  @Test
  void rejectedRejectsAMissingHookOrReason() {
    assertRejects(NullPointerException.class, "by must not be null", () -> rejected(null, "r"));
    assertRejects(IllegalArgumentException.class, "by must not be blank", () -> rejected("", "r"));
    assertRejects(
        NullPointerException.class, "reason must not be null", () -> rejected("Hook", null));
    assertRejects(
        IllegalArgumentException.class, "reason must not be blank", () -> rejected("Hook", "\t"));
  }

  @Test
  void validComponentsAreKept() {
    assertEquals("java.lang.Error", new ToolOutcome.Threw("java.lang.Error").exceptionType());
    assertEquals(Duration.ofMillis(1), new ToolOutcome.TimedOut(Duration.ofMillis(1)).limit());
    assertEquals(Duration.ofMillis(2), new ToolOutcome.Unknown(Duration.ofMillis(2)).limit());
    var rejected = new ToolOutcome.Rejected("Hook", "no");
    assertEquals("Hook", rejected.by());
    assertEquals("no", rejected.reason());
  }

  private static void threw(String exceptionType) {
    new ToolOutcome.Threw(exceptionType);
  }

  private static void timedOut(Duration limit) {
    new ToolOutcome.TimedOut(limit);
  }

  private static void unknown(Duration limit) {
    new ToolOutcome.Unknown(limit);
  }

  private static void rejected(String by, String reason) {
    new ToolOutcome.Rejected(by, reason);
  }

  private static void assertRejectsAMissingOrNonPositiveLimit(Consumer<Duration> construction) {
    assertRejects(
        NullPointerException.class, "limit must not be null", () -> construction.accept(null));
    assertRejects(
        IllegalArgumentException.class,
        "limit must be strictly positive, got PT0S",
        () -> construction.accept(Duration.ZERO));
    assertRejects(
        IllegalArgumentException.class,
        "limit must be strictly positive, got PT-1S",
        () -> construction.accept(Duration.ofSeconds(-1)));
  }

  private static void assertRejects(
      Class<? extends RuntimeException> type, String message, Executable construction) {
    assertEquals(message, assertThrows(type, construction).getMessage());
  }
}
