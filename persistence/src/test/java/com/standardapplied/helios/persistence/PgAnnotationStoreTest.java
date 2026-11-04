/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.Paginate;
import io.opentelemetry.api.common.Attributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgAnnotationStoreTest {

  /** A fresh run id to annotate. */
  private static String subject() {
    return "run-" + UUID.randomUUID();
  }

  private static final String STEP_SPAN_ID = "1000000000000001";

  private PgTraceStore traces;
  private PgAnnotationStore store;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateTraces();
    traces = new PgTraceStore(PgTestSupport.pgConfig());
    store = new PgAnnotationStore(PgTestSupport.pgConfig());
  }

  /** Store a run with one step through the exporter; its run id. */
  private static String storedRun() {
    var runId = subject();
    var exporter = new PgSpanExporter(PgTestSupport.pgConfig());
    var spans =
        List.of(
            ExportedSpans.step(runId, STEP_SPAN_ID, "chat scripted", "chat", 1, Attributes.empty()),
            ExportedSpans.run(runId, "00f067aa0ba902b7", "feedback-test", 0, Attributes.empty()));
    assertTrue(exporter.export(spans).isSuccess());
    return runId;
  }

  @Test
  void storeAndFindAnnotationWithAllFields() {
    var subjectId = subject();
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
    var subjectId = subject();
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
    var subjectId = subject();
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
    assertTrue(store.findAnnotationsBySubject(subject()).isEmpty());
  }

  @Test
  void metadataRoundTripsThroughJsonb() {
    var subjectId = subject();
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
    var subjectA = subject();
    var subjectB = subject();
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

    var found = store.findAnnotationsBySubjects(List.of(subjectA, subjectB, subject()));
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
        new PgAnnotationStore(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withSchema("no_such_schema")
                .build());
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(subject())
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .build();

    assertThrows(PgException.class, () -> broken.storeAnnotation(annotation));
    assertThrows(PgException.class, () -> broken.upsertAnnotation(annotation));
    assertThrows(PgException.class, () -> broken.findAnnotationsBySubject(subject()));
    assertThrows(PgException.class, () -> broken.findAnnotationsBySubjects(List.of(subject())));
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
    var subjectId = subject();
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
    var subjectId = subject();
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

  // --- Annotation authorId tests ---
  @Test
  void storeAnnotationWithAuthorId() {
    var subjectId = subject();
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
    var subjectId = subject();
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
    var subjectId = subject();
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
    var subjectId = subject();
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
    var subjectId = subject();
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
    var subjectId = subject();
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
    var subjectId = subject();
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
    var subjectId = subject();
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
    var subjectId = subject();
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
    var runId = storedRun();

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(runId)
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("quality")
            .withRating(1)
            .build());

    var found = traces.findById(runId);
    assertEquals(1, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerIncrementsThumbsDown() {
    var runId = storedRun();

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(runId)
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("quality")
            .withRating(-1)
            .build());

    var found = traces.findById(runId);
    assertEquals(0, found.thumbsUpCount());
    assertEquals(1, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerIgnoresZeroRating() {
    var runId = storedRun();

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(runId)
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("quality")
            .withRating(0)
            .build());

    var found = traces.findById(runId);
    assertEquals(0, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerIgnoresNullRating() {
    var runId = storedRun();

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(runId)
            .withLabel("flag")
            .withAuthorKind(AuthorKind.HUMAN)
            .build());

    var found = traces.findById(runId);
    assertEquals(0, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerSpanAnnotationIsNoOp() {
    var runId = storedRun();

    // Annotate the span, not the trace
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(STEP_SPAN_ID)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .build());

    // Trace counters should remain zero
    var found = traces.findById(runId);
    assertEquals(0, found.thumbsUpCount());
    assertEquals(0, found.thumbsDownCount());
  }

  @Test
  void feedbackTriggerMultipleAnnotationsAccumulate() {
    var runId = storedRun();

    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(runId)
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("quality")
            .withRating(1)
            .build());
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(runId)
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("relevance")
            .withRating(1)
            .build());
    store.storeAnnotation(
        Annotation.newBuilder()
            .withSubjectId(runId)
            .withAuthorKind(AuthorKind.HUMAN)
            .withLabel("accuracy")
            .withRating(-1)
            .build());

    var found = traces.findById(runId);
    assertEquals(2, found.thumbsUpCount());
    assertEquals(1, found.thumbsDownCount());
  }
}
