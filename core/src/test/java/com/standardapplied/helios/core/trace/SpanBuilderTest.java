/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.trace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.events.HeliosEvent;
import com.standardapplied.helios.core.model.Response.Usage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpanBuilderTest {

  @Test
  void spanEventsWithNullSinksStaySilent() {
    var events = new SpanEvents(null, Ids.newId());
    var span = Span.newBuilder().withName("s").build();

    assertDoesNotThrow(() -> events.opened(span.id(), null, "s"));
    assertDoesNotThrow(() -> events.closed(span));
  }

  @Test
  void spanClosedOfASpanWithoutDurationCarriesZero() {
    var received = new ArrayList<HeliosEvent>();
    var runId = Ids.newId();
    var span = Span.newBuilder().withName("s").build();

    new SpanEvents(List.of(received::add), runId).closed(span);

    var closed = assertInstanceOf(HeliosEvent.SpanClosed.class, received.getFirst());
    assertEquals(1, received.size());
    assertEquals(runId, closed.runId());
    assertEquals(span.id(), closed.closedSpanId());
    assertEquals(Duration.ZERO, closed.duration());
    assertTrue(closed.success());
    assertEquals(Optional.empty(), closed.error());
  }

  @Test
  void spanCarriesUsageAndCost() {
    var trace = TraceBuilder.start("test");
    var span =
        trace
            .withChildSpan("model.chat", SpanKind.MODEL_CALL)
            .withUsage(Usage.of(100, 50, 20, 10))
            .withCost(CostEstimate.ofMicroUsd(1_250L))
            .end();

    assertEquals(Usage.of(100, 50, 20, 10), span.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_250L), span.cost());
  }

  @Test
  void usageAndCostDefaultToNull() {
    var span =
        TraceBuilder.start("test").withChildSpan("tool.search", SpanKind.TOOL_EXECUTION).end();

    assertNull(span.usage());
    assertNull(span.cost());
  }

  @Test
  void usageAfterEndThrows() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.withChildSpan("model.chat", SpanKind.MODEL_CALL);
    spanBuilder.end();

    assertThrows(IllegalStateException.class, () -> spanBuilder.withUsage(Usage.of(1, 1)));
    assertThrows(IllegalStateException.class, () -> spanBuilder.withCost(CostEstimate.zero()));
  }

  @Test
  void createAndEndSpan() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.withChildSpan("model.chat", SpanKind.MODEL_CALL);

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
    var spanBuilder = trace.withChildSpan("model.chat", SpanKind.MODEL_CALL);

    spanBuilder.withAttribute("model", "gemini").withAttribute("tokens", "150");
    var span = spanBuilder.end();

    assertEquals(Map.of("model", "gemini", "tokens", "150"), span.attributes());
  }

  @Test
  void spanWithChildSpans() {
    var trace = TraceBuilder.start("test");
    var parent = trace.withChildSpan("tool.search", SpanKind.TOOL_EXECUTION);
    var child = parent.withChildSpan("inner.chat", SpanKind.MODEL_CALL);

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
    var spanBuilder = trace.withChildSpan("model.chat", SpanKind.MODEL_CALL);

    var span = spanBuilder.fail("connection timeout");

    assertFalse(span.success());
    assertEquals("connection timeout", span.error());
  }

  @Test
  void endThrowsIfChildrenStillOpen() {
    var trace = TraceBuilder.start("test");
    var parent = trace.withChildSpan("parent", SpanKind.AGENT);
    parent.withChildSpan("child", SpanKind.MODEL_CALL);

    var ex = assertThrows(IllegalStateException.class, parent::end);
    assertTrue(ex.getMessage().contains("1 child span(s) still open"));
  }

  @Test
  void failAutoFailsOpenChildren() {
    var trace = TraceBuilder.start("test");
    var parent = trace.withChildSpan("parent", SpanKind.AGENT);
    parent.withChildSpan("child1", SpanKind.MODEL_CALL);
    parent.withChildSpan("child2", SpanKind.TOOL_EXECUTION);

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
    var spanBuilder = trace.withChildSpan("span", SpanKind.CUSTOM);

    spanBuilder.end();

    var ex = assertThrows(IllegalStateException.class, spanBuilder::end);
    assertTrue(ex.getMessage().contains("has already ended"));
  }

  @Test
  void spanAfterEndThrows() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.withChildSpan("span", SpanKind.CUSTOM);

    spanBuilder.end();

    var ex =
        assertThrows(
            IllegalStateException.class, () -> spanBuilder.withChildSpan("child", SpanKind.CUSTOM));
    assertTrue(ex.getMessage().contains("has already ended"));
  }

  @Test
  void attributeAfterEndThrows() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.withChildSpan("span", SpanKind.CUSTOM);

    spanBuilder.end();

    var ex =
        assertThrows(IllegalStateException.class, () -> spanBuilder.withAttribute("key", "value"));
    assertTrue(ex.getMessage().contains("has already ended"));
  }

  @Test
  void failWithMixOfOpenAndClosedChildren() {
    var trace = TraceBuilder.start("test");
    var parent = trace.withChildSpan("parent", SpanKind.AGENT);
    var child1 = parent.withChildSpan("child1", SpanKind.MODEL_CALL);
    parent.withChildSpan("child2", SpanKind.TOOL_EXECUTION);

    child1.end();
    var span = parent.fail("parent failed");

    assertEquals(2, span.children().size());
    assertTrue(span.children().get(0).success());
    assertFalse(span.children().get(1).success());
  }

  @Test
  void durationIsNonNegative() {
    var trace = TraceBuilder.start("test");
    var spanBuilder = trace.withChildSpan("span", SpanKind.CUSTOM);

    var span = spanBuilder.end();

    assertNotNull(span.duration());
    assertFalse(span.duration().isNegative());
  }
}
