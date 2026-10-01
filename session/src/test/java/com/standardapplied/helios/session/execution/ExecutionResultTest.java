/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ExecutionResultTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var r =
        new ExecutionResult(
            0, "ok\n", "warn\n", Duration.ofMillis(120), false, Map.of("GH_TOKEN", 2));
    assertEquals(0, r.exitCode());
    assertEquals("ok\n", r.stdout());
    assertEquals("warn\n", r.stderr());
    assertEquals(Duration.ofMillis(120), r.duration());
    assertFalse(r.timedOut());
    assertEquals(Map.of("GH_TOKEN", 2), r.secretRedactionCounts());
  }

  @Test
  void totalRedactionsSumsAcrossSecrets() {
    var r =
        new ExecutionResult(0, "", "", Duration.ZERO, false, Map.of("GH_TOKEN", 2, "AWS_KEY", 3));
    assertEquals(5, r.totalRedactions());
  }

  @Test
  void totalRedactionsZeroWhenEmpty() {
    var r = new ExecutionResult(0, "", "", Duration.ZERO, false, Map.of());
    assertEquals(0, r.totalRedactions());
  }

  // ── nulls and validation ─────────────────────────────────────────────────

  @Test
  void stdoutNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionResult(0, null, "", Duration.ZERO, false, Map.of()));
    assertEquals("stdout must not be null", ex.getMessage());
  }

  @Test
  void stderrNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionResult(0, "", null, Duration.ZERO, false, Map.of()));
    assertEquals("stderr must not be null", ex.getMessage());
  }

  @Test
  void durationNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionResult(0, "", "", null, false, Map.of()));
    assertEquals("duration must not be null", ex.getMessage());
  }

  @Test
  void durationNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExecutionResult(0, "", "", Duration.ofMillis(-1), false, Map.of()));
    assertTrue(ex.getMessage().startsWith("duration must not be negative"));
  }

  @Test
  void secretRedactionCountsNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionResult(0, "", "", Duration.ZERO, false, null));
    assertEquals("secretRedactionCounts must not be null", ex.getMessage());
  }

  @Test
  void secretRedactionCountsNullKeyRejected() {
    var counts = new HashMap<String, Integer>();
    counts.put(null, 1);
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionResult(0, "", "", Duration.ZERO, false, counts));
    assertEquals("secretRedactionCounts must not contain null keys", ex.getMessage());
  }

  @Test
  void secretRedactionCountsNullValueRejected() {
    var counts = new HashMap<String, Integer>();
    counts.put("K", null);
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionResult(0, "", "", Duration.ZERO, false, counts));
    assertEquals("secretRedactionCounts must not contain null values", ex.getMessage());
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
    assertEquals(Map.of(), r.secretRedactionCounts());
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
            .withSecretRedactionCounts(Map.of("TOKEN", 1))
            .build();
    assertEquals(7, r.exitCode());
    assertEquals("hello\n", r.stdout());
    assertEquals("warn\n", r.stderr());
    assertEquals(Duration.ofMillis(50), r.duration());
    assertTrue(r.timedOut());
    assertEquals(Map.of("TOKEN", 1), r.secretRedactionCounts());
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

  @Test
  void builderWithSecretRedactionCountsNullRejected() {
    var b = ExecutionResult.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withSecretRedactionCounts(null));
    assertEquals("counts must not be null", ex.getMessage());
  }

  @Test
  void builderWithSecretRedactionCountsNullKeyRejected() {
    var b = ExecutionResult.newBuilder();
    var counts = new HashMap<String, Integer>();
    counts.put(null, 1);
    var ex = assertThrows(NullPointerException.class, () -> b.withSecretRedactionCounts(counts));
    assertEquals("counts must not contain null keys", ex.getMessage());
  }

  @Test
  void builderWithSecretRedactionCountsNullValueRejected() {
    var b = ExecutionResult.newBuilder();
    var counts = new HashMap<String, Integer>();
    counts.put("K", null);
    var ex = assertThrows(NullPointerException.class, () -> b.withSecretRedactionCounts(counts));
    assertEquals("counts must not contain null values", ex.getMessage());
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
    assertTrue(r.secretRedactionCounts().isEmpty());
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
