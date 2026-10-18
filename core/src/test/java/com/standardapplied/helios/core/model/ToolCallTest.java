/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.DeclarationOrderFixture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ToolCallTest {

  @Test
  void theConstructorAndTheBuilderMakeEqualCalls() {
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("query", "weather");
    arguments.put("days", new ArrayList<>(List.of(1, 2)));

    var built =
        ToolCall.newBuilder()
            .withId("call_123")
            .withName("search")
            .withArguments(arguments)
            .build();

    assertEquals(new ToolCall("call_123", "search", arguments), built);
    assertEquals(arguments, built.arguments());
  }

  @Test
  void aCallWithoutArgumentsHasAnEmptyObjectForAParameterlessTool() {
    var built = ToolCall.newBuilder().withId("call_1").withName("now").build();

    assertEquals(new ToolCall("call_1", "now", Map.of()), built);
    assertTrue(built.arguments().isEmpty());
  }

  static Stream<Arguments> anInvalidCallIsRejectedAlikeByTheConstructorAndTheBuilder() {
    var cyclic = new HashMap<String, Object>();
    cyclic.put("self", cyclic);
    return Stream.of(
        Arguments.of(null, "echo", Map.of(), NullPointerException.class, "id must not be null"),
        Arguments.of(" ", "echo", Map.of(), IllegalArgumentException.class, "id must not be blank"),
        Arguments.of("c1", null, Map.of(), NullPointerException.class, "name must not be null"),
        Arguments.of("c1", "", Map.of(), IllegalArgumentException.class, "name must not be blank"),
        Arguments.of(
            "c1",
            "echo",
            null,
            NullPointerException.class,
            "arguments must not be null; a call without any is empty"),
        Arguments.of(
            "c1",
            "echo",
            Map.of("n", Double.NaN),
            IllegalArgumentException.class,
            "arguments.n is NaN; a JSON number is finite"),
        Arguments.of(
            "c1",
            "echo",
            cyclic,
            IllegalArgumentException.class,
            "arguments.self contains itself"));
  }

  @ParameterizedTest
  @MethodSource
  void anInvalidCallIsRejectedAlikeByTheConstructorAndTheBuilder(
      String id,
      String name,
      Map<String, Object> arguments,
      Class<? extends RuntimeException> type,
      String message) {
    Supplier<ToolCall> builder =
        () -> ToolCall.newBuilder().withId(id).withName(name).withArguments(arguments).build();

    assertEquals(message, assertThrows(type, () -> new ToolCall(id, name, arguments)).getMessage());
    assertEquals(message, assertThrows(type, builder::get).getMessage());
  }

  @Test
  void changingTheGivenMapsAfterConstructionLeavesTheCallAsItWas() {
    var days = new ArrayList<Object>(List.of(1, 2));
    var options = new HashMap<String, Object>(Map.of("unit", "C"));
    var arguments = new HashMap<String, Object>(Map.of("days", days, "options", options));

    var call = new ToolCall("c1", "weather", arguments);
    days.add(3);
    options.put("unit", "F");
    arguments.put("added", true);

    assertEquals(Map.of("days", List.of(1, 2), "options", Map.of("unit", "C")), call.arguments());
  }

  @Test
  void noViewACallReturnsCanChangeIt() {
    var call =
        new ToolCall(
            "c1",
            "weather",
            Map.of("days", new ArrayList<>(List.of(1)), "options", new HashMap<>(Map.of())));
    List<?> days = assertInstanceOf(List.class, call.arguments().get("days"));
    Map<?, ?> options = assertInstanceOf(Map.class, call.arguments().get("options"));

    assertThrows(UnsupportedOperationException.class, () -> call.arguments().put("k", "v"));
    assertThrows(UnsupportedOperationException.class, () -> days.clear());
    assertThrows(UnsupportedOperationException.class, () -> options.clear());
    assertEquals(
        new ToolCall("c1", "weather", Map.of("days", List.of(1), "options", Map.of())), call);
  }

  @Test
  void argumentsKeepTheOrderTheyWereGiven() {
    var arguments = new LinkedHashMap<String, Object>();
    for (var name : DeclarationOrderFixture.ARGUMENTS) {
      arguments.put(name, name);
    }

    var call = new ToolCall("c1", "echo", arguments);

    assertEquals(DeclarationOrderFixture.ARGUMENTS, DeclarationOrderFixture.keys(call.arguments()));
  }

  @Test
  void aJsonNullStaysAPresentKeyAtTheTopAndNestedWhileAnAbsentOneStaysAbsent() {
    var nested = new LinkedHashMap<String, Object>();
    nested.put("lang", null);
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("city", null);
    arguments.put("days", Arrays.asList(1, null));
    arguments.put("options", nested);

    var call = new ToolCall("c1", "weather", arguments);

    assertEquals(arguments, call.arguments());
    assertTrue(call.arguments().containsKey("city"));
    assertTrue(assertInstanceOf(Map.class, call.arguments().get("options")).containsKey("lang"));
    assertFalse(call.arguments().containsKey("unit"));
  }
}
