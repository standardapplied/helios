/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.session.hooks.HookOutcome;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each {@link HookOutcome} as the runtime writes it: its kind and its record components, a history
 * as its message count.
 */
final class HookOutcomeSerializerTest {

  static Stream<Arguments> outcomes() {
    return Stream.of(
        Arguments.of(HookOutcome.cont(), "{\"kind\":\"Continue\"}"),
        Arguments.of(
            HookOutcome.mutateArgs(Map.of("path", List.of("/a", 1))),
            "{\"kind\":\"MutateArgs\",\"args\":{\"path\":[\"/a\",1]}}"),
        Arguments.of(
            HookOutcome.mutateHistory(List.of(Message.user("a"), Message.assistant("b"))),
            "{\"kind\":\"MutateHistory\",\"messages\":2}"),
        Arguments.of(HookOutcome.mutateText("t"), "{\"kind\":\"MutateText\",\"text\":\"t\"}"),
        Arguments.of(HookOutcome.mutateResult(""), "{\"kind\":\"MutateResult\",\"output\":\"\"}"),
        Arguments.of(HookOutcome.block("no"), "{\"kind\":\"Block\",\"reason\":\"no\"}"),
        Arguments.of(
            HookOutcome.inject("remember"), "{\"kind\":\"Inject\",\"userMessage\":\"remember\"}"),
        Arguments.of(HookOutcome.stop("done"), "{\"kind\":\"Stop\",\"result\":\"done\"}"));
  }

  @ParameterizedTest
  @MethodSource("outcomes")
  void writesTheKindAndTheComponents(HookOutcome outcome, String json) {
    assertEquals(json, JsonBody.MAPPER.writeValueAsString(outcome));
  }
}
