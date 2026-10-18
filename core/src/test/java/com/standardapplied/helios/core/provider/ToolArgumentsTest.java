/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ProviderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolArgumentsTest {

  private static final ProviderFailure FAILURE = ProviderException::new;

  @Test
  void readsAJsonObjectAsADeepSnapshotKeepingNullValues() {
    var days = new ArrayList<Object>(Arrays.asList(1, null));
    var object = new LinkedHashMap<String, Object>();
    object.put("city", null);
    object.put("days", days);

    var arguments =
        ToolArguments.read("weather", "{\"city\":null,\"days\":[1,null]}", json -> object, FAILURE);
    days.add(2);

    var expected = new LinkedHashMap<String, Object>();
    expected.put("city", null);
    expected.put("days", Arrays.asList(1, null));
    assertEquals(expected, arguments);
    assertTrue(arguments.containsKey("city"));
    assertThrows(UnsupportedOperationException.class, () -> arguments.put("unit", "C"));
  }

  @Test
  void anObjectTheSnapshotRejectsFailsNamingTheToolAndWhere() {
    var cyclic = new HashMap<String, Object>();
    cyclic.put("self", cyclic);

    var thrown =
        assertThrows(
            ProviderException.class,
            () -> ToolArguments.read("weather", "{}", json -> cyclic, FAILURE));

    assertEquals(
        "Arguments of tool call 'weather' are rejected: arguments.self contains itself",
        thrown.getMessage());
    assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    assertEquals(0, thrown.statusCode());
  }

  @Test
  void noStreamedArgumentsAreAnEmptyObject() {
    assertEquals(
        Map.of(),
        ToolArguments.read(
            "weather",
            "",
            json -> {
              throw new AssertionError("not read");
            },
            FAILURE));
  }

  @Test
  void argumentsTheReaderRejectsFailNamingTheToolAndTheReason() {
    var rejected = new IllegalArgumentException("Unexpected end-of-input");

    var thrown =
        assertThrows(
            ProviderException.class,
            () ->
                ToolArguments.read(
                    "weather",
                    "{\"city\":",
                    json -> {
                      throw rejected;
                    },
                    FAILURE));

    assertEquals(
        "Arguments of tool call 'weather' are not a JSON object: Unexpected end-of-input",
        thrown.getMessage());
    assertSame(rejected, thrown.getCause());
    assertEquals(0, thrown.statusCode());
  }

  @Test
  void aJsonNullInsteadOfAnObjectFails() {
    var thrown =
        assertThrows(
            ProviderException.class,
            () -> ToolArguments.read("weather", "null", json -> null, FAILURE));

    assertEquals(
        "Arguments of tool call 'weather' are not a JSON object: null", thrown.getMessage());
    assertNull(thrown.getCause());
  }
}
