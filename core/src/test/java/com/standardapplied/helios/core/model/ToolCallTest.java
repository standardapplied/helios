/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallTest {

  @Test
  void buildToolCall() {
    var toolCall =
        ToolCall.newBuilder()
            .withId("call_123")
            .withName("search")
            .withArguments(Map.of("query", "weather"))
            .build();

    assertEquals("call_123", toolCall.id());
    assertEquals("search", toolCall.name());
    assertEquals("weather", toolCall.arguments().get("query"));
  }

  @Test
  void buildWithNullArguments() {
    var toolCall =
        ToolCall.newBuilder().withId("call_1").withName("test").withArguments(null).build();

    assertTrue(toolCall.arguments().isEmpty());
  }

  @Test
  void buildWithEmptyArguments() {
    var toolCall =
        ToolCall.newBuilder().withId("call_1").withName("test").withArguments(Map.of()).build();

    assertTrue(toolCall.arguments().isEmpty());
  }

  @Test
  void argumentsAreImmutable() {
    var original = new java.util.HashMap<String, Object>();
    original.put("key", "value");

    var toolCall =
        ToolCall.newBuilder().withId("call_1").withName("test").withArguments(original).build();

    original.put("key2", "value2");

    assertEquals(1, toolCall.arguments().size());
    assertEquals("value", toolCall.arguments().get("key"));
  }
}
