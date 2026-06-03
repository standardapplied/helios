/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnnotationTest {

  @Test
  void builderDefaults() {
    var subjectId = UUID.randomUUID();

    var annotation =
        Annotation.newBuilder()
            .withSubjectId(subjectId)
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withRating(1)
            .withComment("Great response")
            .build();

    assertNotNull(annotation.id());
    assertEquals(subjectId, annotation.subjectId());
    assertEquals("quality", annotation.label());
    assertEquals(AuthorKind.HUMAN, annotation.authorKind());
    assertEquals(1, annotation.rating());
    assertEquals("Great response", annotation.comment());
    assertNotNull(annotation.createdAt());
    assertNotNull(annotation.updatedAt());
    assertTrue(annotation.metadata().isEmpty());
  }

  @Test
  void builderRoundTrip() {
    var original =
        Annotation.newBuilder()
            .withSubjectId(UUID.randomUUID())
            .withFacet("mutuality")
            .withLabel("relevance")
            .withAuthorKind(AuthorKind.MODEL)
            .withAuthorId("judge-model-1")
            .withRating(-1)
            .withComment("Off topic")
            .withMetadata(Map.of("groupId", "g-7"))
            .build();

    var copy = Annotation.newBuilder(original).build();

    assertEquals(original.id(), copy.id());
    assertEquals(original.subjectId(), copy.subjectId());
    assertEquals("mutuality", copy.facet());
    assertEquals(original.label(), copy.label());
    assertEquals(AuthorKind.MODEL, copy.authorKind());
    assertEquals("judge-model-1", copy.authorId());
    assertEquals(original.rating(), copy.rating());
    assertEquals(original.comment(), copy.comment());
    assertEquals(Map.of("groupId", "g-7"), copy.metadata());
    assertEquals(original.createdAt(), copy.createdAt());
    assertEquals(original.updatedAt(), copy.updatedAt());
  }

  @Test
  void builderWithExplicitTimestamps() {
    var id = UUID.randomUUID();
    var subjectId = UUID.randomUUID();
    var createdAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var updatedAt = createdAt.plusHours(3);

    var annotation =
        Annotation.newBuilder()
            .withId(id)
            .withSubjectId(subjectId)
            .withLabel("accuracy")
            .withAuthorKind(AuthorKind.SYSTEM)
            .withRating(0)
            .withComment("Neutral")
            .withCreatedAt(createdAt)
            .withUpdatedAt(updatedAt)
            .build();

    assertEquals(id, annotation.id());
    assertEquals(subjectId, annotation.subjectId());
    assertEquals(createdAt, annotation.createdAt());
    assertEquals(updatedAt, annotation.updatedAt());
  }

  @Test
  void updatedAtDefaultsToCreatedAt() {
    var createdAt = OffsetDateTime.of(2026, 2, 2, 0, 0, 0, 0, ZoneOffset.UTC);

    var annotation =
        Annotation.newBuilder()
            .withSubjectId(UUID.randomUUID())
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withCreatedAt(createdAt)
            .build();

    assertEquals(createdAt, annotation.updatedAt());
  }

  @Test
  void optionalFieldsCanBeNull() {
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(UUID.randomUUID())
            .withLabel("flag")
            .withAuthorKind(AuthorKind.SYSTEM)
            .build();

    assertNull(annotation.facet());
    assertNull(annotation.rating());
    assertNull(annotation.comment());
    assertNull(annotation.authorId());
  }

  @Test
  void metadataDefaultsToEmptyImmutableMap() {
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(UUID.randomUUID())
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .build();

    assertTrue(annotation.metadata().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> annotation.metadata().put("k", "v"));
  }

  @Test
  void metadataIsDefensivelyCopied() {
    var source = new HashMap<String, Object>();
    source.put("a", 1);

    var annotation =
        Annotation.newBuilder()
            .withSubjectId(UUID.randomUUID())
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withMetadata(source)
            .build();

    source.put("b", 2);

    assertEquals(1, annotation.metadata().size());
    assertThrows(UnsupportedOperationException.class, () -> annotation.metadata().put("c", 3));
  }

  @Test
  void metadataToleratesNullValues() {
    var source = new HashMap<String, Object>();
    source.put("k", null);

    var annotation =
        Annotation.newBuilder()
            .withSubjectId(UUID.randomUUID())
            .withLabel("quality")
            .withAuthorKind(AuthorKind.HUMAN)
            .withMetadata(source)
            .build();

    assertTrue(annotation.metadata().containsKey("k"));
    assertNull(annotation.metadata().get("k"));
  }

  @Test
  void builderMissingSubjectIdThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Annotation.newBuilder().withLabel("quality").withAuthorKind(AuthorKind.HUMAN).build());
  }

  @Test
  void builderMissingLabelThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Annotation.newBuilder()
                .withSubjectId(UUID.randomUUID())
                .withAuthorKind(AuthorKind.HUMAN)
                .build());
  }

  @Test
  void builderBlankLabelThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Annotation.newBuilder()
                .withSubjectId(UUID.randomUUID())
                .withLabel("   ")
                .withAuthorKind(AuthorKind.HUMAN)
                .build());
  }

  @Test
  void builderMissingAuthorKindThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Annotation.newBuilder().withSubjectId(UUID.randomUUID()).withLabel("quality").build());
  }

  @Test
  void builderWithFacetAndAuthor() {
    var annotation =
        Annotation.newBuilder()
            .withSubjectId(UUID.randomUUID())
            .withFacet("relevance")
            .withLabel("rubric")
            .withAuthorKind(AuthorKind.HUMAN)
            .withAuthorId("ceo@example.com")
            .build();

    assertEquals("relevance", annotation.facet());
    assertEquals("ceo@example.com", annotation.authorId());
  }

  @Test
  void authorKindValuesAreStable() {
    assertEquals(AuthorKind.HUMAN, AuthorKind.valueOf("HUMAN"));
    assertEquals(3, AuthorKind.values().length);
  }
}
