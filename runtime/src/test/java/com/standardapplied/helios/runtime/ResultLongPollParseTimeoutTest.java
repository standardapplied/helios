/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-private {@link ResultLongPoll#parseResultTimeoutSeconds(String)}
 * clamp/fallback logic. The HTTP integration test covers the happy and timeout-elapsed paths; this
 * fixture pins down the edge cases that are awkward to verify through HTTP (huge values, negatives,
 * malformed input).
 */
final class ResultLongPollParseTimeoutTest {

  @Test
  void nullFallsBackToDefault() {
    assertEquals(
        ResultLongPoll.DEFAULT_RESULT_TIMEOUT_SECONDS,
        ResultLongPoll.parseResultTimeoutSeconds(null));
  }

  @Test
  void blankFallsBackToDefault() {
    assertEquals(
        ResultLongPoll.DEFAULT_RESULT_TIMEOUT_SECONDS,
        ResultLongPoll.parseResultTimeoutSeconds("   "));
  }

  @Test
  void malformedFallsBackToDefault() {
    assertEquals(
        ResultLongPoll.DEFAULT_RESULT_TIMEOUT_SECONDS,
        ResultLongPoll.parseResultTimeoutSeconds("abc"));
  }

  @Test
  void zeroIsAccepted() {
    assertEquals(0L, ResultLongPoll.parseResultTimeoutSeconds("0"));
  }

  @Test
  void smallPositiveIsAccepted() {
    assertEquals(5L, ResultLongPoll.parseResultTimeoutSeconds("5"));
  }

  @Test
  void whitespaceAroundDigitsIsTrimmed() {
    assertEquals(7L, ResultLongPoll.parseResultTimeoutSeconds("  7  "));
  }

  @Test
  void negativeClampsToZero() {
    assertEquals(0L, ResultLongPoll.parseResultTimeoutSeconds("-1"));
    assertEquals(0L, ResultLongPoll.parseResultTimeoutSeconds("-9999"));
  }

  @Test
  void aboveCapClampsToMax() {
    assertEquals(
        ResultLongPoll.MAX_RESULT_TIMEOUT_SECONDS,
        ResultLongPoll.parseResultTimeoutSeconds(
            String.valueOf(ResultLongPoll.MAX_RESULT_TIMEOUT_SECONDS + 1)));
    assertEquals(
        ResultLongPoll.MAX_RESULT_TIMEOUT_SECONDS,
        ResultLongPoll.parseResultTimeoutSeconds("99999"));
  }

  @Test
  void exactlyAtCapIsAccepted() {
    assertEquals(
        ResultLongPoll.MAX_RESULT_TIMEOUT_SECONDS,
        ResultLongPoll.parseResultTimeoutSeconds(
            String.valueOf(ResultLongPoll.MAX_RESULT_TIMEOUT_SECONDS)));
  }

  @Test
  void overflowingLongFallsBackToDefault() {
    // "99999999999999999999" cannot parse as long → NumberFormatException → fallback.
    assertEquals(
        ResultLongPoll.DEFAULT_RESULT_TIMEOUT_SECONDS,
        ResultLongPoll.parseResultTimeoutSeconds("99999999999999999999"));
  }
}
