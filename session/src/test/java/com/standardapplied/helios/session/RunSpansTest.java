/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookPhase;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link RunSpans} on its own: a hook span names the hook, its phase and its outcome, and only a
 * verbose run adds the reason or text the outcome carries; a step's span is a child of its run's
 * span whatever context is current; an event of no run in progress starts no span; the served model
 * is set only on the open attempt's span; and a run's error is the first host {@link Error} its
 * session recorded.
 */
final class RunSpansTest {

  private static final Instant AT = Instant.parse("2026-11-03T10:00:00Z");
  private static final RuntimeException CRASH = new IllegalStateException("crashed");
  private static final String SECRET = "sk-span-secret-0123456789";
  private static final String RESPONSE_MODEL = "gen_ai.response.model";

  private final SpanCapture capture = new SpanCapture();
  private final SessionState run =
      new SessionState("sess-spans", new CancellationToken(), Clock.systemUTC());

  private RunSpans spans(TraceDetail detail) {
    return spans(detail, new SecretRegistry());
  }

  private RunSpans spans(TraceDetail detail, SecretRegistry secrets) {
    var options =
        SessionOptions.newBuilder()
            .withModel(ScriptedModel.newBuilder().build())
            .withSessionId("sess-spans")
            .withOpenTelemetry(capture.openTelemetry())
            .withTraceDetail(detail)
            .withSecretRegistry(secrets)
            .build();
    return new RunSpans(options, Map.of());
  }

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

  private Map<String, Object> hookAttributes(HookOutcome outcome, TraceDetail detail) {
    var spans = spans(detail);
    spans.start(run, Context.root());
    spans.accept(
        new QueryEvent.HookFired(
            "sess-spans", run.runId(), 1, AT, "guard", HookPhase.PRE_STOP, outcome));
    spans.end(run, null, CRASH);
    var attributes = new LinkedHashMap<String, Object>();
    capture
        .only("hook guard")
        .getAttributes()
        .forEach((key, value) -> attributes.put(key.getKey(), value));
    return attributes;
  }

  @ParameterizedTest
  @MethodSource("outcomes")
  void aVerboseHookSpanAddsTheOutcomesText(HookOutcome outcome, String text) {
    var expected = new LinkedHashMap<String, Object>();
    expected.put("helios.run.id", run.runId());
    expected.put("helios.hook.name", "guard");
    expected.put("helios.hook.phase", "PRE_STOP");
    expected.put("helios.hook.outcome", outcome.getClass().getSimpleName());
    expected.put("helios.turn.index", 1L);
    if (text != null) {
      expected.put("helios.hook.text", text);
    }

    assertEquals(expected, hookAttributes(outcome, TraceDetail.VERBOSE));
  }

  @ParameterizedTest
  @MethodSource("outcomes")
  void aStandardHookSpanNamesOnlyTheHookItsPhaseAndItsOutcome(HookOutcome outcome, String text) {
    assertEquals(
        Map.of(
            "helios.run.id",
            run.runId(),
            "helios.hook.name",
            "guard",
            "helios.hook.phase",
            "PRE_STOP",
            "helios.hook.outcome",
            outcome.getClass().getSimpleName(),
            "helios.turn.index",
            1L),
        hookAttributes(outcome, TraceDetail.STANDARD));
  }

  @Test
  void anEventOfNoRunInProgressStartsNoSpan() {
    var spans = spans(TraceDetail.STANDARD);

    spans.accept(
        new QueryEvent.HookFired(
            "sess-spans", run.runId(), 1, AT, "guard", HookPhase.PRE_STOP, HookOutcome.stop("x")));
    spans.end(run, null, CRASH);

    assertEquals(List.of(), capture.spans());
  }

  @Test
  void aToolCallWithNoSpanRunsInTheRunsContext() {
    var spans = spans(TraceDetail.STANDARD);
    var context = spans.start(run, Context.root());

    var tool = spans.toolCall(run, new ToolCall("c-1", "unseen", Map.of()));

    assertSame(Span.fromContext(context), Span.fromContext(tool));
    spans.end(run, null, CRASH);
    assertEquals(List.of("invoke_agent helios"), capture.names());
  }

  @Test
  void aStepsSpanIsAChildOfItsRunsSpanWhateverContextIsCurrent() {
    var spans = spans(TraceDetail.STANDARD);
    var caller = capture.tracer().spanBuilder("request").startSpan();
    spans.start(run, Context.root().with(caller));

    spans.accept(
        new QueryEvent.HookFired(
            "sess-spans", run.runId(), 1, AT, "guard", HookPhase.PRE_STOP, HookOutcome.stop("x")));
    spans.end(run, null, CRASH);

    var runSpan = capture.run();
    assertEquals(caller.getSpanContext().getSpanId(), runSpan.getParentSpanId());
    assertEquals(runSpan.getSpanId(), capture.only("hook guard").getParentSpanId());
  }

  @Test
  void theServedModelIsSetOnTheOpenAttemptsSpanOnly() {
    var spans = spans(TraceDetail.STANDARD);
    spans.start(run, Context.root());

    spans.modelAttempt(run);
    spans.servedModel(run, null);
    spans.accept(attemptEnded(1));
    spans.modelAttempt(run);
    spans.servedModel(run, "claude-opus-5-5-20261101");
    spans.accept(attemptEnded(2));
    spans.servedModel(run, "after-the-attempt");
    spans.end(run, null, CRASH);

    assertNull(SessionSpansTest.string(capture.starting("chat ").get(0), RESPONSE_MODEL));
    assertEquals(
        "claude-opus-5-5-20261101",
        SessionSpansTest.string(capture.starting("chat ").get(1), RESPONSE_MODEL));
    assertNull(SessionSpansTest.string(capture.run(), RESPONSE_MODEL));
  }

  @Test
  void theServedModelOfARunWithNoSpanSetsNothing() {
    spans(TraceDetail.STANDARD).servedModel(run, "claude-opus-5-5-20261101");

    assertEquals(List.of(), capture.spans());
  }

  private QueryEvent.ModelCallEnded attemptEnded(int attempt) {
    return new QueryEvent.ModelCallEnded(
        "sess-spans",
        run.runId(),
        run.currentTurnIndex(),
        AT,
        "scripted",
        attempt,
        FinishReason.STOP,
        Usage.of(1, 1),
        CostEstimate.zero(),
        Duration.ZERO,
        null);
  }

  @Test
  void aRunsErrorIsTheFirstHostErrorItsSessionRecordedBeforeItsCrashRedacted() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var spans = spans(TraceDetail.STANDARD, secrets);
    spans.start(run, Context.root());

    run.ending().fail(new AssertionError("leaked " + SECRET));
    spans.end(run, null, CRASH);

    var span = capture.run();
    assertEquals(
        "java.lang.AssertionError: leaked <redacted:key>", span.getStatus().getDescription());
    assertEquals("java.lang.AssertionError", SessionSpansTest.string(span, "error.type"));
  }

  @Test
  void aVerboseRunWithNoUserMessageAndNoResultCarriesNoContent() {
    var spans = spans(TraceDetail.VERBOSE);
    spans.start(run, Context.root());

    spans.end(run, null, CRASH);

    var span = capture.run();
    assertNull(SessionSpansTest.string(span, "gen_ai.input.messages"));
    assertNull(SessionSpansTest.string(span, "gen_ai.output.messages"));
    assertEquals("java.lang.IllegalStateException: crashed", span.getStatus().getDescription());
  }
}
