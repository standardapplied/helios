/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolParameterTest {

  @Test
  void buildSimpleParameter() {
    var param =
        ToolParameter.newBuilder()
            .withName("query")
            .withDescription("The search query")
            .withType(ParameterType.STRING)
            .withRequired(true)
            .build();

    assertEquals("query", param.name());
    assertEquals("The search query", param.description());
    assertEquals(ParameterType.STRING, param.type());
    assertTrue(param.required());
    assertNull(param.defaultValue());
    assertNull(param.items());
  }

  @Test
  void buildWithDefaultValue() {
    var param =
        ToolParameter.newBuilder()
            .withName("limit")
            .withType(ParameterType.INTEGER)
            .withDefaultValue(10)
            .build();

    assertEquals(10, param.defaultValue());
    assertFalse(param.required());
  }

  @Test
  void buildArrayParameter() {
    var itemParam = ToolParameter.newBuilder().withType(ParameterType.STRING).build();
    var param =
        ToolParameter.newBuilder()
            .withName("tags")
            .withType(ParameterType.ARRAY)
            .withItems(itemParam)
            .build();

    assertEquals(ParameterType.ARRAY, param.type());
    assertEquals(itemParam, param.items());
    assertEquals(ParameterType.STRING, param.items().type());
  }

  @Test
  void defaultTypeIsString() {
    var param = ToolParameter.newBuilder().withName("test").build();

    assertEquals(ParameterType.STRING, param.type());
  }

  @Test
  void defaultRequiredIsFalse() {
    var param = ToolParameter.newBuilder().withName("test").build();

    assertFalse(param.required());
  }

  @Test
  void allParameterTypes() {
    assertEquals("string", ParameterType.STRING.jsonType());
    assertEquals("integer", ParameterType.INTEGER.jsonType());
    assertEquals("number", ParameterType.NUMBER.jsonType());
    assertEquals("boolean", ParameterType.BOOLEAN.jsonType());
    assertEquals("array", ParameterType.ARRAY.jsonType());
    assertEquals("object", ParameterType.OBJECT.jsonType());
    assertEquals(6, ParameterType.values().length);
  }
}
