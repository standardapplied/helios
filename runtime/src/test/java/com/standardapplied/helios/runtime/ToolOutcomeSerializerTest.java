/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.tool.ToolOutcome;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Each {@link ToolOutcome} as the runtime writes it: its kind and its record components. */
final class ToolOutcomeSerializerTest {

  static Stream<Arguments> outcomes() {
    return Stream.of(
        Arguments.of(new ToolOutcome.Succeeded(), "{\"kind\":\"Succeeded\"}"),
        Arguments.of(new ToolOutcome.Failed(), "{\"kind\":\"Failed\"}"),
        Arguments.of(new ToolOutcome.NotFound(), "{\"kind\":\"NotFound\"}"),
        Arguments.of(
            new ToolOutcome.Threw("java.lang.IllegalStateException"),
            "{\"kind\":\"Threw\",\"exceptionType\":\"java.lang.IllegalStateException\"}"),
        Arguments.of(
            new ToolOutcome.TimedOut(Duration.ofMinutes(1)),
            "{\"kind\":\"TimedOut\",\"limit\":\"PT1M\"}"),
        Arguments.of(
            new ToolOutcome.Unknown(Duration.ofSeconds(30)),
            "{\"kind\":\"Unknown\",\"limit\":\"PT30S\"}"),
        Arguments.of(
            new ToolOutcome.Rejected("Gate", "not allowed"),
            "{\"kind\":\"Rejected\",\"by\":\"Gate\",\"reason\":\"not allowed\"}"));
  }

  @ParameterizedTest
  @MethodSource("outcomes")
  void writesTheKindAndTheComponents(ToolOutcome outcome, String json) {
    assertEquals(json, JsonBody.MAPPER.writeValueAsString(outcome));
  }
}
