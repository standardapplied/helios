/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.HandClock;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A session's runs are OpenTelemetry spans: one {@code invoke_agent} span per run with a {@code
 * chat} span per model attempt, an {@code execute_tool} span per tool call and a {@code hook} span
 * per hook decision under it, their usage the run's, and content only when asked for, always
 * redacted.
 */
final class SessionSpansTest {

  static final AttributeKey<String> RUN_ID = stringKey("helios.run.id");
  static final AttributeKey<String> OPERATION = stringKey("gen_ai.operation.name");
  static final AttributeKey<String> TERMINAL = stringKey("helios.terminal");
  static final AttributeKey<String> ERROR_TYPE = stringKey("error.type");
  static final AttributeKey<Long> INPUT_TOKENS = longKey("gen_ai.usage.input_tokens");
  static final AttributeKey<Long> OUTPUT_TOKENS = longKey("gen_ai.usage.output_tokens");
  static final AttributeKey<Long> CACHE_READ = longKey("gen_ai.usage.cache_read.input_tokens");
  static final AttributeKey<Long> CACHE_WRITE = longKey("gen_ai.usage.cache_write.input_tokens");
  static final AttributeKey<Long> TOTAL_TOKENS = longKey("helios.usage.total_tokens");
  static final AttributeKey<Long> COST = longKey("helios.cost.micro_usd");

  private static final String SID = "sess-spans";
  private static final String SECRET = "sk-trace-secret-0123456789";
  private static final String SENTINEL = "S3NT1NEL";
  private static final String RESPONSE_MODEL = "gen_ai.response.model";
  private static final String SERVED = "claude-opus-5-5-20261101";
  private static final String FIRST_CHUNK = "helios.model.first_chunk_ms";
  private static final String FIRST_TEXT = "helios.model.first_text_ms";
  private static final List<AttributeKey<String>> CONTENT =
      List.of(
          stringKey("gen_ai.input.messages"),
          stringKey("gen_ai.output.messages"),
          stringKey("gen_ai.tool.call.arguments"),
          stringKey("gen_ai.tool.call.result"),
          stringKey("helios.hook.text"));
  private static final CostCalculator COSTS =
      CostCalculator.staticTable(
          Map.of(
              "scripted", new Pricing(1_000_000, 2_000_000),
              "summary", new Pricing(3_000_000, 3_000_000)));

  private final SpanCapture capture = new SpanCapture();

  private SessionOptions.Builder traced(Model model) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withCostCalculator(COSTS)
        .withOpenTelemetry(capture.openTelemetry());
  }

  private static ResultMessage run(SessionOptions options, String prompt) {
    try (var session = AgentSession.open(options)) {
      return Await.value("the result", session.send(prompt));
    }
  }

  static ToolBinding tool(String name, String output) {
    return EndingSessions.binding(name, (arguments, context) -> ToolResult.success(output));
  }

  static ToolCall call(String name, Map<String, Object> arguments) {
    return new ToolCall("c-" + name, name, arguments);
  }

  static String string(SpanData span, String key) {
    return span.getAttributes().get(stringKey(key));
  }

  static Long number(SpanData span, String key) {
    return span.getAttributes().get(longKey(key));
  }

  /**
   * The run span carries the run's totals, never re-summed from its children, no total of its own,
   * and its cost only when there is one.
   */
  static void assertTotalsEqual(ResultMessage result, SpanData run) {
    var usage = result.usage();
    var input =
        (long) usage.inputTokens()
            + usage.cacheCreationInputTokens()
            + usage.cacheReadInputTokens();
    assertEquals(input, run.getAttributes().get(INPUT_TOKENS));
    assertEquals(usage.outputTokens(), run.getAttributes().get(OUTPUT_TOKENS));
    assertNull(run.getAttributes().get(TOTAL_TOKENS));
    var cost = result.cost().microUsd();
    assertEquals(cost == 0 ? null : cost, run.getAttributes().get(COST));
  }

  @Test
  void aRunIsOneAgentSpanWithEachModelAttemptToolCallAndHookUnderIt() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(Usage.of(100, 10), call("noop", Map.of()))
            .withTextTurn("done", Usage.of(200, 20))
            .build();
    PostToolUseHook rewrite = (call, result, context) -> HookOutcome.mutateResult("rewritten");

    var result = run(traced(model).withTool(tool("noop", "ok")).withHook(rewrite).build(), "go");

    var run = capture.run();
    assertEquals("invoke_agent helios", run.getName());
    assertEquals(SpanKind.INTERNAL, run.getKind());
    assertEquals("invoke_agent", run.getAttributes().get(OPERATION));
    assertEquals("helios", string(run, "gen_ai.agent.name"));
    assertEquals(SID, string(run, "gen_ai.conversation.id"));
    assertEquals("testing", string(run, "gen_ai.provider.name"));
    assertEquals("scripted", string(run, "gen_ai.request.model"));
    assertEquals("Success", run.getAttributes().get(TERMINAL));
    assertEquals(StatusCode.UNSET, run.getStatus().getStatusCode());
    assertNull(run.getAttributes().get(ERROR_TYPE));
    assertFalse(run.getParentSpanContext().isValid());
    var chats = capture.named("chat scripted");
    var tool = capture.only("execute_tool noop");
    var hook = capture.only("hook " + rewrite.name());
    assertEquals(2, chats.size());
    assertEquals(5, capture.spans().size());
    for (var span : capture.spans()) {
      assertEquals(run.getTraceId(), span.getTraceId());
      assertEquals(result.runId(), span.getAttributes().get(RUN_ID));
      assertEquals("com.standardapplied.helios", span.getInstrumentationScopeInfo().getName());
    }
    for (var child : List.of(chats.get(0), chats.get(1), tool, hook)) {
      assertEquals(run.getSpanId(), child.getParentSpanId());
    }
    assertEquals(
        result.usage().outputTokens(),
        chats.stream().mapToLong(chat -> chat.getAttributes().get(OUTPUT_TOKENS)).sum());
    assertTotalsEqual(result, run);
    assertEquals(300L, run.getAttributes().get(INPUT_TOKENS));
    assertEquals(30L, run.getAttributes().get(OUTPUT_TOKENS));
    assertEquals(Set.of(), capture.unended());

    var first = chats.getFirst();
    assertEquals(SpanKind.CLIENT, first.getKind());
    assertEquals("chat", first.getAttributes().get(OPERATION));
    assertEquals("testing", string(first, "gen_ai.provider.name"));
    assertEquals("scripted", string(first, "gen_ai.request.model"));
    assertEquals(
        List.of("tool_calls"),
        first.getAttributes().get(stringArrayKey("gen_ai.response.finish_reasons")));
    assertEquals(
        List.of("stop"),
        chats.get(1).getAttributes().get(stringArrayKey("gen_ai.response.finish_reasons")));
    assertEquals(1L, number(first, "helios.attempt"));
    assertEquals(
        COSTS.cost("scripted", Usage.of(100, 10)).microUsd(), first.getAttributes().get(COST));
    assertEquals(100L, first.getAttributes().get(INPUT_TOKENS));

    assertEquals(SpanKind.INTERNAL, tool.getKind());
    assertEquals("execute_tool", tool.getAttributes().get(OPERATION));
    assertEquals("noop", string(tool, "gen_ai.tool.name"));
    assertEquals("c-noop", string(tool, "gen_ai.tool.call.id"));
    assertEquals("function", string(tool, "gen_ai.tool.type"));
    assertEquals("Succeeded", string(tool, "helios.tool.outcome"));
    assertEquals(2L, number(tool, "helios.tool.argumentsChars"));
    assertEquals(2L, number(tool, "helios.tool.resultChars"));
    assertEquals(StatusCode.UNSET, tool.getStatus().getStatusCode());
    assertNull(tool.getAttributes().get(INPUT_TOKENS));

    assertEquals(SpanKind.INTERNAL, hook.getKind());
    assertEquals(rewrite.name(), string(hook, "helios.hook.name"));
    assertEquals("POST_TOOL_USE", string(hook, "helios.hook.phase"));
    assertEquals("MutateResult", string(hook, "helios.hook.outcome"));
    assertEquals(hook.getStartEpochNanos(), hook.getEndEpochNanos());
    assertNull(hook.getAttributes().get(OPERATION));
  }

  @Test
  void aChatSpanCarriesTheModelThatServedItOnlyWhenTheProviderReportedOne() {
    var model =
        ScriptedModel.newBuilder()
            .withResponseTurn(
                Response.newBuilder()
                    .withContent("")
                    .withToolCalls(List.of(call("noop", Map.of())))
                    .withFinishReason(FinishReason.TOOL_CALLS)
                    .withMetadata(Map.of(Response.RESPONSE_MODEL_KEY, SERVED))
                    .build())
            .withTextTurn("done")
            .build();

    run(traced(model).withTool(tool("noop", "ok")).build(), "go");

    var chats = capture.named("chat scripted");
    assertEquals("scripted", string(chats.get(0), "gen_ai.request.model"));
    assertEquals(SERVED, string(chats.get(0), RESPONSE_MODEL));
    assertEquals("scripted", string(chats.get(1), "gen_ai.request.model"));
    assertNull(string(chats.get(1), RESPONSE_MODEL));
  }

  @Test
  void aChatSpanTimesItsFirstChunkOfAnyKindAndItsFirstTextOnTheSessionsClock() {
    var clock = new HandClock(Instant.EPOCH);
    var noop = call("noop", Map.of());
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                clock.paced(
                    ModelStreams.of(
                        new ModelChunk.ToolUseStart(noop.id(), noop.name()),
                        new ModelChunk.ToolUseStop(noop),
                        new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1), Map.of())),
                    250))
            .withStreamTurn(
                clock.paced(
                    ModelStreams.of(
                        new ModelChunk.ThinkingDelta("Hm."),
                        new ModelChunk.TextDelta("done"),
                        new ModelChunk.MessageStop("STOP", Usage.of(1, 1), Map.of())),
                    400,
                    600))
            .build();

    run(traced(model).withTool(tool("noop", "ok")).withClock(clock).build(), "go");

    var chats = capture.named("chat scripted");
    assertEquals(250L, number(chats.get(0), FIRST_CHUNK));
    assertNull(number(chats.get(0), FIRST_TEXT));
    assertEquals(400L, number(chats.get(1), FIRST_CHUNK));
    assertEquals(1_000L, number(chats.get(1), FIRST_TEXT));
    assertNull(number(capture.run(), FIRST_CHUNK));
  }

  @Test
  void aRetriedAttemptsChatSpanCarriesItsOwnFirstChunk() {
    var clock = new HandClock(Instant.EPOCH);
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                clock.paced(
                    ModelStreams.failing(
                        new TransientStreamException("reset", null, "test"),
                        new ModelChunk.KeepAlive()),
                    30))
            .withStreamTurn(
                clock.paced(
                    ModelStreams.of(
                        new ModelChunk.TextDelta("ok"),
                        new ModelChunk.MessageStop("STOP", Usage.of(1, 1), Map.of())),
                    50))
            .build();
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(new StreamRetryPolicy(2, Backoff.fixed(Duration.ZERO), 0.0))
            .build();

    run(traced(model).withLimits(limits).withClock(clock).build(), "go");

    var chats = capture.named("chat scripted");
    assertEquals(StatusCode.ERROR, chats.get(0).getStatus().getStatusCode());
    assertEquals(30L, number(chats.get(0), FIRST_CHUNK));
    assertNull(number(chats.get(0), FIRST_TEXT));
    assertEquals(50L, number(chats.get(1), FIRST_CHUNK));
    assertEquals(50L, number(chats.get(1), FIRST_TEXT));
  }

  @Test
  void usageCountsTheCachedTokensAsInputAndNamesEachCacheClassOnlyWhenItHasTokens() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(Usage.of(100, 20, 30, 50), call("noop", Map.of()))
            .withTextTurn("done", Usage.of(10, 2))
            .build();

    var result = run(traced(model).withTool(tool("noop", "ok")).build(), "go");

    var chats = capture.named("chat scripted");
    var cached = chats.getFirst().getAttributes();
    assertEquals(180L, cached.get(INPUT_TOKENS));
    assertEquals(20L, cached.get(OUTPUT_TOKENS));
    assertEquals(30L, cached.get(CACHE_WRITE));
    assertEquals(50L, cached.get(CACHE_READ));
    assertNull(cached.get(TOTAL_TOKENS));
    var plain = chats.get(1).getAttributes();
    assertEquals(10L, plain.get(INPUT_TOKENS));
    assertEquals(2L, plain.get(OUTPUT_TOKENS));
    assertNull(plain.get(CACHE_WRITE));
    assertNull(plain.get(CACHE_READ));
    assertNull(plain.get(TOTAL_TOKENS));
    assertTotalsEqual(result, capture.run());
    assertEquals(50L, capture.run().getAttributes().get(CACHE_READ));
  }

  @Test
  void aStandardRunCarriesNoContent() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);

    run(contentSession(secrets).build(), "PROMPT-" + SECRET);

    assertEquals(5, capture.spans().size());
    for (var span : capture.spans()) {
      CONTENT.forEach(key -> assertNull(span.getAttributes().get(key), key.getKey()));
      span.getAttributes()
          .forEach(
              (key, value) -> {
                assertFalse(String.valueOf(value).contains(SECRET), key.getKey());
                assertFalse(String.valueOf(value).contains(SENTINEL), key.getKey());
              });
    }
    var echo = capture.only("execute_tool echo");
    var arguments = "{\"q\":\"" + SENTINEL + " <redacted:key>\"}";
    assertEquals((long) arguments.length(), number(echo, "helios.tool.argumentsChars"));
    assertEquals((long) "result <redacted:key>".length(), number(echo, "helios.tool.resultChars"));
  }

  @Test
  void aVerboseRunCarriesTheRedactedContent() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);

    run(contentSession(secrets).withTraceDetail(TraceDetail.VERBOSE).build(), "PROMPT-" + SECRET);

    var run = capture.run();
    assertEquals(
        "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"PROMPT-<redacted:key>\"}]}]",
        string(run, "gen_ai.input.messages"));
    assertEquals(
        "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"ANSWER-<redacted:key>\"}]}]",
        string(run, "gen_ai.output.messages"));
    var echo = capture.only("execute_tool echo");
    assertEquals(
        "{\"q\":\"" + SENTINEL + " <redacted:key>\"}", string(echo, "gen_ai.tool.call.arguments"));
    assertEquals("result <redacted:key>", string(echo, "gen_ai.tool.call.result"));
    assertEquals("noted", string(capture.only("hook noting"), "helios.hook.text"));
    for (var span : capture.spans()) {
      span.getAttributes()
          .forEach(
              (key, value) -> assertFalse(String.valueOf(value).contains(SECRET), key.getKey()));
    }
  }

  private SessionOptions.Builder contentSession(SecretRegistry secrets) {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("echo", Map.of("q", SENTINEL + " " + SECRET)))
            .withTextTurn("ANSWER-" + SECRET)
            .build();
    return traced(model)
        .withTool(tool("echo", "result " + SECRET))
        .withHook(new Noting())
        .withSecretRegistry(secrets);
  }

  @Test
  void aVerboseRunsInputIsItsFirstUserMessageNotOneSteeredInLater() {
    var opened = new AtomicReference<AgentSession>();
    var steer =
        EndingSessions.binding(
            "steer",
            (arguments, context) -> {
              opened.get().send("STEERED");
              return ToolResult.success("ok");
            });
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("steer", Map.of()))
            .withTextTurn("done")
            .build();
    var events = new EventCollector();
    try (var session =
        AgentSession.open(
            traced(model).withTool(steer).withTraceDetail(TraceDetail.VERBOSE).build())) {
      opened.set(session);
      events.read(session.events());
      Await.value("the result", session.send("FIRST"));
      events.awaitRunEnded();
    }

    assertEquals(2, events.eventsOf(QueryEvent.UserMessageReceived.class).size());
    assertEquals(
        "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"FIRST\"}]}]",
        string(capture.run(), "gen_ai.input.messages"));
  }

  @Test
  void aBlockingHookIsAHookSpanAndItsCallAFailedToolSpanWithNoToolUse() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("noop", Map.of()))
            .withTextTurn("done")
            .build();

    run(traced(model).withTool(tool("noop", "ok")).withHook(new Blocking()).build(), "go");

    var hook = capture.only("hook blocking");
    assertEquals("PRE_TOOL_USE", string(hook, "helios.hook.phase"));
    assertEquals("Block", string(hook, "helios.hook.outcome"));
    assertEquals(hook.getStartEpochNanos(), hook.getEndEpochNanos());
    var noop = capture.only("execute_tool noop");
    assertEquals("Rejected", string(noop, "helios.tool.outcome"));
    assertEquals(1L, noop.getAttributes().get(SessionSpanTurnsTest.TURN_INDEX));
    assertEquals(StatusCode.ERROR, noop.getStatus().getStatusCode());
    assertEquals("Rejected", noop.getStatus().getDescription());
    assertEquals("Rejected", noop.getAttributes().get(ERROR_TYPE));
    assertEquals(capture.run().getSpanId(), noop.getParentSpanId());
  }

  @Test
  void aToolSpanRecordsWhatTheToolReturnedNotAPostToolUseRewrite() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("echo", Map.of()))
            .withTextTurn("done")
            .build();
    PostToolUseHook rewrite = (call, result, context) -> HookOutcome.mutateResult("rewritten");

    run(
        traced(model)
            .withTool(tool("echo", "original"))
            .withHook(rewrite)
            .withTraceDetail(TraceDetail.VERBOSE)
            .build(),
        "go");

    var echo = capture.only("execute_tool echo");
    assertEquals("original", string(echo, "gen_ai.tool.call.result"));
    assertEquals(8L, number(echo, "helios.tool.resultChars"));
    assertEquals("rewritten", string(capture.only("hook " + rewrite.name()), "helios.hook.text"));
  }

  @Test
  void aRetriedAttemptIsAFailedChatSpanWithNoUsage() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(new TransientStreamException("reset " + SECRET, null, "test")))
            .withResponseTurn(
                Response.newBuilder()
                    .withContent("ok")
                    .withUsage(Usage.of(50, 5))
                    .withFinishReason(FinishReason.STOP)
                    .withMetadata(Map.of(Response.RESPONSE_MODEL_KEY, SERVED))
                    .build())
            .build();
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(new StreamRetryPolicy(2, Backoff.fixed(Duration.ZERO), 0.0))
            .build();

    var result = run(traced(model).withLimits(limits).withSecretRegistry(secrets).build(), "go");

    var chats = capture.named("chat scripted");
    var retried = chats.getFirst();
    assertEquals(1L, number(retried, "helios.attempt"));
    assertEquals(StatusCode.ERROR, retried.getStatus().getStatusCode());
    assertEquals(TransientStreamException.class.getName(), retried.getAttributes().get(ERROR_TYPE));
    assertTrue(
        retried
            .getStatus()
            .getDescription()
            .startsWith(TransientStreamException.class.getName() + ": reset"));
    assertFalse(retried.getStatus().getDescription().contains(SECRET));
    assertEquals(
        List.of("error"),
        retried.getAttributes().get(stringArrayKey("gen_ai.response.finish_reasons")));
    for (var usage :
        List.of(INPUT_TOKENS, OUTPUT_TOKENS, CACHE_READ, CACHE_WRITE, TOTAL_TOKENS, COST)) {
      assertNull(retried.getAttributes().get(usage));
    }
    assertNull(string(retried, RESPONSE_MODEL));
    assertEquals(2L, number(chats.get(1), "helios.attempt"));
    assertEquals(SERVED, string(chats.get(1), RESPONSE_MODEL));
    assertEquals(1L, chats.get(0).getAttributes().get(SessionSpanTurnsTest.TURN_INDEX));
    assertEquals(1L, chats.get(1).getAttributes().get(SessionSpanTurnsTest.TURN_INDEX));
    assertEquals(StatusCode.UNSET, chats.get(1).getStatus().getStatusCode());
    assertTotalsEqual(result, capture.run());
  }

  /**
   * The span is read when the retry is announced: it has ended by then, so it does not span the
   * back-off, which the run's interrupt then cuts.
   */
  @Test
  void aRetriedAttemptThatReportedUsageIsAChatSpanWithItsUsageAndCostEndedBeforeItsBackOff() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(
                    new TransientStreamException("reset", null, "test"),
                    new ModelChunk.UsageDelta(Usage.of(40, 4))))
            .withTextTurn("never")
            .build();
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(
                new StreamRetryPolicy(2, Backoff.fixed(Duration.ofHours(1)), 0.0))
            .build();
    var endedAtRetry = new AtomicReference<List<SpanData>>();
    var retried = new CountDownLatch(1);

    try (var session = AgentSession.open(traced(model).withLimits(limits).build())) {
      new EventCollector(
              event -> {
                if (event instanceof QueryEvent.TurnRetried) {
                  endedAtRetry.set(capture.named("chat scripted"));
                  retried.countDown();
                }
              })
          .read(session.events());
      var run = session.send("go");
      Await.latch("the attempt's TurnRetried", retried);
      session.interrupt();
      Await.value("the interrupted run", run);
    }

    var chat = endedAtRetry.get().getFirst();
    assertEquals(1, endedAtRetry.get().size());
    assertEquals(1L, number(chat, "helios.attempt"));
    assertEquals(StatusCode.ERROR, chat.getStatus().getStatusCode());
    assertEquals(40L, chat.getAttributes().get(INPUT_TOKENS));
    assertEquals(4L, chat.getAttributes().get(OUTPUT_TOKENS));
    assertEquals(40L + 4 * 2, chat.getAttributes().get(COST));
  }

  @Test
  void aCompactorsSummaryCallIsAChatSpanOverTheCallsDurationPricedAtItsOwnModel() {
    var compactions = new AtomicInteger();
    ContextCompactor compactor =
        (history, context) ->
            compactions.getAndIncrement() == 0
                ? new CompactionResult(history, Usage.of(0, 0, 0, 900), "summary")
                : CompactionResult.noOp(history);
    var model = ScriptedModel.newBuilder().withTextTurn("done", Usage.of(10, 1)).build();
    var events = new EventCollector();
    ResultMessage result;
    try (var session =
        AgentSession.open(
            traced(model)
                .withContextCompactor(compactor)
                .withLimits(SessionLimits.newBuilder().withMaxContextTokens(1).build())
                .build())) {
      events.read(session.events());
      result = Await.value("the result", session.send("go"));
      events.awaitRunEnded();
    }

    var ended =
        events.eventsOf(QueryEvent.ModelCallEnded.class).stream()
            .filter(event -> event.modelId().equals("summary"))
            .findFirst()
            .orElseThrow();
    var summary = capture.only("chat summary");
    assertEquals("summary", string(summary, "gen_ai.request.model"));
    assertEquals(true, summary.getAttributes().get(SessionSpanTurnsTest.COMPACTION));
    assertNull(number(summary, FIRST_CHUNK));
    assertNull(number(summary, FIRST_TEXT));
    assertNull(summary.getAttributes().get(SessionSpanTurnsTest.TURN_INDEX));
    var turn = capture.only("chat scripted");
    assertNull(turn.getAttributes().get(SessionSpanTurnsTest.COMPACTION));
    assertEquals(1L, turn.getAttributes().get(SessionSpanTurnsTest.TURN_INDEX));
    assertEquals(900L, summary.getAttributes().get(INPUT_TOKENS));
    assertEquals(900L, summary.getAttributes().get(CACHE_READ));
    assertEquals(
        COSTS.cost("summary", Usage.of(0, 0, 0, 900)).microUsd(),
        summary.getAttributes().get(COST));
    assertEquals(
        ended.duration().toNanos(), summary.getEndEpochNanos() - summary.getStartEpochNanos());
    assertEquals(capture.run().getSpanId(), summary.getParentSpanId());
    assertTotalsEqual(result, capture.run());
  }

  @Test
  void aPricingFailureKeepsTheAttemptsUsageWithNoCost() {
    var model = ScriptedModel.newBuilder().withTextTurn("done", Usage.of(100, 10)).build();
    CostCalculator unpriced =
        (modelId, usage) -> {
          throw new IllegalStateException("no price for " + modelId);
        };

    var result = run(traced(model).withCostCalculator(unpriced).build(), "go");

    assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    var chat = capture.only("chat scripted");
    assertEquals(100L, chat.getAttributes().get(INPUT_TOKENS));
    assertNull(chat.getAttributes().get(COST));
    assertTotalsEqual(result, capture.run());
  }

  @Test
  void eachRunOfASessionIsItsOwnTraceLinkedByTheConversationId() {
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

    var runs = capture.starting("invoke_agent ");
    assertEquals(2, runs.size());
    assertEquals(first.runId(), runs.get(0).getAttributes().get(RUN_ID));
    assertEquals(second.runId(), runs.get(1).getAttributes().get(RUN_ID));
    assertEquals(SID, string(runs.get(1), "gen_ai.conversation.id"));
    assertNotEquals(runs.get(0).getTraceId(), runs.get(1).getTraceId());
    assertEquals("Success", runs.get(0).getAttributes().get(TERMINAL));
    assertEquals("ErrorMaxTurns", runs.get(1).getAttributes().get(TERMINAL));
    assertTotalsEqual(first, runs.get(0));
    assertTotalsEqual(second, runs.get(1));
    assertEquals(Set.of(), capture.unended());
  }

  @Test
  void everyRunSpanOfALabelledSessionCarriesItsLabelsAndNoOtherSpanDoes() {
    var model = ScriptedModel.newBuilder().withTextTurn("first").withTextTurn("second").build();
    var options =
        traced(model)
            .withTraceAttributes(Map.of("app.eval.case_id", "c-17", "app.eval.variant", "b"))
            .build();
    try (var session = AgentSession.open(options)) {
      Await.value("the first run", session.send("one"));
      Await.value("the second run", session.send("two"));
    }

    var runs = capture.starting("invoke_agent ");
    assertEquals(2, runs.size());
    for (var run : runs) {
      assertEquals("c-17", string(run, "app.eval.case_id"));
      assertEquals("b", string(run, "app.eval.variant"));
      assertEquals(SID, string(run, "gen_ai.conversation.id"));
    }
    for (var chat : capture.named("chat scripted")) {
      assertNull(string(chat, "app.eval.case_id"));
    }
  }

  @Test
  void anUnlabelledSessionsRunSpanCarriesNoAppAttribute() {
    run(traced(ScriptedModel.newBuilder().withTextTurn("done").build()).build(), "go");

    capture
        .run()
        .getAttributes()
        .forEach((key, value) -> assertFalse(key.getKey().startsWith("app."), key.getKey()));
  }

  @Test
  void aSessionClosedBeforeAnyRunEmitsNoSpan() {
    AgentSession.open(traced(ScriptedModel.newBuilder().build()).build()).close();

    assertEquals(List.of(), capture.spans());
  }

  @Test
  void theAgentNameNamesTheRunAndGeminiIsNamedAsTheConventionsName() {
    var model = new GeminiNamed(ScriptedModel.newBuilder().withTextTurn("done").build());

    run(traced(model).withAgentName("matchmaking").build(), "go");

    var run = capture.run();
    assertEquals("invoke_agent matchmaking", run.getName());
    assertEquals("matchmaking", string(run, "gen_ai.agent.name"));
    assertEquals("gcp.gemini", string(run, "gen_ai.provider.name"));
    assertEquals("gcp.gemini", string(capture.only("chat scripted"), "gen_ai.provider.name"));
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

  /** Rewrites every tool result to {@code noted}. */
  private static final class Noting implements PostToolUseHook {

    @Override
    public HookOutcome.PostToolUse afterTool(
        ToolCall call, ToolResult result, HookContext context) {
      return HookOutcome.mutateResult("noted");
    }

    @Override
    public String name() {
      return "noting";
    }
  }

  /** A model that reports itself a Gemini model. */
  private record GeminiNamed(Model model) implements Model {

    @Override
    public CloseableIterator<ModelChunk> chatStream(List<Message> messages, List<Tool> tools) {
      return model.chatStream(messages, tools);
    }

    @Override
    public String id() {
      return model.id();
    }

    @Override
    public String provider() {
      return "gemini";
    }
  }
}
