/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Response.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceBuilderTest {

  @Test
  void createAndEndTrace() {
    var trace = TraceBuilder.start("agent-run").end();

    assertNotNull(trace.id());
    assertEquals("agent-run", trace.name());
    assertNotNull(trace.startTime());
    assertNotNull(trace.endTime());
    assertNotNull(trace.duration());
    assertNull(trace.error());
    assertTrue(trace.success());
    assertTrue(trace.spans().isEmpty());
    assertTrue(trace.attributes().isEmpty());
  }

  @Test
  void traceWithMultipleSpans() {
    var builder = TraceBuilder.start("agent-run");
    builder.span("model.chat", SpanKind.MODEL_CALL).end();
    builder.span("tool.search", SpanKind.TOOL_EXECUTION).end();

    var trace = builder.end();

    assertEquals(2, trace.spans().size());
    assertEquals("model.chat", trace.spans().get(0).name());
    assertEquals("tool.search", trace.spans().get(1).name());
  }

  @Test
  void traceWithNestedSpans() {
    var builder = TraceBuilder.start("agent-run");
    var toolSpan = builder.span("tool.search", SpanKind.TOOL_EXECUTION);
    var innerModel = toolSpan.span("inner.chat", SpanKind.MODEL_CALL);
    innerModel.end();
    toolSpan.end();

    var trace = builder.end();

    assertEquals(1, trace.spans().size());
    var tool = trace.spans().getFirst();
    assertEquals("tool.search", tool.name());
    assertEquals(1, tool.children().size());
    assertEquals("inner.chat", tool.children().getFirst().name());
  }

  @Test
  void failWithErrorMessage() {
    var trace = TraceBuilder.start("agent-run").fail("model unavailable");

    assertFalse(trace.success());
    assertEquals("model unavailable", trace.error());
  }

  @Test
  void endThrowsIfSpansStillOpen() {
    var builder = TraceBuilder.start("agent-run");
    builder.span("model.chat", SpanKind.MODEL_CALL);

    var ex = assertThrows(IllegalStateException.class, builder::end);
    assertTrue(ex.getMessage().contains("1 span(s) still open"));
  }

  @Test
  void failAutoFailsOpenSpans() {
    var builder = TraceBuilder.start("agent-run");
    builder.span("model.chat", SpanKind.MODEL_CALL);
    builder.span("tool.search", SpanKind.TOOL_EXECUTION);

    var trace = builder.fail("agent crashed");

    assertEquals(2, trace.spans().size());
    for (var span : trace.spans()) {
      assertFalse(span.success());
      assertTrue(span.error().contains("Trace 'agent-run' failed"));
    }
  }

  @Test
  void endReturnsBuiltTrace() {
    var trace = TraceBuilder.start("agent-run").end();
    assertEquals("agent-run", trace.name());
    assertTrue(trace.success());
  }

  @Test
  void failReturnsBuiltTraceWithError() {
    var trace = TraceBuilder.start("agent-run").fail("boom");
    assertFalse(trace.success());
    assertEquals("boom", trace.error());
  }

  @Test
  void eventSinksReceiveSpanOpenedAndClosed() {
    var events = new ArrayList<ai.singlr.core.events.HeliosEvent>();
    var runId = ai.singlr.core.common.Ids.newId();
    ai.singlr.core.events.EventSink sink = events::add;
    var builder = TraceBuilder.start("agent-run", runId, List.of(sink));

    builder.span("model.chat", SpanKind.MODEL_CALL).end();
    builder.end();

    assertTrue(
        events.stream().anyMatch(ai.singlr.core.events.HeliosEvent.SpanOpened.class::isInstance),
        "expected SpanOpened");
    assertTrue(
        events.stream().anyMatch(ai.singlr.core.events.HeliosEvent.SpanClosed.class::isInstance),
        "expected SpanClosed");
  }

  @Test
  void sinkExceptionDoesNotPreventSpanCompletion() {
    var runId = ai.singlr.core.common.Ids.newId();
    ai.singlr.core.events.EventSink failing =
        event -> {
          throw new RuntimeException("sink failed");
        };
    var builder = TraceBuilder.start("agent-run", runId, List.of(failing));
    builder.span("model.chat", SpanKind.MODEL_CALL).end();
    var trace = builder.end();
    assertEquals(1, trace.spans().size());
  }

  @Test
  void failWithMixOfOpenAndClosedSpans() {
    var builder = TraceBuilder.start("agent-run");
    var span1 = builder.span("model.chat", SpanKind.MODEL_CALL);
    builder.span("tool.search", SpanKind.TOOL_EXECUTION);

    span1.end();
    var trace = builder.fail("agent crashed");

    assertEquals(2, trace.spans().size());
    assertTrue(trace.spans().get(0).success());
    assertFalse(trace.spans().get(1).success());
  }

  @Test
  void doubleEndThrows() {
    var builder = TraceBuilder.start("agent-run");

    builder.end();

    var ex = assertThrows(IllegalStateException.class, builder::end);
    assertTrue(ex.getMessage().contains("has already ended"));
  }

  @Test
  void attributesRoundTrip() {
    var builder = TraceBuilder.start("agent-run");
    builder.attribute("agent", "test-agent").attribute("model", "gemini");

    var trace = builder.end();

    assertEquals(Map.of("agent", "test-agent", "model", "gemini"), trace.attributes());
  }

  @Test
  void propagatesContextFields() {
    var sessionId = UUID.randomUUID();
    var builder = TraceBuilder.start("agent-run");
    builder
        .inputText("What is 2+2?")
        .outputText("4")
        .userId("user-1")
        .sessionId(sessionId)
        .modelId("gemini-2.0-flash")
        .promptName("math-agent")
        .promptVersion(2)
        .groupId("eval-batch-1")
        .labels(List.of("math", "test"));

    var trace = builder.end();

    assertEquals("What is 2+2?", trace.inputText());
    assertEquals("4", trace.outputText());
    assertEquals("user-1", trace.userId());
    assertEquals(sessionId, trace.sessionId());
    assertEquals("gemini-2.0-flash", trace.modelId());
    assertEquals("math-agent", trace.promptName());
    assertEquals(2, trace.promptVersion());
    assertEquals("eval-batch-1", trace.groupId());
    assertEquals(List.of("math", "test"), trace.labels());
  }

  @Test
  void computesTotalTokensFromModelCallSpans() {
    var builder = TraceBuilder.start("agent-run");
    var span1 = builder.span("model.chat", SpanKind.MODEL_CALL);
    span1.attribute("inputTokens", "100").attribute("outputTokens", "50");
    span1.end();

    var span2 = builder.span("model.chat", SpanKind.MODEL_CALL);
    span2.attribute("inputTokens", "80").attribute("outputTokens", "30");
    span2.end();

    var trace = builder.end();

    assertEquals(260, trace.totalTokens());
  }

  @Test
  void totalTokensIgnoresNonModelCallSpans() {
    var builder = TraceBuilder.start("agent-run");
    var toolSpan = builder.span("tool.search", SpanKind.TOOL_EXECUTION);
    toolSpan.attribute("inputTokens", "999").attribute("outputTokens", "999");
    toolSpan.end();

    var modelSpan = builder.span("model.chat", SpanKind.MODEL_CALL);
    modelSpan.attribute("inputTokens", "10").attribute("outputTokens", "5");
    modelSpan.end();

    var trace = builder.end();

    assertEquals(15, trace.totalTokens());
  }

  @Test
  void malformedTokenAttributeDoesNotAbandonTrace() {
    // A custom span emitter might write a non-integer "inputTokens" attribute (debugging
    // annotation, mistake, etc). computeTotalTokens used to throw NumberFormatException at
    // end-of-trace, abandoning the whole trace artifact for what should be a soft error.
    var builder = TraceBuilder.start("agent-run");

    var validSpan = builder.span("model.chat", SpanKind.MODEL_CALL);
    validSpan.attribute("inputTokens", "10").attribute("outputTokens", "5");
    validSpan.end();

    var malformedSpan = builder.span("model.chat", SpanKind.MODEL_CALL);
    malformedSpan.attribute("inputTokens", "not-a-number").attribute("outputTokens", "also-bad");
    malformedSpan.end();

    var trace = builder.end();

    assertEquals(2, trace.spans().size());
    assertEquals(
        15, trace.totalTokens(), "malformed attributes contribute 0, valid spans still aggregate");
  }

  @Test
  void totalTokensDefaultsToZeroWhenNoSpans() {
    var trace = TraceBuilder.start("agent-run").end();

    assertEquals(0, trace.totalTokens());
  }

  @Test
  void rollsUpUsageAndCostAcrossSpans() {
    var builder = TraceBuilder.start("agent-run");
    builder
        .span("model.chat", SpanKind.MODEL_CALL)
        .usage(Usage.of(100, 50, 20, 10))
        .cost(CostEstimate.ofMicroUsd(1_000L))
        .end();
    builder
        .span("model.chat", SpanKind.MODEL_CALL)
        .usage(Usage.of(80, 30, 0, 40))
        .cost(CostEstimate.ofMicroUsd(500L))
        .end();

    var trace = builder.end();

    assertEquals(Usage.of(180, 80, 20, 50), trace.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_500L), trace.cost());
    assertEquals(330, trace.totalTokens());
  }

  @Test
  void rollupIncludesNestedSpans() {
    var builder = TraceBuilder.start("agent-run");
    var toolSpan = builder.span("tool.search", SpanKind.TOOL_EXECUTION);
    toolSpan
        .span("inner.chat", SpanKind.MODEL_CALL)
        .usage(Usage.of(10, 5))
        .cost(CostEstimate.ofMicroUsd(7L))
        .end();
    toolSpan.end();
    builder.span("model.chat", SpanKind.MODEL_CALL).usage(Usage.of(20, 15)).end();

    var trace = builder.end();

    assertEquals(Usage.of(30, 20, 0, 0), trace.usage());
    assertEquals(CostEstimate.ofMicroUsd(7L), trace.cost());
    assertEquals(50, trace.totalTokens());
  }

  @Test
  void usageAndCostNullWhenNoSpanCarriesThem() {
    var builder = TraceBuilder.start("agent-run");
    var span = builder.span("model.chat", SpanKind.MODEL_CALL);
    span.attribute("inputTokens", "100").attribute("outputTokens", "50");
    span.end();

    var trace = builder.end();

    assertNull(trace.usage());
    assertNull(trace.cost());
    assertEquals(150, trace.totalTokens(), "legacy attribute fallback still totals tokens");
  }

  @Test
  void typedUsageWinsOverAttributesForTotalTokens() {
    var builder = TraceBuilder.start("agent-run");
    var span = builder.span("model.chat", SpanKind.MODEL_CALL);
    span.attribute("inputTokens", "999").attribute("outputTokens", "999");
    span.usage(Usage.of(10, 5));
    span.end();

    var trace = builder.end();

    assertEquals(Usage.of(10, 5), trace.usage());
    assertEquals(15, trace.totalTokens());
  }

  @Test
  void costRollsUpWithoutUsage() {
    var builder = TraceBuilder.start("agent-run");
    builder.span("model.chat", SpanKind.MODEL_CALL).cost(CostEstimate.ofMicroUsd(42L)).end();

    var trace = builder.end();

    assertNull(trace.usage());
    assertEquals(CostEstimate.ofMicroUsd(42L), trace.cost());
  }

  @Test
  void thumbsCountsDefaultToZero() {
    var trace = TraceBuilder.start("agent-run").end();

    assertEquals(0, trace.thumbsUpCount());
    assertEquals(0, trace.thumbsDownCount());
  }
}
