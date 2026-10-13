/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ExecutionResultTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var r = new ExecutionResult(0, "ok\n", "warn\n", Duration.ofMillis(120), false);
    assertEquals(0, r.exitCode());
    assertEquals("ok\n", r.stdout());
    assertEquals("warn\n", r.stderr());
    assertEquals(Duration.ofMillis(120), r.duration());
    assertFalse(r.timedOut());
  }

  // ── nulls and validation ─────────────────────────────────────────────────

  @Test
  void stdoutNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionResult(0, null, "", Duration.ZERO, false));
    assertEquals("stdout must not be null", ex.getMessage());
  }

  @Test
  void stderrNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionResult(0, "", null, Duration.ZERO, false));
    assertEquals("stderr must not be null", ex.getMessage());
  }

  @Test
  void durationNullRejected() {
    var ex =
        assertThrows(NullPointerException.class, () -> new ExecutionResult(0, "", "", null, false));
    assertEquals("duration must not be null", ex.getMessage());
  }

  @Test
  void durationNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExecutionResult(0, "", "", Duration.ofMillis(-1), false));
    assertTrue(ex.getMessage().startsWith("duration must not be negative"));
  }

  // ── Builder ───────────────────────────────────────────────────────────────

  @Test
  void builderDefaults() {
    var r = ExecutionResult.newBuilder().build();
    assertEquals(0, r.exitCode());
    assertEquals("", r.stdout());
    assertEquals("", r.stderr());
    assertEquals(Duration.ZERO, r.duration());
    assertFalse(r.timedOut());
  }

  @Test
  void builderWithAllOptions() {
    var r =
        ExecutionResult.newBuilder()
            .withExitCode(7)
            .withStdout("hello\n")
            .withStderr("warn\n")
            .withDuration(Duration.ofMillis(50))
            .withTimedOut(true)
            .build();
    assertEquals(7, r.exitCode());
    assertEquals("hello\n", r.stdout());
    assertEquals("warn\n", r.stderr());
    assertEquals(Duration.ofMillis(50), r.duration());
    assertTrue(r.timedOut());
  }

  @Test
  void builderWithStdoutNullRejected() {
    var b = ExecutionResult.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withStdout(null));
    assertEquals("stdout must not be null", ex.getMessage());
  }

  @Test
  void builderWithStderrNullRejected() {
    var b = ExecutionResult.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withStderr(null));
    assertEquals("stderr must not be null", ex.getMessage());
  }

  @Test
  void builderWithDurationNullRejected() {
    var b = ExecutionResult.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withDuration(null));
    assertEquals("duration must not be null", ex.getMessage());
  }

  @Test
  void builderWithDurationNegativeRejected() {
    var b = ExecutionResult.newBuilder();
    var ex =
        assertThrows(IllegalArgumentException.class, () -> b.withDuration(Duration.ofMillis(-1)));
    assertTrue(ex.getMessage().startsWith("duration must not be negative"));
  }

  // ── refusal factory ──────────────────────────────────────────────────────

  @Test
  void refusalProducesCanonicalRefusalShape() {
    var r = ExecutionResult.refusal("runtime PYTHON not supported");
    assertEquals(-1, r.exitCode());
    assertEquals("", r.stdout());
    assertEquals("runtime PYTHON not supported", r.stderr());
    assertEquals(Duration.ZERO, r.duration());
    assertFalse(r.timedOut());
  }

  @Test
  void refusalRejectsNullStderr() {
    var ex = assertThrows(NullPointerException.class, () -> ExecutionResult.refusal(null));
    assertEquals("stderr must not be null", ex.getMessage());
  }

  @Test
  void refusalRejectsBlankStderr() {
    var ex = assertThrows(IllegalArgumentException.class, () -> ExecutionResult.refusal("   "));
    assertEquals("stderr must not be blank", ex.getMessage());
  }
}
