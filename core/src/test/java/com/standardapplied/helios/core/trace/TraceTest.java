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

class TraceTest {

  @Test
  void recordAccessors() {
    var id = UUID.randomUUID();
    var start = OffsetDateTime.now(ZoneOffset.UTC);
    var end = start.plusSeconds(5);
    var duration = Duration.between(start, end);
    var span = Span.newBuilder().withName("child-span").withKind(SpanKind.MODEL_CALL).build();

    var trace =
        Trace.newBuilder()
            .withId(id)
            .withName("agent-run")
            .withStartTime(start)
            .withEndTime(end)
            .withDuration(duration)
            .withSpan(span)
            .withAttribute("agent", "test")
            .build();

    assertEquals(id, trace.id());
    assertEquals("agent-run", trace.name());
    assertEquals(start, trace.startTime());
    assertEquals(end, trace.endTime());
    assertEquals(duration, trace.duration());
    assertNull(trace.error());
    assertEquals(1, trace.spans().size());
    assertEquals("child-span", trace.spans().getFirst().name());
    assertEquals(Map.of("agent", "test"), trace.attributes());
  }

  @Test
  void successDerivedMethod() {
    var success = Trace.newBuilder().withName("ok").build();
    assertTrue(success.success());

    var failure = Trace.newBuilder().withName("fail").withError("boom").build();
    assertFalse(failure.success());
    assertEquals("boom", failure.error());
  }

  @Test
  void builderRoundTrip() {
    var original =
        Trace.newBuilder()
            .withName("original")
            .withError("err")
            .withAttribute("key", "value")
            .build();

    var copy = Trace.newBuilder(original).build();

    assertEquals(original.id(), copy.id());
    assertEquals(original.name(), copy.name());
    assertEquals(original.startTime(), copy.startTime());
    assertEquals(original.error(), copy.error());
    assertEquals(original.attributes(), copy.attributes());
  }

  @Test
  void spansAndAttributesAreImmutable() {
    var trace = Trace.newBuilder().withName("immutable").withAttribute("k", "v").build();

    assertThrows(UnsupportedOperationException.class, () -> trace.spans().clear());
    assertThrows(UnsupportedOperationException.class, () -> trace.attributes().put("x", "y"));
  }

  @Test
  void durationComputedFromTimes() {
    var start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var end = start.plusSeconds(3);

    var trace = Trace.newBuilder().withName("timed").withStartTime(start).withEndTime(end).build();

    assertNotNull(trace.duration());
    assertEquals(Duration.ofSeconds(3), trace.duration());
  }

  @Test
  void autoGeneratesIdAndStartTime() {
    var trace = Trace.newBuilder().withName("auto").build();

    assertNotNull(trace.id());
    assertNotNull(trace.startTime());
  }

  @Test
  void builderWithExplicitDuration() {
    var start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var end = start.plusSeconds(10);
    var customDuration = Duration.ofSeconds(10);

    var trace =
        Trace.newBuilder()
            .withName("explicit")
            .withStartTime(start)
            .withEndTime(end)
            .withDuration(customDuration)
            .build();

    assertEquals(customDuration, trace.duration());
  }

  @Test
  void builderWithAttributesMap() {
    var attrs = Map.of("a", "1", "b", "2");

    var trace = Trace.newBuilder().withName("attrs").withAttributes(attrs).build();

    assertEquals(attrs, trace.attributes());
  }

  @Test
  void builderWithSpansList() {
    var s1 = Span.newBuilder().withName("s1").withKind(SpanKind.CUSTOM).build();
    var s2 = Span.newBuilder().withName("s2").withKind(SpanKind.CUSTOM).build();

    var trace = Trace.newBuilder().withName("multi").withSpans(List.of(s1, s2)).build();

    assertEquals(2, trace.spans().size());
  }

  @Test
  void newFieldsDefaultToNullOrZero() {
    var trace = Trace.newBuilder().withName("minimal").build();

    assertNull(trace.inputText());
    assertNull(trace.outputText());
    assertNull(trace.userId());
    assertNull(trace.sessionId());
    assertNull(trace.modelId());
    assertNull(trace.promptName());
    assertNull(trace.promptVersion());
    assertEquals(0, trace.totalTokens());
    assertEquals(0, trace.thumbsUpCount());
    assertEquals(0, trace.thumbsDownCount());
    assertNull(trace.groupId());
    assertTrue(trace.labels().isEmpty());
  }

  @Test
  void newFieldsRoundTrip() {
    var sessionId = UUID.randomUUID();
    var original =
        Trace.newBuilder()
            .withName("full")
            .withInputText("Hello")
            .withOutputText("Hi there")
            .withUserId("user-1")
            .withSessionId(sessionId)
            .withModelId("gemini-2.0-flash")
            .withPromptName("agent-prompt")
            .withPromptVersion(3)
            .withTotalTokens(150)
            .withThumbsUpCount(5)
            .withThumbsDownCount(2)
            .withGroupId("eval-batch-1")
            .withLabels(List.of("production", "v2"))
            .build();

    var copy = Trace.newBuilder(original).build();

    assertEquals("Hello", copy.inputText());
    assertEquals("Hi there", copy.outputText());
    assertEquals("user-1", copy.userId());
    assertEquals(sessionId, copy.sessionId());
    assertEquals("gemini-2.0-flash", copy.modelId());
    assertEquals("agent-prompt", copy.promptName());
    assertEquals(3, copy.promptVersion());
    assertEquals(150, copy.totalTokens());
    assertEquals(5, copy.thumbsUpCount());
    assertEquals(2, copy.thumbsDownCount());
    assertEquals("eval-batch-1", copy.groupId());
    assertEquals(List.of("production", "v2"), copy.labels());
  }

  @Test
  void labelsAreImmutable() {
    var trace = Trace.newBuilder().withName("immutable").withLabels(List.of("a", "b")).build();

    assertThrows(UnsupportedOperationException.class, () -> trace.labels().add("c"));
  }
}
