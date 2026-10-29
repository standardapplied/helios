/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.session.hooks.HookOutcome;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class RedactedHookOutcomeTest {

  private static final String SECRET = "s3cr3t-value";
  private static final String MARKER = "<redacted:TOKEN>";

  private static SecretRegistry secrets() {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", SECRET);
    return secrets;
  }

  static Stream<Arguments> outcomes() {
    return Stream.of(
        Arguments.of(HookOutcome.block("no " + SECRET), HookOutcome.block("no " + MARKER)),
        Arguments.of(HookOutcome.inject("use " + SECRET), HookOutcome.inject("use " + MARKER)),
        Arguments.of(HookOutcome.stop("saw " + SECRET), HookOutcome.stop("saw " + MARKER)),
        Arguments.of(
            HookOutcome.mutateText("say " + SECRET), HookOutcome.mutateText("say " + MARKER)),
        Arguments.of(
            HookOutcome.mutateResult("got " + SECRET), HookOutcome.mutateResult("got " + MARKER)),
        Arguments.of(
            HookOutcome.mutateArgs(Map.of("key-" + SECRET, List.of("token " + SECRET))),
            HookOutcome.mutateArgs(Map.of("key-" + MARKER, List.of("token " + MARKER)))));
  }

  @ParameterizedTest
  @MethodSource("outcomes")
  void everyStringTheOutcomeCarriesIsRedacted(HookOutcome outcome, HookOutcome shown) {
    assertEquals(shown, RedactedHookOutcome.of(outcome, secrets()));
  }

  @Test
  void aStringWithNoSecretIsKeptAsItIs() {
    var unpaired = HookOutcome.block("half \uD800 a pair");

    assertSame(
        unpaired.reason(),
        ((HookOutcome.Block) RedactedHookOutcome.of(unpaired, secrets())).reason());
  }

  @Test
  void aHistoryMutationIsShownAsTheHookReturnedIt() {
    var mutate = HookOutcome.mutateHistory(List.of(Message.user("keep " + SECRET)));

    assertSame(mutate, RedactedHookOutcome.of(mutate, secrets()));
  }
}
