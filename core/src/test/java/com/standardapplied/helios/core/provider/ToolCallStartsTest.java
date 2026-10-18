/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ToolCallStartsTest {

  private static final ProviderFailure FAILURE = ProviderException::new;

  @ParameterizedTest
  @CsvSource(
      value = {
        "NULL, weather, Tool call 'weather' streamed without an id",
        "'  ', weather, Tool call 'weather' streamed without an id",
        "c1, NULL, A tool call streamed without a tool name",
        "NULL, '', A tool call streamed without a tool name"
      },
      nullValues = "NULL")
  void aCallWithoutAnIdOrANameFailsWithTheProvidersException(
      String id, String name, String message) {
    var thrown = assertThrows(ProviderException.class, () -> ToolCallStarts.of(id, name, FAILURE));

    assertEquals(message, thrown.getMessage());
    assertEquals(0, thrown.statusCode());
    assertNull(thrown.getCause());
  }

  @Test
  void aCallWithAnIdAndANameStarts() {
    assertEquals(
        new StreamEvent.ToolCallStart("c1", "weather"),
        ToolCallStarts.of("c1", "weather", FAILURE));
  }
}
