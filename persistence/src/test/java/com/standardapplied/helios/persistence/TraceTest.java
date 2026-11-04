/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceTest {

  private static final OffsetDateTime START =
      OffsetDateTime.of(2026, 11, 3, 10, 0, 0, 0, ZoneOffset.UTC);

  private static Trace full() {
    return Trace.newBuilder()
        .withId("run-1")
        .withOtelTraceId("0af7651916cd43dd8448eb211c80319c")
        .withOtelSpanId("00f067aa0ba902b7")
        .withName("matchmaking")
        .withStartTime(START)
        .withEndTime(START.plusSeconds(5))
        .withError("ErrorMaxTurns")
        .withSpans(List.of(Span.newBuilder().withId("1000000000000001").withKind("chat").build()))
        .withAttributes(Map.of("helios.terminal", "ErrorMaxTurns"))
        .withInputText("hi")
        .withOutputText("bye")
        .withSessionId("sess-1")
        .withModelId("scripted")
        .withTotalTokens(15)
        .withUsage(Usage.of(10, 5))
        .withCost(CostEstimate.ofMicroUsd(7))
        .withThumbsUpCount(2)
        .withThumbsDownCount(1)
        .build();
  }

  @Test
  void theBuilderSetsEveryComponentAndTheDuration() {
    var trace = full();

    assertEquals("run-1", trace.id());
    assertEquals("0af7651916cd43dd8448eb211c80319c", trace.otelTraceId());
    assertEquals("00f067aa0ba902b7", trace.otelSpanId());
    assertEquals("matchmaking", trace.name());
    assertEquals(Duration.ofSeconds(5), trace.duration());
    assertEquals("ErrorMaxTurns", trace.error());
    assertFalse(trace.success());
    assertEquals("chat", trace.spans().getFirst().kind());
    assertEquals("ErrorMaxTurns", trace.attributes().get("helios.terminal"));
    assertEquals("hi", trace.inputText());
    assertEquals("bye", trace.outputText());
    assertEquals("sess-1", trace.sessionId());
    assertEquals("scripted", trace.modelId());
    assertEquals(15, trace.totalTokens());
    assertEquals(Usage.of(10, 5), trace.usage());
    assertEquals(CostEstimate.ofMicroUsd(7), trace.cost());
    assertEquals(2, trace.thumbsUpCount());
    assertEquals(1, trace.thumbsDownCount());
  }

  @Test
  void aBuilderFromATraceCopiesIt() {
    var trace = full();

    assertEquals(trace, Trace.newBuilder(trace).build());
  }

  @Test
  void aTraceWithoutBothTimesHasNoDurationAndWithoutAnErrorSucceeded() {
    var trace = Trace.newBuilder().withId("run-1").withStartTime(START).build();

    assertNull(trace.duration());
    assertNull(Trace.newBuilder().withEndTime(START).build().duration());
    assertTrue(trace.success());
  }

  @Test
  void spansAndAttributesAreImmutable() {
    var trace = full();

    assertThrows(UnsupportedOperationException.class, () -> trace.spans().clear());
    assertThrows(UnsupportedOperationException.class, () -> trace.attributes().clear());
  }
}
