/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.trace.Annotation;
import ai.singlr.core.trace.AuthorKind;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Maps Helidon {@link DbRow} results to {@link Annotation} records. */
public final class AnnotationMapper {

  private AnnotationMapper() {}

  /** Maps a single database row to an Annotation. */
  public static Annotation map(DbRow row) {
    var ratingObj = row.column("rating").get(Object.class);
    Integer rating = ratingObj != null ? ((Number) ratingObj).intValue() : null;

    return Annotation.newBuilder()
        .withId(row.column("id").get(UUID.class))
        .withSubjectId(row.column("subject_id").get(UUID.class))
        .withFacet(row.column("facet").getString())
        .withLabel(row.column("label").getString())
        .withAuthorKind(AuthorKind.valueOf(row.column("author_kind").getString()))
        .withAuthorId(row.column("author_id").getString())
        .withRating(rating)
        .withComment(row.column("comment").getString())
        .withMetadata(JsonbMapper.fromJsonbObject(row.column("metadata").getString()))
        .withCreatedAt(row.column("created_at").get(OffsetDateTime.class))
        .withUpdatedAt(row.column("updated_at").get(OffsetDateTime.class))
        .build();
  }

  /** Maps a stream of database rows to an immutable list of Annotations. */
  public static List<Annotation> mapAll(Stream<DbRow> rows) {
    return rows.map(AnnotationMapper::map).toList();
  }
}
