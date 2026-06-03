/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A structured note attached to a trace or span.
 *
 * <p>An annotation always targets a real Helios {@code subjectId} (a trace or span id). The
 * optional {@code facet} addresses a named sub-coordinate within that subject, so a single author
 * can hold several judgments about one subject (for example one rating per evaluation dimension)
 * without inventing synthetic target ids. The {@code label} categorizes the judgment; together
 * {@code (subjectId, facet, label, authorId)} form the idempotency key honored by the persistence
 * store's {@code upsertAnnotation}.
 *
 * <p>Both {@code facet} and {@code metadata} are opaque to Helios: it stores and returns them
 * without interpretation. {@code metadata} is always a non-null immutable map (empty when unset).
 *
 * @param id unique identifier
 * @param subjectId the trace or span id this annotation is attached to
 * @param facet optional named sub-coordinate within the subject (opaque, nullable)
 * @param label category label for the judgment (e.g. "quality", "relevance", "accuracy")
 * @param authorKind the generic class of author that produced this annotation
 * @param authorId the precise author identity (nullable, no FK constraint)
 * @param rating optional numeric rating (e.g. -1, 0, or 1)
 * @param comment optional free text
 * @param metadata opaque consumer-owned key/value context (never null; empty when unset)
 * @param createdAt when this annotation was first created
 * @param updatedAt when this annotation was last written (equals {@code createdAt} on first write)
 */
public record Annotation(
    UUID id,
    UUID subjectId,
    String facet,
    String label,
    AuthorKind authorKind,
    String authorId,
    Integer rating,
    String comment,
    Map<String, Object> metadata,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  /**
   * Canonical constructor. Normalizes {@code metadata} to a non-null immutable map, tolerating null
   * values (a generic JSON bag may legitimately carry them).
   */
  public Annotation {
    metadata =
        metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Annotation annotation) {
    return new Builder(annotation);
  }

  /** Builder for Annotation. */
  public static class Builder {

    private UUID id;
    private UUID subjectId;
    private String facet;
    private String label;
    private AuthorKind authorKind;
    private String authorId;
    private Integer rating;
    private String comment;
    private Map<String, Object> metadata;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    private Builder() {}

    private Builder(Annotation annotation) {
      this.id = annotation.id;
      this.subjectId = annotation.subjectId;
      this.facet = annotation.facet;
      this.label = annotation.label;
      this.authorKind = annotation.authorKind;
      this.authorId = annotation.authorId;
      this.rating = annotation.rating;
      this.comment = annotation.comment;
      this.metadata = annotation.metadata;
      this.createdAt = annotation.createdAt;
      this.updatedAt = annotation.updatedAt;
    }

    public Builder withId(UUID id) {
      this.id = id;
      return this;
    }

    public Builder withSubjectId(UUID subjectId) {
      this.subjectId = subjectId;
      return this;
    }

    public Builder withFacet(String facet) {
      this.facet = facet;
      return this;
    }

    public Builder withLabel(String label) {
      this.label = label;
      return this;
    }

    public Builder withAuthorKind(AuthorKind authorKind) {
      this.authorKind = authorKind;
      return this;
    }

    public Builder withAuthorId(String authorId) {
      this.authorId = authorId;
      return this;
    }

    public Builder withRating(Integer rating) {
      this.rating = rating;
      return this;
    }

    public Builder withComment(String comment) {
      this.comment = comment;
      return this;
    }

    public Builder withMetadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder withCreatedAt(OffsetDateTime createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Builder withUpdatedAt(OffsetDateTime updatedAt) {
      this.updatedAt = updatedAt;
      return this;
    }

    /**
     * Builds the Annotation. Auto-generates {@code id} and {@code createdAt} when unset, and
     * defaults {@code updatedAt} to {@code createdAt} for a first write.
     *
     * @throws IllegalStateException if {@code subjectId}, {@code label}, or {@code authorKind} is
     *     not set
     */
    public Annotation build() {
      if (subjectId == null) {
        throw new IllegalStateException("subjectId is required");
      }
      if (Strings.isBlank(label)) {
        throw new IllegalStateException("label is required");
      }
      if (authorKind == null) {
        throw new IllegalStateException("authorKind is required");
      }
      if (id == null) {
        id = Ids.newId();
      }
      if (createdAt == null) {
        createdAt = Ids.now();
      }
      if (updatedAt == null) {
        updatedAt = createdAt;
      }
      return new Annotation(
          id,
          subjectId,
          facet,
          label,
          authorKind,
          authorId,
          rating,
          comment,
          metadata,
          createdAt,
          updatedAt);
    }
  }
}
