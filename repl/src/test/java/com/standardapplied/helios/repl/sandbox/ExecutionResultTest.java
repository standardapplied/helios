/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExecutionResultTest {

  @Test
  void constructorSetsFields() {
    var result = new ExecutionResult("out", "err", 1, "submitted");
    assertEquals("out", result.stdout());
    assertEquals("err", result.stderr());
    assertEquals(1, result.exitCode());
    assertEquals("submitted", result.submitted());
  }

  @Test
  void nullStdoutDefaultsToEmpty() {
    var result = new ExecutionResult(null, "err", 0, null);
    assertEquals("", result.stdout());
  }

  @Test
  void nullStderrDefaultsToEmpty() {
    var result = new ExecutionResult("out", null, 0, null);
    assertEquals("", result.stderr());
  }

  @Test
  void succeededWhenExitCodeZero() {
    assertTrue(ExecutionResult.success("ok").succeeded());
  }

  @Test
  void succeededFalseWhenNonZero() {
    assertFalse(ExecutionResult.failure("err").succeeded());
  }

  @Test
  void hasSubmittedValueTrue() {
    assertTrue(ExecutionResult.success("ok", "answer").hasSubmittedValue());
  }

  @Test
  void hasSubmittedValueFalse() {
    assertFalse(ExecutionResult.success("ok").hasSubmittedValue());
  }

  @Test
  void successFactory() {
    var result = ExecutionResult.success("hello");
    assertEquals("hello", result.stdout());
    assertEquals("", result.stderr());
    assertEquals(0, result.exitCode());
    assertNull(result.submitted());
  }

  @Test
  void successWithSubmittedFactory() {
    var result = ExecutionResult.success("out", "answer");
    assertEquals("out", result.stdout());
    assertEquals("answer", result.submitted());
    assertEquals(0, result.exitCode());
  }

  @Test
  void failureFactory() {
    var result = ExecutionResult.failure("error msg");
    assertEquals("", result.stdout());
    assertEquals("error msg", result.stderr());
    assertEquals(1, result.exitCode());
  }

  @Test
  void failureWithExitCodeFactory() {
    var result = ExecutionResult.failure("oom", 137);
    assertEquals("", result.stdout());
    assertEquals("oom", result.stderr());
    assertEquals(137, result.exitCode());
  }

  @Test
  void builderDefaults() {
    var result = ExecutionResult.newBuilder().build();
    assertEquals("", result.stdout());
    assertEquals("", result.stderr());
    assertEquals(0, result.exitCode());
    assertNull(result.submitted());
  }

  @Test
  void builderAllOptions() {
    var result =
        ExecutionResult.newBuilder()
            .withStdout("output")
            .withStderr("warning")
            .withExitCode(2)
            .withSubmitted("final")
            .build();
    assertEquals("output", result.stdout());
    assertEquals("warning", result.stderr());
    assertEquals(2, result.exitCode());
    assertEquals("final", result.submitted());
  }

  @Test
  void durationDefaultsToZeroForLegacyConstructors() {
    assertEquals(Duration.ZERO, new ExecutionResult("o", "e", 0, null).duration());
    assertEquals(
        Duration.ZERO,
        new ExecutionResult("o", "e", 0, null, java.util.Map.of()).duration(),
        "the 5-arg convenience must default duration to ZERO");
    assertEquals(
        Duration.ZERO,
        new ExecutionResult("code", "o", "e", 0, null, java.util.Map.of()).duration(),
        "the 6-arg convenience must default duration to ZERO");
  }

  @Test
  void durationDefaultsToZeroWhenCanonicalReceivesNull() {
    var result = new ExecutionResult("c", "o", "e", 0, null, java.util.Map.of(), null);
    assertEquals(Duration.ZERO, result.duration());
  }

  @Test
  void durationDefaultsToZeroWhenNegative() {
    var result =
        new ExecutionResult("c", "o", "e", 0, null, java.util.Map.of(), Duration.ofMillis(-5));
    assertEquals(
        Duration.ZERO,
        result.duration(),
        "negative durations are nonsensical (clock skew, bad data) and must be normalized");
  }

  @Test
  void durationPreservedWhenPositive() {
    var d = Duration.ofMillis(123);
    var result = new ExecutionResult("c", "o", "e", 0, null, java.util.Map.of(), d);
    assertEquals(d, result.duration());
  }

  @Test
  void builderWithDuration() {
    var d = Duration.ofMillis(42);
    var result = ExecutionResult.newBuilder().withDuration(d).build();
    assertEquals(d, result.duration());
  }

  @Test
  void builderWithNullDurationDefaultsToZero() {
    var result = ExecutionResult.newBuilder().withDuration(null).build();
    assertEquals(Duration.ZERO, result.duration());
  }

  @Test
  void withDurationReturnsNewInstancePreservingOtherFields() {
    var original =
        new ExecutionResult(
            "code", "stdout", "stderr", 7, "submitted", java.util.Map.of("v", "1"), Duration.ZERO);
    var copy = original.withDuration(Duration.ofMillis(50));

    assertNotSame(original, copy, "withDuration must return a new instance (record immutability)");
    assertEquals(Duration.ofMillis(50), copy.duration());
    assertEquals("code", copy.executedCode());
    assertEquals("stdout", copy.stdout());
    assertEquals("stderr", copy.stderr());
    assertEquals(7, copy.exitCode());
    assertEquals("submitted", copy.submitted());
    assertEquals(java.util.Map.of("v", "1"), copy.bindings());
  }

  @Test
  void withDurationNormalizesNullToZero() {
    var original = new ExecutionResult("o", "e", 0, null);
    var copy = original.withDuration(null);
    assertEquals(Duration.ZERO, copy.duration());
  }

  @Test
  void withDurationOnZeroDurationIsBenign() {
    var original = new ExecutionResult("o", "e", 0, null);
    var copy = original.withDuration(Duration.ZERO);
    // The instance is new (records have value semantics — withDuration always rebuilds), but
    // both share Duration.ZERO. The point of this test is that the rebuild doesn't blow up on
    // zero, since Duration.ZERO is the legitimate default.
    assertEquals(Duration.ZERO, copy.duration());
    assertNotSame(original, copy);
  }

  @Test
  void successFactoryHasZeroDuration() {
    assertSame(Duration.ZERO, ExecutionResult.success("ok").duration());
    assertSame(Duration.ZERO, ExecutionResult.success("ok", "answer").duration());
  }

  @Test
  void failureFactoryHasZeroDuration() {
    assertSame(Duration.ZERO, ExecutionResult.failure("err").duration());
    assertSame(Duration.ZERO, ExecutionResult.failure("err", 137).duration());
  }
}
