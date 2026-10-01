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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpanTest {

  @Test
  void recordAccessors() {
    var id = UUID.randomUUID();
    var start = OffsetDateTime.now(ZoneOffset.UTC);
    var end = start.plusSeconds(2);
    var duration = Duration.between(start, end);
    var child =
        Span.newBuilder()
            .withName("child")
            .withKind(SpanKind.CUSTOM)
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build();

    var span =
        Span.newBuilder()
            .withId(id)
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(start)
            .withEndTime(end)
            .withDuration(duration)
            .withChild(child)
            .withAttribute("model", "gemini")
            .build();

    assertEquals(id, span.id());
    assertEquals("model.chat", span.name());
    assertEquals(SpanKind.MODEL_CALL, span.kind());
    assertEquals(start, span.startTime());
    assertEquals(end, span.endTime());
    assertEquals(duration, span.duration());
    assertNull(span.error());
    assertEquals(1, span.children().size());
    assertEquals("child", span.children().getFirst().name());
    assertEquals(Map.of("model", "gemini"), span.attributes());
  }

  @Test
  void successReturnsTrueWhenNoError() {
    var span = Span.newBuilder().withName("ok").withKind(SpanKind.CUSTOM).build();

    assertTrue(span.success());
  }

  @Test
  void successReturnsFalseWhenErrorPresent() {
    var span =
        Span.newBuilder().withName("failed").withKind(SpanKind.CUSTOM).withError("boom").build();

    assertFalse(span.success());
    assertEquals("boom", span.error());
  }

  @Test
  void builderRoundTrip() {
    var original =
        Span.newBuilder()
            .withName("original")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withError("err")
            .withAttribute("key", "value")
            .build();

    var copy = Span.newBuilder(original).build();

    assertEquals(original.id(), copy.id());
    assertEquals(original.name(), copy.name());
    assertEquals(original.kind(), copy.kind());
    assertEquals(original.startTime(), copy.startTime());
    assertEquals(original.error(), copy.error());
    assertEquals(original.attributes(), copy.attributes());
  }

  @Test
  void childrenAndAttributesAreImmutable() {
    var span =
        Span.newBuilder()
            .withName("immutable")
            .withKind(SpanKind.CUSTOM)
            .withAttribute("k", "v")
            .build();

    assertThrows(UnsupportedOperationException.class, () -> span.children().clear());
    assertThrows(UnsupportedOperationException.class, () -> span.attributes().put("x", "y"));
  }

  @Test
  void durationComputedFromTimes() {
    var start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var end = start.plus(Duration.ofMillis(500));

    var span =
        Span.newBuilder()
            .withName("timed")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(start)
            .withEndTime(end)
            .build();

    assertNotNull(span.duration());
    assertEquals(Duration.ofMillis(500), span.duration());
  }

  @Test
  void autoGeneratesIdAndStartTime() {
    var span = Span.newBuilder().withName("auto").withKind(SpanKind.CUSTOM).build();

    assertNotNull(span.id());
    assertNotNull(span.startTime());
  }

  @Test
  void builderWithChildrenList() {
    var child1 = Span.newBuilder().withName("c1").withKind(SpanKind.CUSTOM).build();
    var child2 = Span.newBuilder().withName("c2").withKind(SpanKind.CUSTOM).build();

    var span =
        Span.newBuilder()
            .withName("parent")
            .withKind(SpanKind.AGENT)
            .withChildren(List.of(child1, child2))
            .build();

    assertEquals(2, span.children().size());
  }

  @Test
  void builderWithAttributesMap() {
    var attrs = Map.of("a", "1", "b", "2");

    var span =
        Span.newBuilder().withName("attrs").withKind(SpanKind.CUSTOM).withAttributes(attrs).build();

    assertEquals(attrs, span.attributes());
  }
}
