/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.ProviderException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolArgumentsTest {

  private static final ProviderFailure FAILURE = ProviderException::new;

  @Test
  void readsAJsonObjectKeepingANullValue() {
    var object = new LinkedHashMap<String, Object>();
    object.put("city", null);

    assertSame(object, ToolArguments.read("weather", "{\"city\":null}", json -> object, FAILURE));
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
