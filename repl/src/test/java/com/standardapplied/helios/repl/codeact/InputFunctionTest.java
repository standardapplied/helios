/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link InputFunction}. The host function is what makes typed input
 * variables show up inside the sandbox at the top of the model's first {@code execute_code}
 * iteration — its toMap behaviour is load-bearing for the CodeAct preset's "input is pre-bound"
 * promise.
 */
final class InputFunctionTest {

  public record Input(String topic, int count, List<String> tags) {}

  @SuppressWarnings("unchecked")
  private static Map<String, Object> invoke(HostFunction fn) {
    try {
      return (Map<String, Object>) fn.handler().handle(Map.of());
    } catch (Exception e) {
      throw new AssertionError("InputFunction handler must not throw", e);
    }
  }

  @Test
  void reservedNameMatchesHostBridge() {
    assertEquals("__getInput", InputFunction.NAME);
  }

  @Test
  void nullInputProducesEmptyMap() {
    var fn = InputFunction.create(null);
    var out = invoke(fn);
    assertTrue(out.isEmpty());
  }

  @Test
  void recordInputIsConvertedToMap() {
    var fn = InputFunction.create(new Input("widgets", 7, List.of("a", "b")));
    var out = invoke(fn);
    assertEquals("widgets", out.get("topic"));
    assertEquals(7, out.get("count"));
    assertEquals(List.of("a", "b"), out.get("tags"));
  }

  @Test
  void mapInputIsReturnedAsIs() {
    var input = Map.<String, Object>of("topic", "widgets", "count", 7);
    var fn = InputFunction.create(input);
    var out = invoke(fn);
    assertEquals(7, out.get("count"));
    assertEquals("widgets", out.get("topic"));
  }

  @Test
  void hostFunctionIsZeroArity() {
    var fn = InputFunction.create(new Input("x", 1, List.of()));
    assertEquals(0, fn.parameters().size());
    assertNotNull(fn.description());
    assertTrue(fn.description().toLowerCase().contains("framework"));
  }
}
