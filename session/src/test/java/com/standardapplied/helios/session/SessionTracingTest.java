/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.core.trace.Span;
import com.standardapplied.helios.core.trace.SpanKind;
import com.standardapplied.helios.core.trace.Trace;
import com.standardapplied.helios.core.trace.TraceDetail;
import com.standardapplied.helios.session.EndingSessions.Unprintable;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A session given a trace listener hands it one trace per run, however the run ends: a span per
 * model attempt, tool call and hook decision, totals equal to the run's result, content only when
 * asked for and always redacted.
 */
final class SessionTracingTest {

  private static final String SID = "sess-traced";
  private static final String SECRET = "sk-trace-secret-0123456789";
  private static final String SENTINEL = "S3NT1NEL";
  private static final CostCalculator COSTS =
      CostCalculator.staticTable(
          Map.of(
              "scripted", new Pricing(1_000_000, 2_000_000),
              "summary", new Pricing(3_000_000, 3_000_000)));

  private final List<Trace> traces = new CopyOnWriteArrayList<>();
  private final CountDownLatch entered = new CountDownLatch(1);

  private SessionOptions.Builder traced(Model model) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withCostCalculator(COSTS)
        .withTraceListener(traces::add);
  }

  private Trace onlyTrace() {
    assertEquals(1, traces.size(), "traces");
    return traces.getFirst();
  }

  private static List<Span> spans(Trace trace, String name) {
    return trace.spans().stream().filter(span -> span.name().equals(name)).toList();
  }

  private static Span onlySpan(Trace trace, String name) {
    var named = spans(trace, name);
    assertEquals(1, named.size(), name);
    return named.getFirst();
  }

  private static ToolBinding tool(String name, String output) {
    return EndingSessions.binding(name, (arguments, context) -> ToolResult.success(output));
  }

  private static ToolCall call(String name, Map<String, Object> arguments) {
    return new ToolCall("c-" + name, name, arguments);
  }

  private static void assertTotalsEqual(ResultMessage result, Trace trace) {
    assertEquals(result.usage(), trace.usage());
    assertEquals(result.cost(), trace.cost());
    assertEquals(result.usage().totalTokens(), trace.totalTokens());
  }

  private ResultMessage run(SessionOptions options, String prompt) {
    try (var session = AgentSession.open(options)) {
      return Await.value("the result", session.send(prompt));
    }
  }

  @Test
  void aRunsTraceHoldsEachModelCallAndToolCallWithTotalsEqualToItsResult(@TempDir Path dir) {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(Usage.of(100, 10), call(LsTool.NAME, Map.of()))
            .withTextTurn("done", Usage.of(200, 20))
            .build();
    var events = new EventCollector();
    ResultMessage result;
    try (var session =
        AgentSession.open(traced(model).withTool(LsTool.binding(WorkspaceRoot.of(dir))).build())) {
      events.read(session.events());
      result = Await.value("the result", session.send("list"));
      events.awaitRunEnded();
    }

    var trace = onlyTrace();
    var calls = spans(trace, "model.call");
    assertEquals(List.of(110, 220), calls.stream().map(s -> s.usage().totalTokens()).toList());
    assertEquals(COSTS.cost("scripted", Usage.of(100, 10)), calls.get(0).cost());
    assertEquals(COSTS.cost("scripted", Usage.of(200, 20)), calls.get(1).cost());
    assertTrue(calls.get(0).cost().microUsd() > 0);
    var ended = events.eventsOf(QueryEvent.ModelCallEnded.class);
    assertEquals(
        ended.stream().map(QueryEvent.ModelCallEnded::duration).toList(),
        calls.stream().map(Span::duration).toList());
    assertEquals(SpanKind.MODEL_CALL, calls.getFirst().kind());
    assertEquals("scripted", calls.getFirst().attributes().get("model"));
    assertEquals("1", calls.getFirst().attributes().get("attempt"));
    assertEquals("TOOL_CALLS", calls.getFirst().attributes().get("finishReason"));
    var ls = onlySpan(trace, "tool.LS");
    assertEquals(SpanKind.TOOL_EXECUTION, ls.kind());
    assertEquals("Succeeded", ls.attributes().get("tool.outcome"));
    assertEquals("c-LS", ls.attributes().get("tool.callId"));
    assertTrue(ls.success());
    assertEquals(330, trace.totalTokens());
    assertTotalsEqual(result, trace);
    assertEquals("run", trace.name());
    assertEquals("scripted", trace.modelId());
    assertEquals(SID, trace.sessionId());
    assertEquals(SID, trace.attributes().get("session.id"));
    assertEquals(result.runId(), trace.attributes().get("run.id"));
    assertEquals("testing", trace.attributes().get("provider"));
    assertEquals("Success", trace.attributes().get("terminal"));
    assertNull(trace.error());
    assertFalse(trace.attributes().containsKey("error.type"));
  }

  @Test
  void aRetriedAttemptIsAFailedModelCallWithNoUsage() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(new TransientStreamException("reset " + SECRET, null, "test")))
            .withTextTurn("ok", Usage.of(50, 5))
            .build();
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(new StreamRetryPolicy(2, Backoff.fixed(Duration.ZERO), 0.0))
            .build();
    var events = new EventCollector();
    ResultMessage result;
    try (var session =
        AgentSession.open(traced(model).withLimits(limits).withSecretRegistry(secrets).build())) {
      events.read(session.events());
      result = Await.value("the result", session.send("go"));
      events.awaitRunEnded();
    }

    var ended = events.eventsOf(QueryEvent.ModelCallEnded.class);
    assertEquals(List.of(1, 2), ended.stream().map(QueryEvent.ModelCallEnded::attempt).toList());
    var retried = ended.getFirst();
    assertEquals(Usage.of(0, 0), retried.usage());
    assertEquals(FinishReason.ERROR, retried.finishReason());
    assertEquals(TransientStreamException.class.getName(), retried.error().kind());
    assertFalse(retried.error().message().contains(SECRET));
    assertEquals(Usage.of(50, 5), ended.get(1).usage());
    var calls = spans(onlyTrace(), "model.call");
    assertFalse(calls.get(0).success());
    assertEquals(
        TransientStreamException.class.getName(), calls.get(0).attributes().get("error.type"));
    assertFalse(calls.get(0).error().contains(SECRET));
    assertTrue(calls.get(1).success());
    assertTotalsEqual(result, onlyTrace());
  }

  @Test
  void aCompactorsSummaryCallIsAModelCallPricedAtItsOwnModel() {
    var compactions = new AtomicInteger();
    ContextCompactor compactor =
        (history, context) ->
            compactions.getAndIncrement() == 0
                ? new CompactionResult(history, Usage.of(0, 0, 0, 900), "summary")
                : CompactionResult.noOp(history);
    var model = ScriptedModel.newBuilder().withTextTurn("done", Usage.of(10, 1)).build();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(1).build();
    var events = new EventCollector();
    ResultMessage result;
    try (var session =
        AgentSession.open(
            traced(model).withContextCompactor(compactor).withLimits(limits).build())) {
      events.read(session.events());
      result = Await.value("the result", session.send("go"));
      events.awaitRunEnded();
    }

    var summaries =
        events.eventsOf(QueryEvent.ModelCallEnded.class).stream()
            .filter(e -> e.modelId().equals("summary"))
            .toList();
    assertEquals(1, summaries.size());
    assertEquals(Usage.of(0, 0, 0, 900), summaries.getFirst().usage());
    assertEquals(COSTS.cost("summary", Usage.of(0, 0, 0, 900)), summaries.getFirst().cost());
    assertEquals(FinishReason.STOP, summaries.getFirst().finishReason());
    var trace = onlyTrace();
    assertEquals(900, trace.usage().cacheReadInputTokens());
    assertEquals(2, spans(trace, "model.call").size());
    assertTotalsEqual(result, trace);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aModelAttemptAHostErrorEndsIsAFailedModelCallWithNoUsage(boolean atOpen) {
    var failure = new HostFailure();
    var model =
        EndingSessions.model(
            () -> {
              if (atOpen) {
                throw failure;
              }
              return ModelStreams.failing(failure);
            });

    try (var session = AgentSession.open(traced(model).build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }

    var call = onlySpan(onlyTrace(), "model.call");
    assertFalse(call.success());
    assertEquals(HostFailure.class.getName(), call.attributes().get("error.type"));
    assertEquals("1", call.attributes().get("attempt"));
    assertEquals(Usage.of(0, 0), call.usage());
    assertEquals(Usage.of(0, 0), onlyTrace().usage());
  }

  @Test
  void aPricingFailureKeepsTheCallsUsageAtZeroCostInTheEventTheTraceAndTheResult() {
    var model = ScriptedModel.newBuilder().withTextTurn("done", Usage.of(100, 10)).build();
    CostCalculator unpriced =
        (modelId, usage) -> {
          throw new IllegalStateException("no price for " + modelId);
        };
    var events = new EventCollector();
    ResultMessage result;
    try (var session = AgentSession.open(traced(model).withCostCalculator(unpriced).build())) {
      events.read(session.events());
      result = Await.value("the result", session.send("go"));
      events.awaitRunEnded();
    }

    assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    assertEquals(Usage.of(100, 10), result.usage());
    var ended = events.eventsOf(QueryEvent.ModelCallEnded.class);
    assertEquals(1, ended.size());
    assertEquals(Usage.of(100, 10), ended.getFirst().usage());
    assertEquals(0, ended.getFirst().cost().microUsd());
    assertEquals(Usage.of(100, 10), onlySpan(onlyTrace(), "model.call").usage());
    assertTotalsEqual(result, onlyTrace());
  }

  @Test
  void aClockThatStepsBackDuringAModelCallLeavesTheResultAndReportsAZeroDuration() {
    var now = new AtomicReference<>(Instant.parse("2026-10-29T10:00:00Z"));
    var model = ScriptedModel.newBuilder().withTextTurn("done", Usage.of(100, 10)).build();
    var events = new EventCollector();
    ResultMessage result;
    var options =
        traced(model)
            .withClock(now::get)
            .withHook(
                (OnStreamEventHook)
                    (event, ctx) -> {
                      if (event instanceof QueryEvent.RunStarted) {
                        now.set(now.get().plusSeconds(10));
                      } else if (event instanceof QueryEvent.AssistantText) {
                        now.set(now.get().minusSeconds(1));
                      }
                    })
            .build();
    try (var session = AgentSession.open(options)) {
      events.read(session.events());
      result = Await.value("the result", session.send("go"));
      events.awaitRunEnded();
    }

    assertInstanceOf(ResultMessage.Success.class, result);
    var ended = events.eventsOf(QueryEvent.ModelCallEnded.class);
    assertEquals(1, ended.size());
    assertEquals(Duration.ZERO, ended.getFirst().duration());
    assertEquals(result.usage(), ended.getFirst().usage());
    assertEquals(Duration.ZERO, onlySpan(onlyTrace(), "model.call").duration());
  }

  @Test
  void aRunThatHitsItsTurnLimitLeavesATraceNamingItsTerminal() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(Usage.of(3, 1), call("noop", Map.of()))
            .build();
    var result =
        run(
            traced(model)
                .withTool(tool("noop", "ok"))
                .withLimits(SessionLimits.newBuilder().withMaxTurns(1).build())
                .build(),
            "go");

    assertInstanceOf(ResultMessage.ErrorMaxTurns.class, result);
    var trace = onlyTrace();
    assertEquals("ErrorMaxTurns", trace.attributes().get("terminal"));
    assertEquals("ErrorMaxTurns", trace.error());
    assertEquals("ErrorMaxTurns", trace.attributes().get("error.type"));
    assertTotalsEqual(result, trace);
  }

  @Test
  void aRunPastItsWallClockLeavesATrace() {
    var model = ScriptedModel.newBuilder().withStreamTurn(stalled()).build();
    var now = new AtomicReference<>(Instant.parse("2026-10-29T10:00:00Z"));
    var limits = SessionLimits.defaults();
    ResultMessage result;
    try (var session = AgentSession.open(traced(model).withClock(now::get).build())) {
      var future = session.send("go");
      Await.latch("the model stream to be read", entered);
      now.set(now.get().plus(limits.maxWallClock()).plusSeconds(1));
      ((AgentSessionImpl) session).lifecycle.expireWallClock();
      result = Await.value("the result", future);
    }

    assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, result);
    assertEquals("ErrorMaxWallClock", onlyTrace().error());
    assertEquals("ErrorMaxWallClock", onlyTrace().attributes().get("terminal"));
  }

  @Test
  void aRunCancelledByCloseMidTurnLeavesATrace() {
    var model = ScriptedModel.newBuilder().withStreamTurn(stalled()).build();
    ResultMessage result;
    try (var session = AgentSession.open(traced(model).build())) {
      var future = session.send("go");
      Await.latch("the model stream to be read", entered);
      session.close();
      result = Await.value("the result", future);
    }

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals("Cancelled", onlyTrace().error());
    var call = onlySpan(onlyTrace(), "model.call");
    assertFalse(call.success());
  }

  @Test
  void aRunCancelledByInterruptLeavesATrace() {
    var model = ScriptedModel.newBuilder().withStreamTurn(stalled()).build();
    ResultMessage result;
    try (var session = AgentSession.open(traced(model).build())) {
      var future = session.send("go");
      Await.latch("the model stream to be read", entered);
      session.interrupt();
      result = Await.value("the result", future);
    }

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals("Cancelled", onlyTrace().attributes().get("terminal"));
    assertEquals("Cancelled", onlyTrace().error());
  }

  @Test
  void aRefusedRunLeavesATrace() {
    var result =
        run(traced(ScriptedModel.newBuilder().withRefusalTurn("no").build()).build(), "go");

    assertInstanceOf(ResultMessage.Refusal.class, result);
    assertEquals("Refusal", onlyTrace().error());
  }

  @Test
  void aRunThatFailedItsExecutionNamesTheErrorItCarries() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(ModelStreams.failing(new IllegalStateException("broken " + SECRET)))
            .build();
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);

    var result = run(traced(model).withSecretRegistry(secrets).build(), "go");

    assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    var error = onlyTrace().error();
    assertTrue(
        error.startsWith("ErrorDuringExecution: java.lang.IllegalStateException: broken"), error);
    assertFalse(error.contains(SECRET), error);
    assertEquals("ErrorDuringExecution", onlyTrace().attributes().get("error.type"));
  }

  @Test
  void aRunWhoseStreamKeptFailingTransientlyNamesTheErrorItCarries() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(new TransientStreamException("reset", null, "test")))
            .build();
    var limits =
        SessionLimits.newBuilder().withStreamRetryPolicy(StreamRetryPolicy.disabled()).build();

    var result = run(traced(model).withLimits(limits).build(), "go");

    assertInstanceOf(ResultMessage.ErrorTransientStream.class, result);
    assertEquals(
        "ErrorTransientStream: " + TransientStreamException.class.getName() + ": reset",
        onlyTrace().error());
  }

  @Test
  void aToolThatThrowsAnErrorFailsTheResultAndTheTraceNamesItWithTheToolSpanFailed() {
    var failure = new HostFailure();
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call("fatal", Map.of())).build();
    var fatal =
        EndingSessions.binding(
            "fatal",
            (arguments, context) -> {
              throw failure;
            });
    try (var session = AgentSession.open(traced(model).withTool(fatal).build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }

    var trace = onlyTrace();
    assertTrue(trace.error().startsWith(HostFailure.class.getName()), trace.error());
    assertEquals(HostFailure.class.getName(), trace.attributes().get("error.type"));
    assertFalse(trace.attributes().containsKey("terminal"));
    var tool = onlySpan(trace, "tool.fatal");
    assertEquals(trace.error(), tool.error());
    assertEquals(trace.endTime(), tool.endTime());
  }

  @Test
  void eachRunOfASessionLeavesItsOwnTraceLinkedByTheSessionId() {
    var model =
        ScriptedModel.newBuilder()
            .withTextTurn("first", Usage.of(1, 1))
            .withToolCallsTurn(Usage.of(5, 5), call("noop", Map.of()))
            .build();
    var options =
        traced(model)
            .withTool(tool("noop", "ok"))
            .withLimits(SessionLimits.newBuilder().withMaxTurns(1).build())
            .build();
    ResultMessage first;
    ResultMessage second;
    try (var session = AgentSession.open(options)) {
      first = Await.value("the first run", session.send("one"));
      second = Await.value("the second run", session.send("two"));
    }

    assertEquals(2, traces.size());
    assertEquals(List.of(SID, SID), traces.stream().map(Trace::sessionId).toList());
    assertEquals(first.runId(), traces.get(0).attributes().get("run.id"));
    assertEquals(second.runId(), traces.get(1).attributes().get("run.id"));
    assertNotEquals(first.runId(), second.runId());
    assertEquals("Success", traces.get(0).attributes().get("terminal"));
    assertEquals("ErrorMaxTurns", traces.get(1).attributes().get("terminal"));
    assertTotalsEqual(first, traces.get(0));
    assertTotalsEqual(second, traces.get(1));
  }

  @Test
  void aSessionClosedBeforeAnyRunLeavesNoTrace() {
    AgentSession.open(traced(ScriptedModel.newBuilder().build()).build()).close();

    assertTrue(traces.isEmpty());
  }

  @Test
  void aStandardTraceStoresNoContent() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var prompt = "PROMPT-" + SECRET;

    run(contentSession(secrets).build(), prompt);

    var trace = onlyTrace();
    assertNull(trace.inputText());
    assertNull(trace.outputText());
    texts(trace)
        .forEach(
            text -> {
              assertFalse(text.contains(SENTINEL), text);
              assertFalse(text.contains(SECRET), text);
              assertFalse(text.contains("PROMPT-"), text);
              assertFalse(text.contains("ANSWER-"), text);
            });
    var echo = onlySpan(trace, "tool.echo");
    assertEquals(
        String.valueOf(("{\"q\":\"" + SENTINEL + "\"}").length()),
        echo.attributes().get("tool.argumentsChars"));
    assertEquals(
        String.valueOf("result <redacted:key>".length()),
        echo.attributes().get("tool.resultChars"));
  }

  @Test
  void aVerboseTraceStoresTheRedactedContent() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);

    run(contentSession(secrets).withTraceDetail(TraceDetail.VERBOSE).build(), "PROMPT-" + SECRET);

    var trace = onlyTrace();
    var echo = onlySpan(trace, "tool.echo");
    assertTrue(echo.attributes().get("tool.arguments").contains(SENTINEL));
    assertEquals("result <redacted:key>", echo.attributes().get("tool.result"));
    assertEquals("PROMPT-<redacted:key>", trace.inputText());
    assertEquals("ANSWER-done", trace.outputText());
    texts(trace).forEach(text -> assertFalse(text.contains(SECRET), text));
  }

  private SessionOptions.Builder contentSession(SecretRegistry secrets) {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("echo", Map.of("q", SENTINEL)))
            .withTextTurn("ANSWER-done")
            .build();
    return traced(model).withTool(tool("echo", "result " + SECRET)).withSecretRegistry(secrets);
  }

  /** Every string a trace holds outside its input and output text. */
  private static List<String> texts(Trace trace) {
    var texts = new ArrayList<String>();
    texts.add(trace.name());
    texts.add(String.valueOf(trace.error()));
    trace.attributes().forEach((key, value) -> texts.addAll(List.of(key, value)));
    for (var span : trace.spans()) {
      texts.addAll(List.of(span.name(), String.valueOf(span.error())));
      span.attributes().forEach((key, value) -> texts.addAll(List.of(key, value)));
    }
    return texts;
  }

  @Test
  void aBlockingHookIsAHookSpanAndItsCallAFailedToolSpanWithNoToolUse() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("noop", Map.of()))
            .withTextTurn("done")
            .build();

    run(traced(model).withTool(tool("noop", "ok")).withHook(new Blocking()).build(), "go");

    var trace = onlyTrace();
    var hook = onlySpan(trace, "hook.blocking");
    assertEquals(SpanKind.CUSTOM, hook.kind());
    assertEquals("PreToolUseHook", hook.attributes().get("hook.phase"));
    assertEquals("Block", hook.attributes().get("hook.outcome"));
    assertEquals(Duration.ZERO, hook.duration());
    var noop = onlySpan(trace, "tool.noop");
    assertEquals("Rejected", noop.attributes().get("tool.outcome"));
    assertEquals("Rejected", noop.error());
    assertEquals(Duration.ZERO, noop.duration());
  }

  @Test
  void aRewrittenResultReplacesWhatTheToolSpanSaysTheModelReadAndKeepsItsEnd() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("echo", Map.of()))
            .withTextTurn("done")
            .build();
    PostToolUseHook rewrite = (call, result, context) -> HookOutcome.mutateResult("rewritten");
    var events = new EventCollector();
    try (var session =
        AgentSession.open(
            traced(model)
                .withTool(tool("echo", "original"))
                .withHook(rewrite)
                .withTraceDetail(TraceDetail.VERBOSE)
                .build())) {
      events.read(session.events());
      Await.value("the result", session.send("go"));
      events.awaitRunEnded();
    }

    var echo = onlySpan(onlyTrace(), "tool.echo");
    assertEquals("rewritten", echo.attributes().get("tool.result"));
    assertEquals("9", echo.attributes().get("tool.resultChars"));
    assertEquals("Succeeded", echo.attributes().get("tool.outcome"));
    var first = events.eventsOf(QueryEvent.ToolResult.class).getFirst();
    assertEquals(first.timestamp(), echo.endTime().toInstant());
  }

  @Test
  void aListenerThatThrowsIsLoggedAndLeavesTheResultAsWithoutOne() {
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var model = ScriptedModel.newBuilder().withTextTurn("done", Usage.of(4, 2)).build();
    var plain = run(traced(model).build(), "go");
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    ResultMessage.Success failing;
    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      failing =
          assertInstanceOf(
              ResultMessage.Success.class,
              run(
                  traced(ScriptedModel.newBuilder().withTextTurn("done", Usage.of(4, 2)).build())
                      .withSecretRegistry(secrets)
                      .withTraceListener(
                          trace -> {
                            throw new IllegalStateException("listener " + SECRET);
                          })
                      .build(),
                  "go"));
    }

    var expected = assertInstanceOf(ResultMessage.Success.class, plain);
    assertEquals(expected.result(), failing.result());
    assertEquals(expected.usage(), failing.usage());
    assertEquals(expected.cost(), failing.cost());
    var warning =
        logged.stream()
            .filter(record -> record.getLevel() == Level.WARNING)
            .filter(record -> record.getMessage().contains("trace"))
            .findFirst()
            .orElseThrow();
    assertTrue(warning.getMessage().contains(SID));
    assertFalse(warning.getThrown().getMessage().contains(SECRET));
  }

  @Test
  void aListenerExceptionThatCannotBeReadIsLoggedByItsClassAndLeavesTheResult() {
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var options =
        traced(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withTraceListener(
                trace -> {
                  throw new Unprintable();
                })
            .build();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      assertInstanceOf(ResultMessage.Success.class, run(options, "go"));
    }

    var warning =
        logged.stream()
            .filter(record -> record.getLevel() == Level.WARNING)
            .filter(record -> record.getMessage().contains("trace"))
            .findFirst()
            .orElseThrow();
    assertEquals(Unprintable.class.getName(), warning.getThrown().getMessage());
  }

  @Test
  void aListenerThatThrowsAnErrorFailsTheRunWithThatError() {
    var failure = new HostFailure();
    var options =
        traced(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withTraceListener(
                trace -> {
                  throw failure;
                })
            .build();

    try (var session = AgentSession.open(options)) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }
  }

  @Test
  void theDefaultAndACallersSessionIdBothFillTheTrace() {
    var model = ScriptedModel.newBuilder().withTextTurn("a").withTextTurn("b").build();
    var defaulted =
        SessionOptions.newBuilder()
            .withModel(model)
            .withTraceListener(traces::add)
            .withCostCalculator(COSTS)
            .build();

    run(defaulted, "go");
    run(traced(model).withSessionId("member:42").build(), "go");

    assertTrue(traces.get(0).sessionId().startsWith("sess-"));
    assertEquals(defaulted.sessionId(), traces.get(0).sessionId());
    assertEquals(defaulted.sessionId(), traces.get(0).attributes().get("session.id"));
    assertEquals("member:42", traces.get(1).sessionId());
    assertEquals("member:42", traces.get(1).attributes().get("session.id"));
  }

  private CloseableIterator<ModelChunk> stalled() {
    return ModelStreams.stalled(entered::countDown);
  }

  /** Blocks every tool call. */
  private static final class Blocking implements PreToolUseHook {

    @Override
    public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext context) {
      return HookOutcome.block("not allowed");
    }

    @Override
    public String name() {
      return "blocking";
    }
  }

  /** A host failure a listener or a tool throws. */
  private static final class HostFailure extends Error {

    HostFailure() {
      super("host failure");
    }
  }
}
