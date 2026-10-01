/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ExecutionRequestTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var r =
        new ExecutionRequest(
            Runtime.PYTHON,
            "print('hi')",
            List.of("a", "b"),
            Path.of("/tmp"),
            Duration.ofSeconds(5),
            Map.of("FOO", "bar"),
            Optional.of("input"));
    assertEquals(Runtime.PYTHON, r.runtime());
    assertEquals("print('hi')", r.script());
    assertEquals(List.of("a", "b"), r.args());
    assertEquals(Path.of("/tmp"), r.workingDirectory());
    assertEquals(Duration.ofSeconds(5), r.timeout());
    assertEquals(Map.of("FOO", "bar"), r.environment());
    assertEquals(Optional.of("input"), r.stdin());
  }

  @Test
  void canonicalConstructorAcceptsNullWorkingDirectory() {
    var r =
        new ExecutionRequest(
            Runtime.BASH,
            "echo hi",
            List.of(),
            null,
            Duration.ofSeconds(1),
            Map.of(),
            Optional.empty());
    assertNull(r.workingDirectory());
  }

  // ── nulls and blanks ──────────────────────────────────────────────────────

  @Test
  void runtimeNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    null, "x", List.of(), null, Duration.ofSeconds(1), Map.of(), Optional.empty()));
    assertEquals("runtime must not be null", ex.getMessage());
  }

  @Test
  void scriptNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH,
                    null,
                    List.of(),
                    null,
                    Duration.ofSeconds(1),
                    Map.of(),
                    Optional.empty()));
    assertEquals("script must not be null", ex.getMessage());
  }

  @Test
  void scriptBlankRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH,
                    "   ",
                    List.of(),
                    null,
                    Duration.ofSeconds(1),
                    Map.of(),
                    Optional.empty()));
    assertEquals("script must not be blank", ex.getMessage());
  }

  @Test
  void argsNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH,
                    "x",
                    null,
                    null,
                    Duration.ofSeconds(1),
                    Map.of(),
                    Optional.empty()));
    assertEquals("args must not be null", ex.getMessage());
  }

  @Test
  void argsNullElementRejected() {
    var argsWithNull = Arrays.asList("first", null);
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH,
                    "x",
                    argsWithNull,
                    null,
                    Duration.ofSeconds(1),
                    Map.of(),
                    Optional.empty()));
    assertEquals("args must not contain null", ex.getMessage());
  }

  @Test
  void timeoutNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH, "x", List.of(), null, null, Map.of(), Optional.empty()));
    assertEquals("timeout must not be null", ex.getMessage());
  }

  @Test
  void timeoutZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH, "x", List.of(), null, Duration.ZERO, Map.of(), Optional.empty()));
    assertTrue(ex.getMessage().startsWith("timeout must be strictly positive"));
  }

  @Test
  void timeoutNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH,
                    "x",
                    List.of(),
                    null,
                    Duration.ofSeconds(-1),
                    Map.of(),
                    Optional.empty()));
    assertTrue(ex.getMessage().startsWith("timeout must be strictly positive"));
  }

  @Test
  void environmentNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH,
                    "x",
                    List.of(),
                    null,
                    Duration.ofSeconds(1),
                    null,
                    Optional.empty()));
    assertEquals("environment must not be null", ex.getMessage());
  }

  @Test
  void environmentNullKeyRejected() {
    var env = new HashMap<String, String>();
    env.put(null, "v");
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH,
                    "x",
                    List.of(),
                    null,
                    Duration.ofSeconds(1),
                    env,
                    Optional.empty()));
    assertEquals("environment must not contain null keys", ex.getMessage());
  }

  @Test
  void environmentNullValueRejected() {
    var env = new HashMap<String, String>();
    env.put("K", null);
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH,
                    "x",
                    List.of(),
                    null,
                    Duration.ofSeconds(1),
                    env,
                    Optional.empty()));
    assertEquals("environment must not contain null values", ex.getMessage());
  }

  @Test
  void stdinNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ExecutionRequest(
                    Runtime.BASH, "x", List.of(), null, Duration.ofSeconds(1), Map.of(), null));
    assertEquals("stdin must not be null", ex.getMessage());
  }

  // ── Builder ───────────────────────────────────────────────────────────────

  @Test
  void builderRequiresRuntime() {
    var b = ExecutionRequest.newBuilder().withScript("x");
    var ex = assertThrows(IllegalStateException.class, b::build);
    assertEquals("runtime is required", ex.getMessage());
  }

  @Test
  void builderRequiresScript() {
    var b = ExecutionRequest.newBuilder().withRuntime(Runtime.BASH);
    var ex = assertThrows(IllegalStateException.class, b::build);
    assertEquals("script is required", ex.getMessage());
  }

  @Test
  void builderDefaultsApplied() {
    var r = ExecutionRequest.newBuilder().withRuntime(Runtime.BASH).withScript("echo hi").build();
    assertEquals(List.of(), r.args());
    assertNull(r.workingDirectory());
    assertEquals(Duration.ofSeconds(30), r.timeout());
    assertEquals(Map.of(), r.environment());
    assertTrue(r.stdin().isEmpty());
  }

  @Test
  void builderWithAllOptions() {
    var r =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.PYTHON)
            .withScript("print(1)")
            .withArgs(List.of("a"))
            .withArg("b")
            .withWorkingDirectory(Path.of("/tmp"))
            .withTimeout(Duration.ofSeconds(15))
            .withEnvironment(Map.of("FOO", "bar"))
            .withEnv("BAZ", "qux")
            .withStdin("input")
            .build();
    assertEquals(Runtime.PYTHON, r.runtime());
    assertEquals("print(1)", r.script());
    assertEquals(List.of("a", "b"), r.args());
    assertEquals(Path.of("/tmp"), r.workingDirectory());
    assertEquals(Duration.ofSeconds(15), r.timeout());
    assertEquals(Map.of("FOO", "bar", "BAZ", "qux"), r.environment());
    assertEquals(Optional.of("input"), r.stdin());
  }

  @Test
  void builderWithRuntimeNullRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withRuntime(null));
    assertEquals("runtime must not be null", ex.getMessage());
  }

  @Test
  void builderWithScriptNullRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withScript(null));
    assertEquals("script must not be null", ex.getMessage());
  }

  @Test
  void builderWithScriptBlankRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withScript("  "));
    assertEquals("script must not be blank", ex.getMessage());
  }

  @Test
  void builderWithArgsNullRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withArgs(null));
    assertEquals("args must not be null", ex.getMessage());
  }

  @Test
  void builderWithArgsNullElementRejected() {
    var b = ExecutionRequest.newBuilder();
    var withNull = Arrays.asList("a", null);
    var ex = assertThrows(NullPointerException.class, () -> b.withArgs(withNull));
    assertEquals("args must not contain null", ex.getMessage());
  }

  @Test
  void builderWithArgNullRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withArg(null));
    assertEquals("arg must not be null", ex.getMessage());
  }

  @Test
  void builderWithTimeoutNullRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withTimeout(null));
    assertEquals("timeout must not be null", ex.getMessage());
  }

  @Test
  void builderWithTimeoutZeroRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withTimeout(Duration.ZERO));
    assertTrue(ex.getMessage().startsWith("timeout must be strictly positive"));
  }

  @Test
  void builderWithTimeoutNegativeRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex =
        assertThrows(IllegalArgumentException.class, () -> b.withTimeout(Duration.ofMillis(-1)));
    assertTrue(ex.getMessage().startsWith("timeout must be strictly positive"));
  }

  @Test
  void builderWithEnvironmentNullRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withEnvironment(null));
    assertEquals("environment must not be null", ex.getMessage());
  }

  @Test
  void builderWithEnvironmentNullKeyRejected() {
    var b = ExecutionRequest.newBuilder();
    var env = new HashMap<String, String>();
    env.put(null, "v");
    var ex = assertThrows(NullPointerException.class, () -> b.withEnvironment(env));
    assertEquals("environment must not contain null keys", ex.getMessage());
  }

  @Test
  void builderWithEnvironmentNullValueRejected() {
    var b = ExecutionRequest.newBuilder();
    var env = new HashMap<String, String>();
    env.put("K", null);
    var ex = assertThrows(NullPointerException.class, () -> b.withEnvironment(env));
    assertEquals("environment must not contain null values", ex.getMessage());
  }

  @Test
  void builderWithEnvNameNullRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withEnv(null, "v"));
    assertEquals("name must not be null", ex.getMessage());
  }

  @Test
  void builderWithEnvNameBlankRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withEnv("  ", "v"));
    assertEquals("name must not be blank", ex.getMessage());
  }

  @Test
  void builderWithEnvValueNullRejected() {
    var b = ExecutionRequest.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withEnv("K", null));
    assertEquals("value must not be null", ex.getMessage());
  }

  @Test
  void builderWithStdinNullClearsValue() {
    var r =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("x")
            .withStdin("first")
            .withStdin(null)
            .build();
    assertTrue(r.stdin().isEmpty());
  }
}
