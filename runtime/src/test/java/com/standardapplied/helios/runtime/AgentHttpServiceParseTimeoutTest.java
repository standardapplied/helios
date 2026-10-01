/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-private {@link AgentHttpService#parseResultTimeoutSeconds(String)}
 * clamp/fallback logic. The HTTP integration test covers the happy and timeout-elapsed paths; this
 * fixture pins down the edge cases that are awkward to verify through HTTP (huge values, negatives,
 * malformed input).
 */
final class AgentHttpServiceParseTimeoutTest {

  @Test
  void nullFallsBackToDefault() {
    assertEquals(
        AgentHttpService.DEFAULT_RESULT_TIMEOUT_SECONDS,
        AgentHttpService.parseResultTimeoutSeconds(null));
  }

  @Test
  void blankFallsBackToDefault() {
    assertEquals(
        AgentHttpService.DEFAULT_RESULT_TIMEOUT_SECONDS,
        AgentHttpService.parseResultTimeoutSeconds("   "));
  }

  @Test
  void malformedFallsBackToDefault() {
    assertEquals(
        AgentHttpService.DEFAULT_RESULT_TIMEOUT_SECONDS,
        AgentHttpService.parseResultTimeoutSeconds("abc"));
  }

  @Test
  void zeroIsAccepted() {
    assertEquals(0L, AgentHttpService.parseResultTimeoutSeconds("0"));
  }

  @Test
  void smallPositiveIsAccepted() {
    assertEquals(5L, AgentHttpService.parseResultTimeoutSeconds("5"));
  }

  @Test
  void whitespaceAroundDigitsIsTrimmed() {
    assertEquals(7L, AgentHttpService.parseResultTimeoutSeconds("  7  "));
  }

  @Test
  void negativeClampsToZero() {
    assertEquals(0L, AgentHttpService.parseResultTimeoutSeconds("-1"));
    assertEquals(0L, AgentHttpService.parseResultTimeoutSeconds("-9999"));
  }

  @Test
  void aboveCapClampsToMax() {
    assertEquals(
        AgentHttpService.MAX_RESULT_TIMEOUT_SECONDS,
        AgentHttpService.parseResultTimeoutSeconds(
            String.valueOf(AgentHttpService.MAX_RESULT_TIMEOUT_SECONDS + 1)));
    assertEquals(
        AgentHttpService.MAX_RESULT_TIMEOUT_SECONDS,
        AgentHttpService.parseResultTimeoutSeconds("99999"));
  }

  @Test
  void exactlyAtCapIsAccepted() {
    assertEquals(
        AgentHttpService.MAX_RESULT_TIMEOUT_SECONDS,
        AgentHttpService.parseResultTimeoutSeconds(
            String.valueOf(AgentHttpService.MAX_RESULT_TIMEOUT_SECONDS)));
  }

  @Test
  void overflowingLongFallsBackToDefault() {
    // "99999999999999999999" cannot parse as long → NumberFormatException → fallback.
    assertEquals(
        AgentHttpService.DEFAULT_RESULT_TIMEOUT_SECONDS,
        AgentHttpService.parseResultTimeoutSeconds("99999999999999999999"));
  }
}
