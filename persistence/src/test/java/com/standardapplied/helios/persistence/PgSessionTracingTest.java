/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.Paginate;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.Subagent;
import com.standardapplied.helios.session.TraceDetail;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A session wired to {@link PgSpanExporter} stores each run as it ends, and {@link PgTraceStore}
 * and {@link PgAnnotationStore} read it back by its run id.
 */
final class PgSessionTracingTest {

  private static final String SID = "sess-pg";
  private static final CostCalculator COSTS =
      CostCalculator.staticTable(Map.of("scripted", new Pricing(1_000_000, 2_000_000)));

  private final SpanCapture capture =
      new SpanCapture(SimpleSpanProcessor.create(new PgSpanExporter(PgTestSupport.pgConfig())));
  private final PgTraceStore store = new PgTraceStore(PgTestSupport.pgConfig());

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateTraces();
  }

  private ResultMessage run(TraceDetail detail) {
    return run(detail, Map.of());
  }

  private ResultMessage run(TraceDetail detail, Map<String, String> labels) {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(Usage.of(100, 10), new ToolCall("c-noop", "noop", Map.of()))
            .withTextTurn("matched", Usage.of(200, 20))
            .build();
    var noop =
        ToolBinding.newBuilder(
                Tool.newBuilder()
                    .withName("noop")
                    .withDescription("noop")
                    .withExecutor((arguments, context) -> ToolResult.success("ok"))
                    .build())
            .withCategory(ToolCategory.READ)
            .build();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId(SID)
            .withAgentName("matchmaking")
            .withCostCalculator(COSTS)
            .withTool(noop)
            .withTraceDetail(detail)
            .withTraceAttributes(labels)
            .withOpenTelemetry(capture.openTelemetry())
            .build();
    try (var session = AgentSession.open(options)) {
      return Await.value("the result", session.send("find a match"));
    }
  }

  @Test
  void aRunIsStoredWithItsIdsTotalsAndSteps() {
    var result = run(TraceDetail.STANDARD);

    var found = store.findById(result.runId());
    var runSpan = capture.only("invoke_agent matchmaking");
    assertEquals("matchmaking", found.name());
    assertEquals(SID, found.sessionId());
    assertEquals("scripted", found.modelId());
    assertEquals(runSpan.getTraceId(), found.otelTraceId());
    assertEquals(runSpan.getSpanId(), found.otelSpanId());
    var usage = result.usage();
    assertEquals(
        Usage.of(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheCreationInputTokens(),
            usage.cacheReadInputTokens()),
        found.usage());
    assertEquals(usage.totalTokens(), found.totalTokens());
    assertEquals(result.cost(), found.cost());
    assertTrue(found.success());
    assertEquals(null, found.inputText());
    assertFalse(found.attributes().keySet().stream().anyMatch(key -> key.startsWith("app.")));
    assertEquals(
        List.of("chat", "execute_tool", "chat"), found.spans().stream().map(Span::kind).toList());
    assertEquals(
        List.of("chat scripted", "execute_tool noop", "chat scripted"),
        found.spans().stream().map(Span::name).toList());
    assertEquals(
        List.of("1", "1", "2"),
        found.spans().stream().map(span -> span.attributes().get("helios.turn.index")).toList());
    assertEquals(
        result.usage().outputTokens(),
        found.spans().stream()
            .filter(span -> span.usage() != null)
            .mapToInt(span -> span.usage().outputTokens())
            .sum());

    assertEquals(
        List.of(result.runId()),
        store.list(Paginate.of(), null).items().stream().map(Trace::id).toList());
    var byName = store.summarize(TraceRollupKey.NAME, TraceFilter.none());
    assertEquals(Map.of("name", "matchmaking"), byName.getFirst().key());
    assertEquals(1, byName.getFirst().runCount());
    var byModel = store.summarize(TraceRollupKey.MODEL_ID, TraceFilter.none());
    assertEquals(Map.of("modelId", "scripted"), byModel.getFirst().key());
    assertEquals(result.cost().microUsd(), byModel.getFirst().costMicroUsd());
  }

  @Test
  void aRunAndOneOfItsSpansCanEachBeAnnotated() {
    var result = run(TraceDetail.STANDARD);
    var spanId = store.findById(result.runId()).spans().get(1).id();
    var annotations = new PgAnnotationStore(PgTestSupport.pgConfig());

    annotations.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(result.runId())
            .withLabel("match quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .build());
    annotations.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(spanId)
            .withLabel("tool choice")
            .withAuthorKind(AuthorKind.MODEL)
            .build());

    assertEquals(
        List.of("match quality"),
        annotations.findAnnotationsBySubject(result.runId()).stream()
            .map(Annotation::label)
            .toList());
    assertEquals(
        List.of("tool choice"),
        annotations.findAnnotationsBySubject(spanId).stream().map(Annotation::label).toList());
  }

  @Test
  void aLabelledRunAndTheHostSpanThatNamesItAfterItEndedAreStoredAsOneTrace() {
    var result = run(TraceDetail.STANDARD, Map.of("app.eval.case_id", "c-17"));
    capture
        .tracer()
        .spanBuilder("render reply")
        .setAttribute("helios.run.id", result.runId())
        .setAttribute("app.reply.fallback", true)
        .startSpan()
        .setStatus(StatusCode.ERROR, "parse failed")
        .end();

    var found = store.findById(result.runId());
    assertEquals("c-17", found.attributes().get("app.eval.case_id"));
    assertEquals(
        List.of("chat", "execute_tool", "chat", "host"),
        found.spans().stream().map(Span::kind).toList());
    var host = found.spans().getLast();
    assertEquals("application", host.scope());
    assertEquals("true", host.attributes().get("app.reply.fallback"));
    assertEquals("parse failed", host.error());
    assertEquals(
        List.of(result.runId()),
        store
            .list(Paginate.of(), "attributes eq \"${\\\"app.eval.case_id\\\":\\\"c-17\\\"}\"")
            .items()
            .stream()
            .map(Trace::id)
            .toList());
  }

  @Test
  void aVerboseRunStoresItsPlainInputAndOutputText() {
    var result = run(TraceDetail.VERBOSE);

    var found = store.findById(result.runId());
    assertEquals("find a match", found.inputText());
    assertEquals("matched", found.outputText());
  }

  /** Run session {@code sessionId}, whose model calls subagent {@code matching} once per prompt. */
  private void delegate(String sessionId, PgSessionStore sessions, String... prompts) {
    var calls =
        Arrays.stream(prompts)
            .map(
                prompt ->
                    new ToolCall(
                        "c-" + prompt, "Agent", Map.of("agent", "matching", "prompt", prompt)))
            .toArray(ToolCall[]::new);
    var child = ScriptedModel.newBuilder();
    Arrays.stream(prompts).forEach(prompt -> child.withTextTurn("found " + prompt));
    var matching =
        SessionOptions.newBuilder()
            .withModel(child.build())
            .withStore(sessions)
            .withOpenTelemetry(capture.openTelemetry())
            .build();
    var options =
        SessionOptions.newBuilder()
            .withModel(
                ScriptedModel.newBuilder().withToolCallsTurn(calls).withTextTurn("done").build())
            .withSessionId(sessionId)
            .withPermission(
                new Permission(
                    PermissionMode.DEFAULT,
                    List.of(PermissionRule.any("Agent")),
                    List.of(),
                    List.of()))
            .withSubagent(new Subagent("matching", "finds a match", matching))
            .withOpenTelemetry(capture.openTelemetry())
            .build();
    try (var session = AgentSession.open(options)) {
      Await.value("the result", session.send("delegate"));
    }
  }

  @Test
  void aSubagentsRunIsStoredUnderItsOwnSessionAndFoundByItsParentsSessionId() {
    PgTestSupport.truncateSessions();
    var sessions = new PgSessionStore(PgTestSupport.pgConfig());
    delegate(SID, sessions, "bob", "eve");
    delegate("sess-other", sessions, "ann");

    var children = capture.named("invoke_agent matching");
    var mine =
        children.stream()
            .filter(
                span ->
                    SID.equals(
                        span.getAttributes()
                            .get(AttributeKey.stringKey("helios.parent.session_id"))))
            .map(span -> span.getAttributes().get(AttributeKey.stringKey("helios.run.id")))
            .sorted()
            .toList();
    assertEquals(3, children.size());
    assertEquals(2, mine.size());
    assertEquals(
        mine,
        store
            .list(
                Paginate.of(),
                "attributes eq \"${\\\"helios.parent.session_id\\\":\\\"" + SID + "\\\"}\"")
            .items()
            .stream()
            .map(Trace::id)
            .sorted()
            .toList());
    for (var span : children) {
      var childId = span.getAttributes().get(AttributeKey.stringKey("gen_ai.conversation.id"));
      var found = store.findById(span.getAttributes().get(AttributeKey.stringKey("helios.run.id")));
      assertEquals(childId, found.sessionId());
      assertEquals(
          "Success", sessions.load(childId).lastRunEnded().orElseThrow().terminal(), childId);
      assertEquals(2, sessions.load(childId).history().size(), "its prompt and its answer");
    }
  }
}
