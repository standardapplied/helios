/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Paginate;
import ai.singlr.core.trace.Annotation;
import ai.singlr.core.trace.AuthorKind;
import ai.singlr.core.trace.Span;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgTraceStoreTest {

  private PgTraceStore store;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateTraces();
    store = new PgTraceStore(PgTestSupport.pgConfig());
  }

  @Test
  void storeAndFindMinimalTrace() {
    var start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var end = start.plusSeconds(5);
    var trace =
        Trace.newBuilder().withName("agent-run").withStartTime(start).withEndTime(end).build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertNotNull(found);
    assertEquals(trace.id(), found.id());
    assertEquals("agent-run", found.name());
    assertEquals(start, found.startTime());
    assertEquals(end, found.endTime());
    assertNull(found.error());
    assertTrue(found.success());
    assertTrue(found.spans().isEmpty());
    assertTrue(found.attributes().isEmpty());
  }

  @Test
  void storeTraceWithAttributes() {
    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1))
            .withAttribute("agent", "test-agent")
            .withAttribute("model", "gemini")
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(Map.of("agent", "test-agent", "model", "gemini"), found.attributes());
  }

  @Test
  void storeTraceWithError() {
    var trace =
        Trace.newBuilder()
            .withName("failed-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withError("model unavailable")
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertFalse(found.success());
    assertEquals("model unavailable", found.error());
  }

  @Test
  void storeTraceWithSingleSpan() {
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2))
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3))
            .withSpan(span)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(1, found.spans().size());
    assertEquals("model.chat", found.spans().getFirst().name());
    assertEquals(SpanKind.MODEL_CALL, found.spans().getFirst().kind());
    assertTrue(found.spans().getFirst().success());
  }

  @Test
  void storeTraceWithMultipleTopLevelSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span1 =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var span2 =
        Span.newBuilder()
            .withName("tool.search")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(now.plusSeconds(1))
            .withEndTime(now.plusSeconds(2))
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(3))
            .withSpan(span1)
            .withSpan(span2)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(2, found.spans().size());
    assertEquals("model.chat", found.spans().get(0).name());
    assertEquals("tool.search", found.spans().get(1).name());
  }

  @Test
  void storeTraceWithNestedSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var childSpan =
        Span.newBuilder()
            .withName("inner.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var parentSpan =
        Span.newBuilder()
            .withName("tool.search")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withChild(childSpan)
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(3))
            .withSpan(parentSpan)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(1, found.spans().size());
    var tool = found.spans().getFirst();
    assertEquals("tool.search", tool.name());
    assertEquals(1, tool.children().size());
    assertEquals("inner.chat", tool.children().getFirst().name());
    assertEquals(SpanKind.MODEL_CALL, tool.children().getFirst().kind());
  }

  @Test
  void spanKindRoundTrip() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var trace =
        Trace.newBuilder()
            .withName("kinds")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(5))
            .build();
    var traceBuilder = Trace.newBuilder(trace);

    for (var kind : SpanKind.values()) {
      traceBuilder.withSpan(
          Span.newBuilder()
              .withName("span-" + kind.name())
              .withKind(kind)
              .withStartTime(now)
              .withEndTime(now.plusSeconds(1))
              .build());
    }

    var traceWithSpans = traceBuilder.build();
    store.store(traceWithSpans);
    var found = store.findById(traceWithSpans.id());

    assertEquals(SpanKind.values().length, found.spans().size());
    for (int i = 0; i < SpanKind.values().length; i++) {
      assertEquals(SpanKind.values()[i], found.spans().get(i).kind());
    }
  }

  @Test
  void spanAttributesRoundTrip() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .withAttribute("model", "gemini")
            .withAttribute("tokens", "150")
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withSpan(span)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(Map.of("model", "gemini", "tokens", "150"), found.spans().getFirst().attributes());
  }

  @Test
  void spanWithError() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .withError("connection timeout")
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withSpan(span)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertFalse(found.spans().getFirst().success());
    assertEquals("connection timeout", found.spans().getFirst().error());
  }

  @Test
  void findNonExistentTraceReturnsNull() {
    assertNull(store.findById(UUID.randomUUID()));
  }

  @Test
  void storeAndFindAnnotationWithAllFields() {
    var subjectId = UUID.randomUUID();
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withFacet("relevance")
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withAuthorId("reviewer-1")
            .withRating(1)
            .withComment("Great response")
            .withMetadata(Map.of("groupId", "g-7", "weight", 3))
            .build();

    store.storeAnnotation(annotation);
    var found = store.findAnnotationsBySubject(subjectId);

    assertEquals(1, found.size());
    var a = found.getFirst();
    assertEquals(annotation.id(), a.id());
    assertEquals(subjectId, a.subjectId());
    assertEquals("relevance", a.facet());
    assertEquals("quality", a.label());
    assertEquals(AuthorKind.HUMAN, a.authorKind());
    assertEquals("reviewer-1", a.authorId());
    assertEquals(1, a.rating());
    assertEquals("Great response", a.comment());
    assertEquals("g-7", a.metadata().get("groupId"));
    assertEquals(3, a.metadata().get("weight"));
    assertNotNull(a.createdAt());
    assertEquals(a.createdAt(), a.updatedAt());
  }

  @Test
  void storeAnnotationWithNullableFields() {
    var subjectId = UUID.randomUUID();
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("flag")
            .withAuthorKind(AuthorKind.SYSTEM)
            .build();

    store.storeAnnotation(annotation);
    var found = store.findAnnotationsBySubject(subjectId);

    assertEquals(1, found.size());
    assertNull(found.getFirst().facet());
    assertNull(found.getFirst().rating());
    assertNull(found.getFirst().comment());
    assertNull(found.getFirst().authorId());
    assertTrue(found.getFirst().metadata().isEmpty());
  }

  @Test
  void multipleAnnotationsForSameSubject() {
    var subjectId = UUID.randomUUID();
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .build());
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("relevance")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(-1)
            .build());

    var found = store.findAnnotationsBySubject(subjectId);
    assertEquals(2, found.size());
  }

  @Test
  void findAnnotationsForNonExistentSubjectReturnsEmpty() {
    assertTrue(store.findAnnotationsBySubject(UUID.randomUUID()).isEmpty());
  }

  @Test
  void metadataRoundTripsThroughJsonb() {
    var subjectId = UUID.randomUUID();
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("context")
            .withAuthorKind(AuthorKind.MODEL)
            .withAuthorId("judge-1")
            .withMetadata(Map.of("context", Map.of("groupId", "g-9"), "tags", List.of("a", "b")))
            .build());

    var found = store.findAnnotationsBySubject(subjectId).getFirst();
    assertEquals(Map.of("groupId", "g-9"), found.metadata().get("context"));
    assertEquals(List.of("a", "b"), found.metadata().get("tags"));
  }

  @Test
  void findAnnotationsBySubjectsBatchesAcrossSubjects() {
    var subjectA = UUID.randomUUID();
    var subjectB = UUID.randomUUID();
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectA)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .build());
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectB)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .build());

    var found = store.findAnnotationsBySubjects(List.of(subjectA, subjectB, UUID.randomUUID()));
    assertEquals(2, found.size());
  }

  @Test
  void findAnnotationsBySubjectsEmptyInputReturnsEmpty() {
    assertTrue(store.findAnnotationsBySubjects(List.of()).isEmpty());
    assertTrue(store.findAnnotationsBySubjects(null).isEmpty());
  }

  // --- Annotation error paths (DML/query failures wrap as PgException) ---

  @Test
  void annotationWritesAndReadsWrapDbErrorsAsPgException() {
    var broken =
        new PgTraceStore(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withSchema("no_such_schema")
                .build());
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(UUID.randomUUID())
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .build();

    assertThrows(PgException.class, () -> broken.storeAnnotation(annotation));
    assertThrows(PgException.class, () -> broken.upsertAnnotation(annotation));
    assertThrows(PgException.class, () -> broken.findAnnotationsBySubject(UUID.randomUUID()));
    assertThrows(
        PgException.class, () -> broken.findAnnotationsBySubjects(List.of(UUID.randomUUID())));
    assertThrows(PgException.class, () -> broken.listAnnotations(Paginate.of(), null));
  }

  @Test
  void listAnnotationsWithInvalidFilterThrows() {
    assertThrows(
        PgException.class,
        () -> store.listAnnotations(Paginate.of(), "invalid filter gibberish!!!"));
  }

  @Test
  void listAnnotationsPaginatesWithoutFilter() {
    var subjectId = UUID.randomUUID();
    for (var label : List.of("a", "b", "c")) {
      store.storeAnnotation(
          Annotation.newBuilder()
              .withSubjectId(subjectId)
              .withLabel(label)
              .withAuthorKind(AuthorKind.HUMAN)
              .build());
    }

    var page = store.listAnnotations(new Paginate(1, 2), null);
    assertEquals(2, page.items().size());
    assertTrue(page.hasMore());
  }

  @Test
  void listAnnotationsFiltersByScimOverFirstClassFields() {
    var subjectId = UUID.randomUUID();
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("relevance")
            .withAuthorKind(AuthorKind.MODEL)
            .withAuthorId("judge-1")
            .build());
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("mutuality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withAuthorId("ceo")
            .build());

    var filtered = store.listAnnotations(null, "label eq \"relevance\"");
    assertEquals(1, filtered.items().size());
    assertEquals("relevance", filtered.items().getFirst().label());
    assertEquals(AuthorKind.MODEL, filtered.items().getFirst().authorKind());
  }

  @Test
  void onTraceStoresTraceAndSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();

    var trace =
        Trace.newBuilder()
            .withName("listener-trace")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withSpan(span)
            .build();

    store.onEvent(
        new ai.singlr.core.events.HeliosEvent.RunCompleted(
            java.time.Instant.now(),
            ai.singlr.core.common.Ids.newId(),
            java.util.Optional.empty(),
            trace));
    var found = store.findById(trace.id());

    assertNotNull(found);
    assertEquals("listener-trace", found.name());
    assertEquals(1, found.spans().size());
  }

  @Test
  void deeplyNestedSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var grandchild =
        Span.newBuilder()
            .withName("grandchild")
            .withKind(SpanKind.CUSTOM)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var child =
        Span.newBuilder()
            .withName("child")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withChild(grandchild)
            .build();
    var parent =
        Span.newBuilder()
            .withName("parent")
            .withKind(SpanKind.AGENT)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(3))
            .withChild(child)
            .build();

    var trace =
        Trace.newBuilder()
            .withName("deep-trace")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(4))
            .withSpan(parent)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(1, found.spans().size());
    var p = found.spans().getFirst();
    assertEquals("parent", p.name());
    assertEquals(1, p.children().size());
    var c = p.children().getFirst();
    assertEquals("child", c.name());
    assertEquals(1, c.children().size());
    assertEquals("grandchild", c.children().getFirst().name());
  }

  @Test
  void mixOfSuccessfulAndFailedSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var successSpan =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var failedSpan =
        Span.newBuilder()
            .withName("tool.search")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(now.plusSeconds(1))
            .withEndTime(now.plusSeconds(2))
            .withError("tool crashed")
            .build();

    var trace =
        Trace.newBuilder()
            .withName("mixed-trace")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(3))
            .withSpan(successSpan)
            .withSpan(failedSpan)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(2, found.spans().size());
    assertTrue(found.spans().get(0).success());
    assertFalse(found.spans().get(1).success());
    assertEquals("tool crashed", found.spans().get(1).error());
  }

  @Test
  void durationComputedFromTimes() {
    var start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var end = start.plusSeconds(7);
    var trace = Trace.newBuilder().withName("timed").withStartTime(start).withEndTime(end).build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertNotNull(found.duration());
    assertEquals(Duration.ofSeconds(7), found.duration());
  }

  // --- New field round-trip tests ---

  @Test
  void storeAndFindTraceWithAllNewFields() {
    var start = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    var end = start.plusSeconds(10);
    var sessionId = UUID.randomUUID();
    var trace =
        Trace.newBuilder()
            .withName("full-trace")
            .withStartTime(start)
            .withEndTime(end)
            .withInputText("Hello agent")
            .withOutputText("Hi there, how can I help?")
            .withUserId("user-42")
            .withSessionId(sessionId)
            .withModelId("gemini-2.0-flash")
            .withPromptName("agent-prompt")
            .withPromptVersion(3)
            .withTotalTokens(250)
            .withGroupId("eval-batch-1")
            .withLabels(List.of("production", "v2"))
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertNotNull(found);
    assertEquals("Hello agent", found.inputText());
    assertEquals("Hi there, how can I help?", found.outputText());
    assertEquals("user-42", found.userId());
    assertEquals(sessionId, found.sessionId());
    assertEquals("gemini-2.0-flash", found.modelId());
    assertEquals("agent-prompt", found.promptName());
    assertEquals(3, found.promptVersion());
    assertEquals(250, found.totalTokens());
    assertEquals(0, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
    assertEquals("eval-batch-1", found.groupId());
    assertEquals(List.of("production", "v2"), found.labels());
  }

  @Test
  void newFieldsDefaultToNullOrZeroOnStore() {
    var trace =
        Trace.newBuilder()
            .withName("minimal")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1))
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertNull(found.inputText());
    assertNull(found.outputText());
    assertNull(found.userId());
    assertNull(found.sessionId());
    assertNull(found.modelId());
    assertNull(found.promptName());
    assertNull(found.promptVersion());
    assertEquals(0, found.totalTokens());
    assertEquals(0, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
    assertNull(found.groupId());
    assertTrue(found.labels().isEmpty());
  }

  @Test
  void labelsRoundTrip() {
    var trace =
        Trace.newBuilder()
            .withName("labeled")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withLabels(List.of("alpha", "beta", "gamma"))
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(List.of("alpha", "beta", "gamma"), found.labels());
  }

  @Test
  void emptyLabelsRoundTrip() {
    var trace =
        Trace.newBuilder()
            .withName("no-labels")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertTrue(found.labels().isEmpty());
  }

  // --- list() tests ---

  @Test
  void listWithoutFilterReturnsPaginatedResults() {
    var base = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    for (int i = 0; i < 5; i++) {
      store.store(
          Trace.newBuilder()
              .withName("trace-" + i)
              .withStartTime(base.plusMinutes(i))
              .withEndTime(base.plusMinutes(i).plusSeconds(1))
              .build());
    }

    var result = store.list(new Paginate(1, 3), null);

    assertEquals(3, result.items().size());
    assertTrue(result.hasMore());
    // Ordered by start_time DESC
    assertEquals("trace-4", result.items().get(0).name());
    assertEquals("trace-3", result.items().get(1).name());
    assertEquals("trace-2", result.items().get(2).name());
  }

  @Test
  void listSecondPageReturnsTail() {
    var base = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    for (int i = 0; i < 5; i++) {
      store.store(
          Trace.newBuilder()
              .withName("trace-" + i)
              .withStartTime(base.plusMinutes(i))
              .withEndTime(base.plusMinutes(i).plusSeconds(1))
              .build());
    }

    var result = store.list(new Paginate(2, 3), null);

    assertEquals(2, result.items().size());
    assertFalse(result.hasMore());
    assertEquals("trace-1", result.items().get(0).name());
    assertEquals("trace-0", result.items().get(1).name());
  }

  @Test
  void listWithNullPaginateUsesDefaults() {
    store.store(
        Trace.newBuilder()
            .withName("single")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build());

    var result = store.list(null, null);

    assertEquals(1, result.items().size());
    assertFalse(result.hasMore());
  }

  @Test
  void listEmptyTableReturnsEmptyList() {
    var result = store.list(Paginate.of(), null);

    assertTrue(result.items().isEmpty());
    assertFalse(result.hasMore());
  }

  @Test
  void listWithScimFilterByName() {
    var base = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    store.store(
        Trace.newBuilder()
            .withName("my-agent")
            .withStartTime(base)
            .withEndTime(base.plusSeconds(1))
            .build());
    store.store(
        Trace.newBuilder()
            .withName("other-agent")
            .withStartTime(base.plusMinutes(1))
            .withEndTime(base.plusMinutes(1).plusSeconds(1))
            .build());

    var result = store.list(Paginate.of(), "name eq \"my-agent\"");

    assertEquals(1, result.items().size());
    assertEquals("my-agent", result.items().getFirst().name());
  }

  @Test
  void listWithScimFilterByUserId() {
    var base = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    store.store(
        Trace.newBuilder().withName("trace-u1").withStartTime(base).withUserId("u1").build());
    store.store(
        Trace.newBuilder()
            .withName("trace-u2")
            .withStartTime(base.plusMinutes(1))
            .withUserId("u2")
            .build());

    var result = store.list(Paginate.of(), "user_id eq \"u1\"");

    assertEquals(1, result.items().size());
    assertEquals("u1", result.items().getFirst().userId());
  }

  @Test
  void listWithScimFilterByModelId() {
    var base = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    store.store(
        Trace.newBuilder()
            .withName("gemini-trace")
            .withStartTime(base)
            .withModelId("gemini-2.0-flash")
            .build());
    store.store(
        Trace.newBuilder()
            .withName("other-trace")
            .withStartTime(base.plusMinutes(1))
            .withModelId("gpt-4o")
            .build());

    var result = store.list(Paginate.of(), "model_id eq \"gemini-2.0-flash\"");

    assertEquals(1, result.items().size());
    assertEquals("gemini-2.0-flash", result.items().getFirst().modelId());
  }

  @Test
  void listWithBlankFilterReturnsAll() {
    store.store(
        Trace.newBuilder()
            .withName("trace-1")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build());
    store.store(
        Trace.newBuilder()
            .withName("trace-2")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build());

    var result = store.list(Paginate.of(), "  ");

    assertEquals(2, result.items().size());
  }

  @Test
  void listWithInvalidFilterThrows() {
    assertThrows(PgException.class, () -> store.list(Paginate.of(), "invalid filter gibberish!!!"));
  }

  // --- Annotation authorId tests ---

  @Test
  void storeAnnotationWithAuthorId() {
    var subjectId = UUID.randomUUID();
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withAuthorId("reviewer-1")
            .build();

    store.storeAnnotation(annotation);
    var found = store.findAnnotationsBySubject(subjectId);

    assertEquals(1, found.size());
    assertEquals("reviewer-1", found.getFirst().authorId());
  }

  @Test
  void storeAnnotationWithNullAuthorId() {
    var subjectId = UUID.randomUUID();
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.SYSTEM)
            .withRating(1)
            .build();

    store.storeAnnotation(annotation);
    var found = store.findAnnotationsBySubject(subjectId);

    assertEquals(1, found.size());
    assertNull(found.getFirst().authorId());
  }

  // --- upsertAnnotation tests ---

  @Test
  void upsertAnnotationCreatesNew() {
    var subjectId = UUID.randomUUID();
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withAuthorId("reviewer-1")
            .build();

    store.upsertAnnotation(annotation);
    var found = store.findAnnotationsBySubject(subjectId);

    assertEquals(1, found.size());
    assertEquals(1, found.getFirst().rating());
    assertEquals("reviewer-1", found.getFirst().authorId());
  }

  @Test
  void upsertAnnotationUpdatesSameKeyInPlace() {
    var subjectId = UUID.randomUUID();
    var createdAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var first =
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withFacet("relevance")
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withComment("Good")
            .withAuthorId("reviewer-1")
            .withCreatedAt(createdAt)
            .build();
    store.upsertAnnotation(first);

    var second =
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withFacet("relevance")
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(-1)
            .withComment("Actually not great")
            .withMetadata(Map.of("revised", true))
            .withAuthorId("reviewer-1")
            .withCreatedAt(createdAt.plusDays(1))
            .build();
    store.upsertAnnotation(second);

    var found = store.findAnnotationsBySubject(subjectId);
    assertEquals(1, found.size());
    assertEquals(-1, found.getFirst().rating());
    assertEquals("Actually not great", found.getFirst().comment());
    assertEquals(true, found.getFirst().metadata().get("revised"));
    assertEquals(createdAt, found.getFirst().createdAt());
    assertEquals(createdAt.plusDays(1), found.getFirst().updatedAt());
  }

  @Test
  void upsertAnnotationSameAuthorFacetDifferentLabelsCreatesSeparate() {
    var subjectId = UUID.randomUUID();
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withFacet("relevance")
            .withLabel("score")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withAuthorId("reviewer-1")
            .build());
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withFacet("relevance")
            .withLabel("confidence")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withAuthorId("reviewer-1")
            .build());

    assertEquals(2, store.findAnnotationsBySubject(subjectId).size());
  }

  @Test
  void upsertAnnotationSameAuthorLabelDifferentFacetsCreatesSeparate() {
    var subjectId = UUID.randomUUID();
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withFacet("relevance")
            .withLabel("score")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withAuthorId("reviewer-1")
            .build());
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withFacet("mutuality")
            .withLabel("score")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(-1)
            .withAuthorId("reviewer-1")
            .build());

    assertEquals(2, store.findAnnotationsBySubject(subjectId).size());
  }

  @Test
  void upsertAnnotationNullFacetCoalescesToSingleRow() {
    var subjectId = UUID.randomUUID();
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withAuthorId("reviewer-1")
            .build());
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(-1)
            .withAuthorId("reviewer-1")
            .build());

    var found = store.findAnnotationsBySubject(subjectId);
    assertEquals(1, found.size());
    assertEquals(-1, found.getFirst().rating());
  }

  @Test
  void upsertAnnotationNullAuthorIdAlwaysInserts() {
    var subjectId = UUID.randomUUID();
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.SYSTEM)
            .withRating(1)
            .build());
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.SYSTEM)
            .withRating(-1)
            .build());

    var found = store.findAnnotationsBySubject(subjectId);
    assertEquals(2, found.size());
  }

  @Test
  void upsertAnnotationDifferentAuthorsCreatesSeparate() {
    var subjectId = UUID.randomUUID();
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withAuthorId("reviewer-1")
            .build());
    store.upsertAnnotation(
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(-1)
            .withAuthorId("reviewer-2")
            .build());

    var found = store.findAnnotationsBySubject(subjectId);
    assertEquals(2, found.size());
  }

  // --- Feedback trigger tests ---

  @Test
  void feedbackTriggerIncrementsThumbsUp() {
    var trace =
        Trace.newBuilder()
            .withName("feedback-test")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build();
    store.store(trace);

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(trace.id())
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("quality")
            .withRating(1)
            .build());

    var found = store.findById(trace.id());
    assertEquals(1, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerIncrementsThumbsDown() {
    var trace =
        Trace.newBuilder()
            .withName("feedback-test")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build();
    store.store(trace);

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(trace.id())
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("quality")
            .withRating(-1)
            .build());

    var found = store.findById(trace.id());
    assertEquals(0, found.thumbsUpCount());
    assertEquals(1, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerIgnoresZeroRating() {
    var trace =
        Trace.newBuilder()
            .withName("feedback-test")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build();
    store.store(trace);

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(trace.id())
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("quality")
            .withRating(0)
            .build());

    var found = store.findById(trace.id());
    assertEquals(0, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerIgnoresNullRating() {
    var trace =
        Trace.newBuilder()
            .withName("feedback-test")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build();
    store.store(trace);

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(trace.id())
            .withLabel("flag")
            .withAuthorKind(AuthorKind.HUMAN)
            .build());

    var found = store.findById(trace.id());
    assertEquals(0, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerSpanAnnotationIsNoOp() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var trace =
        Trace.newBuilder()
            .withName("span-feedback-test")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withSpan(span)
            .build();
    store.store(trace);

    // Annotate the span, not the trace
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(span.id())
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .build());

    // Trace counters should remain zero
    var found = store.findById(trace.id());
    assertEquals(0, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerMultipleAnnotationsAccumulate() {
    var trace =
        Trace.newBuilder()
            .withName("multi-feedback")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .build();
    store.store(trace);

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(trace.id())
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("quality")
            .withRating(1)
            .build());
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(trace.id())
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("relevance")
            .withRating(1)
            .build());
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(trace.id())
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("accuracy")
            .withRating(-1)
            .build());

    var found = store.findById(trace.id());
    assertEquals(2, found.thumbsUpCount());
    assertEquals(1, found.thumbsDownCount());
  }

  // ── opt-in trace-side redaction (PgConfig.withRedactor) ───────────────────

  @Test
  void traceTextFieldsArePersistedVerbatimByDefault() {
    // Regression test for the documented default: without a redactor, traces store exactly what
    // the loop and tools produced. Evals and debugging depend on this.
    var raw = "Authorization: Bearer ghp_supersecret_12345678";
    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1))
            .withInputText(raw)
            .withOutputText(raw)
            .withAttribute("captured", raw)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(raw, found.inputText(), "default: input verbatim");
    assertEquals(raw, found.outputText(), "default: output verbatim");
    assertEquals(raw, found.attributes().get("captured"), "default: attribute verbatim");
  }

  @Test
  void traceTextFieldsAreScrubbedWhenRedactorConfigured() {
    var registry = new ai.singlr.core.common.SecretRegistry();
    registry.register("GH_TOKEN", "ghp_supersecret_12345678");
    var redactingStore =
        new PgTraceStore(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withRedactor(registry.redactor())
                .build());

    var raw = "Authorization: Bearer ghp_supersecret_12345678";
    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1))
            .withInputText(raw)
            .withOutputText(raw)
            .withAttribute("captured", raw)
            .build();

    redactingStore.store(trace);
    var found = redactingStore.findById(trace.id());

    var expected = "Authorization: Bearer <redacted:GH_TOKEN>";
    assertEquals(expected, found.inputText());
    assertEquals(expected, found.outputText());
    assertEquals(expected, found.attributes().get("captured"));
  }

  @Test
  void spanFieldsAreScrubbedWhenRedactorConfigured() {
    var registry = new ai.singlr.core.common.SecretRegistry();
    registry.register("API_KEY", "sk-supersecret-abc12345");
    var redactingStore =
        new PgTraceStore(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withRedactor(registry.redactor())
                .build());

    var raw = "leaked: sk-supersecret-abc12345 in span attr";
    var span =
        Span.newBuilder()
            .withName("tool-call")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1))
            .withError(raw)
            .withAttribute("payload", raw)
            .build();
    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2))
            .withSpan(span)
            .build();

    redactingStore.store(trace);
    var found = redactingStore.findById(trace.id());

    var foundSpan = found.spans().getFirst();
    var expected = "leaked: <redacted:API_KEY> in span attr";
    assertEquals(expected, foundSpan.error());
    assertEquals(expected, foundSpan.attributes().get("payload"));
  }
}
