/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.trace.Span;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookPhase;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A {@code hook.<name>} span names the hook's phase and outcome, and only a verbose trace adds the
 * reason or text the outcome carries, as its event shows it.
 */
final class RunTraceHookTest {

  private static final Instant AT = Instant.parse("2026-10-29T10:00:00Z");

  static Stream<Arguments> outcomes() {
    return Stream.of(
        Arguments.of(HookOutcome.block("no writes"), "no writes"),
        Arguments.of(HookOutcome.inject("remember the budget"), "remember the budget"),
        Arguments.of(HookOutcome.stop("enough"), "enough"),
        Arguments.of(HookOutcome.mutateText("shorter"), "shorter"),
        Arguments.of(HookOutcome.mutateResult("rewritten"), "rewritten"),
        Arguments.of(HookOutcome.mutateArgs(Map.of("path", "/tmp")), null),
        Arguments.of(HookOutcome.mutateHistory(List.of(Message.user("hi"))), null));
  }

  private static Map<String, String> attributes(HookOutcome outcome, boolean verbose) {
    var trace = new RunTrace(AT, verbose, new SecretRegistry());
    trace.accept(new QueryEvent.HookFired("s", "r", 1, AT, "guard", HookPhase.PRE_STOP, outcome));
    var spans = trace.finish(AT, new RunTrace.TraceEnding(null, "done")).build().spans();
    return spans.stream().map(Span::attributes).findFirst().orElseThrow();
  }

  @ParameterizedTest
  @MethodSource("outcomes")
  void aVerboseSpanAddsTheOutcomesText(HookOutcome outcome, String text) {
    var expected = new LinkedHashMap<String, String>();
    expected.put("hook.phase", "PRE_STOP");
    expected.put("hook.outcome", outcome.getClass().getSimpleName());
    if (text != null) {
      expected.put("hook.text", text);
    }

    assertEquals(expected, attributes(outcome, true));
  }

  @ParameterizedTest
  @MethodSource("outcomes")
  void aStandardSpanNamesOnlyThePhaseAndTheOutcome(HookOutcome outcome, String text) {
    assertEquals(
        Map.of("hook.phase", "PRE_STOP", "hook.outcome", outcome.getClass().getSimpleName()),
        attributes(outcome, false));
  }
}
