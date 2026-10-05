/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
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
    builder.withChildSpan("model.chat", SpanKind.MODEL_CALL).end();
    builder.withChildSpan("tool.search", SpanKind.TOOL_EXECUTION).end();

    var trace = builder.end();

    assertEquals(2, trace.spans().size());
    assertEquals("model.chat", trace.spans().get(0).name());
    assertEquals("tool.search", trace.spans().get(1).name());
  }

  @Test
  void traceWithNestedSpans() {
    var builder = TraceBuilder.start("agent-run");
    var toolSpan = builder.withChildSpan("tool.search", SpanKind.TOOL_EXECUTION);
    var innerModel = toolSpan.withChildSpan("inner.chat", SpanKind.MODEL_CALL);
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
    builder.withChildSpan("model.chat", SpanKind.MODEL_CALL);

    var ex = assertThrows(IllegalStateException.class, builder::end);
    assertTrue(ex.getMessage().contains("1 span(s) still open"));
  }

  @Test
  void failAutoFailsOpenSpans() {
    var builder = TraceBuilder.start("agent-run");
    builder.withChildSpan("model.chat", SpanKind.MODEL_CALL);
    builder.withChildSpan("tool.search", SpanKind.TOOL_EXECUTION);

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
    var events = new ArrayList<com.standardapplied.helios.core.events.HeliosEvent>();
    var runId = com.standardapplied.helios.core.common.Ids.newId();
    com.standardapplied.helios.core.events.EventSink sink = events::add;
    var builder = TraceBuilder.start("agent-run", runId, List.of(sink));

    builder.withChildSpan("model.chat", SpanKind.MODEL_CALL).end();
    builder.end();

    assertTrue(
        events.stream()
            .anyMatch(
                com.standardapplied.helios.core.events.HeliosEvent.SpanOpened.class::isInstance),
        "expected SpanOpened");
    assertTrue(
        events.stream()
            .anyMatch(
                com.standardapplied.helios.core.events.HeliosEvent.SpanClosed.class::isInstance),
        "expected SpanClosed");
  }

  @Test
  void sinkExceptionDoesNotPreventSpanCompletion() {
    var runId = com.standardapplied.helios.core.common.Ids.newId();
    com.standardapplied.helios.core.events.EventSink failing =
        event -> {
          throw new RuntimeException("sink failed");
        };
    var builder = TraceBuilder.start("agent-run", runId, List.of(failing));
    builder.withChildSpan("model.chat", SpanKind.MODEL_CALL).end();
    var trace = builder.end();
    assertEquals(1, trace.spans().size());
  }

  @Test
  void failWithMixOfOpenAndClosedSpans() {
    var builder = TraceBuilder.start("agent-run");
    var span1 = builder.withChildSpan("model.chat", SpanKind.MODEL_CALL);
    builder.withChildSpan("tool.search", SpanKind.TOOL_EXECUTION);

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
    builder.withAttribute("agent", "test-agent").withAttribute("model", "gemini");

    var trace = builder.end();

    assertEquals(Map.of("agent", "test-agent", "model", "gemini"), trace.attributes());
  }

  @Test
  void propagatesContextFields() {
    var sessionId = UUID.randomUUID();
    var builder = TraceBuilder.start("agent-run");
    builder
        .withInputText("What is 2+2?")
        .withOutputText("4")
        .withUserId("user-1")
        .withSessionId(sessionId)
        .withModelId("gemini-2.0-flash")
        .withPromptName("math-agent")
        .withPromptVersion(2)
        .withGroupId("eval-batch-1")
        .withLabels(List.of("math", "test"));

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
  void totalTokensDefaultsToZeroWhenNoSpans() {
    var trace = TraceBuilder.start("agent-run").end();

    assertEquals(0, trace.totalTokens());
  }

  @Test
  void rollsUpUsageAndCostAcrossSpans() {
    var builder = TraceBuilder.start("agent-run");
    builder
        .withChildSpan("model.chat", SpanKind.MODEL_CALL)
        .withUsage(Usage.of(100, 50, 20, 10))
        .withCost(CostEstimate.ofMicroUsd(1_000L))
        .end();
    builder
        .withChildSpan("model.chat", SpanKind.MODEL_CALL)
        .withUsage(Usage.of(80, 30, 0, 40))
        .withCost(CostEstimate.ofMicroUsd(500L))
        .end();

    var trace = builder.end();

    assertEquals(Usage.of(180, 80, 20, 50), trace.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_500L), trace.cost());
    assertEquals(330, trace.totalTokens());
  }

  @Test
  void rollupIncludesNestedSpans() {
    var builder = TraceBuilder.start("agent-run");
    var toolSpan = builder.withChildSpan("tool.search", SpanKind.TOOL_EXECUTION);
    toolSpan
        .withChildSpan("inner.chat", SpanKind.MODEL_CALL)
        .withUsage(Usage.of(10, 5))
        .withCost(CostEstimate.ofMicroUsd(7L))
        .end();
    toolSpan.end();
    builder.withChildSpan("model.chat", SpanKind.MODEL_CALL).withUsage(Usage.of(20, 15)).end();

    var trace = builder.end();

    assertEquals(Usage.of(30, 20, 0, 0), trace.usage());
    assertEquals(CostEstimate.ofMicroUsd(7L), trace.cost());
    assertEquals(50, trace.totalTokens());
  }

  @Test
  void usageCostAndTotalTokensAbsentWhenNoSpanCarriesTypedUsage() {
    var builder = TraceBuilder.start("agent-run");
    var span = builder.withChildSpan("model.chat", SpanKind.MODEL_CALL);
    span.withAttribute("inputTokens", "100").withAttribute("outputTokens", "50");
    span.end();

    var trace = builder.end();

    assertNull(trace.usage());
    assertNull(trace.cost());
    assertEquals(0, trace.totalTokens(), "token attributes are not a source of totals");
  }

  @Test
  void totalTokensCountsOnlyTypedUsage() {
    var builder = TraceBuilder.start("agent-run");
    builder.withChildSpan("model.chat", SpanKind.MODEL_CALL).withUsage(Usage.of(10, 5)).end();
    var attributed = builder.withChildSpan("model.chat", SpanKind.MODEL_CALL);
    attributed.withAttribute("inputTokens", "100").withAttribute("outputTokens", "50");
    attributed.end();

    var trace = builder.end();

    assertEquals(Usage.of(10, 5), trace.usage());
    assertEquals(15, trace.totalTokens());
  }

  @Test
  void costRollsUpWithoutUsage() {
    var builder = TraceBuilder.start("agent-run");
    builder
        .withChildSpan("model.chat", SpanKind.MODEL_CALL)
        .withCost(CostEstimate.ofMicroUsd(42L))
        .end();

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
