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
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanTest {

  private static final OffsetDateTime START =
      OffsetDateTime.of(2026, 11, 3, 10, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void theBuilderSetsEveryComponentAndTheDuration() {
    var span =
        Span.newBuilder()
            .withId("1000000000000001")
            .withName("chat scripted")
            .withKind("chat")
            .withStartTime(START)
            .withEndTime(START.plusNanos(250_000_000))
            .withError("IllegalStateException: broken")
            .withAttributes(Map.of("helios.attempt", "1"))
            .withUsage(Usage.of(10, 5))
            .withCost(CostEstimate.ofMicroUsd(3))
            .build();

    assertEquals("1000000000000001", span.id());
    assertEquals("chat scripted", span.name());
    assertEquals("chat", span.kind());
    assertEquals(START, span.startTime());
    assertEquals(START.plusNanos(250_000_000), span.endTime());
    assertEquals(Duration.ofMillis(250), span.duration());
    assertEquals("IllegalStateException: broken", span.error());
    assertFalse(span.success());
    assertEquals(Map.of("helios.attempt", "1"), span.attributes());
    assertEquals(Usage.of(10, 5), span.usage());
    assertEquals(CostEstimate.ofMicroUsd(3), span.cost());
    assertThrows(UnsupportedOperationException.class, () -> span.attributes().clear());
  }

  @Test
  void aSpanWithoutBothTimesHasNoDurationAndWithoutAnErrorSucceeded() {
    var span = Span.newBuilder().withId("1000000000000001").withStartTime(START).build();

    assertNull(span.duration());
    assertNull(Span.newBuilder().withEndTime(START).build().duration());
    assertTrue(span.success());
  }

  @Test
  void aHostSpansScopeIsItsStoredScopeAttributeAndAnyOtherSpansIsHelios() {
    var host =
        Span.newBuilder()
            .withKind("host")
            .withAttributes(Map.of("otel.scope.name", "light-grid"))
            .build();

    assertEquals("light-grid", host.scope());
    assertEquals(
        "com.standardapplied.helios",
        Span.newBuilder()
            .withKind("chat")
            .withAttributes(Map.of("otel.scope.name", "light-grid"))
            .build()
            .scope());
  }
}
