/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExecutionRequestTest {

  @Test
  void constructorSetsFields() {
    var req = new ExecutionRequest("println(1)", "java", Duration.ofSeconds(10));
    assertEquals("println(1)", req.code());
    assertEquals("java", req.language());
    assertEquals(Duration.ofSeconds(10), req.timeout());
  }

  @Test
  void nullCodeThrows() {
    assertThrows(IllegalArgumentException.class, () -> new ExecutionRequest(null, "java", null));
  }

  @Test
  void blankCodeThrows() {
    assertThrows(IllegalArgumentException.class, () -> new ExecutionRequest("  ", "java", null));
  }

  @Test
  void nullLanguageThrows() {
    assertThrows(IllegalArgumentException.class, () -> new ExecutionRequest("code", null, null));
  }

  @Test
  void blankLanguageThrows() {
    assertThrows(IllegalArgumentException.class, () -> new ExecutionRequest("code", " ", null));
  }

  @Test
  void zeroTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionRequest("code", "java", Duration.ZERO));
  }

  @Test
  void negativeTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionRequest("code", "java", Duration.ofSeconds(-1)));
  }

  @Test
  void nullTimeoutAllowed() {
    var req = new ExecutionRequest("code", "java", null);
    assertNull(req.timeout());
  }

  @Test
  void javaFactory() {
    var req = ExecutionRequest.java("int x = 1;");
    assertEquals("int x = 1;", req.code());
    assertEquals("java", req.language());
    assertNull(req.timeout());
  }

  @Test
  void builderDefaults() {
    var req = ExecutionRequest.newBuilder().withCode("x").build();
    assertEquals("x", req.code());
    assertEquals("java", req.language());
    assertNull(req.timeout());
  }

  @Test
  void builderAllOptions() {
    var req =
        ExecutionRequest.newBuilder()
            .withCode("code")
            .withLanguage("python")
            .withTimeout(Duration.ofMinutes(1))
            .build();
    assertEquals("code", req.code());
    assertEquals("python", req.language());
    assertEquals(Duration.ofMinutes(1), req.timeout());
  }
}
