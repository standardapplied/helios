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
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanBuilderTest {

  @Test
  void spanCarriesUsageAndCost() {
    var trace = TraceBuilder.start("test");
    var span =
        trace
            .span("model.chat", SpanKind.MODEL_CALL)
            .usage(Usage.of(100, 50, 20, 10))
            .cost(CostEstimate.ofMicroUsd(1_250L))
            .end();

    assertEquals(Usage.of(100, 50, 20, 10), span.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_250L), span.cost());
  }

  @Test
  void usageAndCostDefaultToNull() {
    var span = TraceBuilder.start("test").span("tool.search", SpanKind.TOOL_EXECUTION).end();

    assertNull(span.usage());
    assertNull(span.cost());
  }

  @Test
  void usageAfterEndThrows() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.span("model.chat", SpanKind.MODEL_CALL);
    spanBuilder.end();

    assertThrows(IllegalStateException.class, () -> spanBuilder.usage(Usage.of(1, 1)));
    assertThrows(IllegalStateException.class, () -> spanBuilder.cost(CostEstimate.zero()));
  }

  @Test
  void createAndEndSpan() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.span("model.chat", SpanKind.MODEL_CALL);

    var span = spanBuilder.end();

    assertNotNull(span.id());
    assertEquals("model.chat", span.name());
    assertEquals(SpanKind.MODEL_CALL, span.kind());
    assertNotNull(span.startTime());
    assertNotNull(span.endTime());
    assertNotNull(span.duration());
    assertNull(span.error());
    assertTrue(span.success());
    assertTrue(span.children().isEmpty());
    assertTrue(span.attributes().isEmpty());
  }

  @Test
  void spanWithAttributes() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.span("model.chat", SpanKind.MODEL_CALL);

    spanBuilder.attribute("model", "gemini").attribute("tokens", "150");
    var span = spanBuilder.end();

    assertEquals(Map.of("model", "gemini", "tokens", "150"), span.attributes());
  }

  @Test
  void spanWithChildSpans() {
    var trace = TraceBuilder.start("test");
    var parent = trace.span("tool.search", SpanKind.TOOL_EXECUTION);
    var child = parent.span("inner.chat", SpanKind.MODEL_CALL);

    child.end();
    var span = parent.end();

    assertEquals(1, span.children().size());
    assertEquals("inner.chat", span.children().getFirst().name());
    assertEquals(SpanKind.MODEL_CALL, span.children().getFirst().kind());
    assertTrue(span.children().getFirst().success());
  }

  @Test
  void failRecordsError() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.span("model.chat", SpanKind.MODEL_CALL);

    var span = spanBuilder.fail("connection timeout");

    assertFalse(span.success());
    assertEquals("connection timeout", span.error());
  }

  @Test
  void endThrowsIfChildrenStillOpen() {
    var trace = TraceBuilder.start("test");
    var parent = trace.span("parent", SpanKind.AGENT);
    parent.span("child", SpanKind.MODEL_CALL);

    var ex = assertThrows(IllegalStateException.class, parent::end);
    assertTrue(ex.getMessage().contains("1 child span(s) still open"));
  }

  @Test
  void failAutoFailsOpenChildren() {
    var trace = TraceBuilder.start("test");
    var parent = trace.span("parent", SpanKind.AGENT);
    parent.span("child1", SpanKind.MODEL_CALL);
    parent.span("child2", SpanKind.TOOL_EXECUTION);

    var span = parent.fail("parent failed");

    assertEquals(2, span.children().size());
    for (var child : span.children()) {
      assertFalse(child.success());
      assertTrue(child.error().contains("Parent span 'parent' failed"));
    }
  }

  @Test
  void doubleEndThrows() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.span("span", SpanKind.CUSTOM);

    spanBuilder.end();

    var ex = assertThrows(IllegalStateException.class, spanBuilder::end);
    assertTrue(ex.getMessage().contains("has already ended"));
  }

  @Test
  void spanAfterEndThrows() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.span("span", SpanKind.CUSTOM);

    spanBuilder.end();

    var ex =
        assertThrows(IllegalStateException.class, () -> spanBuilder.span("child", SpanKind.CUSTOM));
    assertTrue(ex.getMessage().contains("has already ended"));
  }

  @Test
  void attributeAfterEndThrows() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.span("span", SpanKind.CUSTOM);

    spanBuilder.end();

    var ex = assertThrows(IllegalStateException.class, () -> spanBuilder.attribute("key", "value"));
    assertTrue(ex.getMessage().contains("has already ended"));
  }

  @Test
  void failWithMixOfOpenAndClosedChildren() {
    var trace = TraceBuilder.start("test");
    var parent = trace.span("parent", SpanKind.AGENT);
    var child1 = parent.span("child1", SpanKind.MODEL_CALL);
    parent.span("child2", SpanKind.TOOL_EXECUTION);

    child1.end();
    var span = parent.fail("parent failed");

    assertEquals(2, span.children().size());
    assertTrue(span.children().get(0).success());
    assertFalse(span.children().get(1).success());
  }

  @Test
  void durationIsNonNegative() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.span("span", SpanKind.CUSTOM);

    var span = spanBuilder.end();

    assertNotNull(span.duration());
    assertFalse(span.duration().isNegative());
  }
}
