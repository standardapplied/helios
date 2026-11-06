/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.Strings;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A structured note attached to a stored run or span.
 *
 * <p>An annotation always targets a real Helios {@code subjectId}: a run id or a span id. The
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
 * @param subjectId the run id or span id this annotation is attached to, at most 64 characters
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
    String subjectId,
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

  /**
   * Returns an empty builder for a new annotation.
   *
   * @return a builder with every field unset
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Returns a builder holding every field of {@code annotation}, to derive a changed copy, such as
   * a new rating under the same id.
   *
   * @param annotation the annotation to copy; non-null
   * @return a builder preset with {@code annotation}'s fields
   * @throws NullPointerException if {@code annotation} is null
   */
  public static Builder newBuilder(Annotation annotation) {
    return new Builder(annotation);
  }

  /**
   * Builds an {@link Annotation}, generating its id and timestamps when they are not given. Not
   * thread-safe; use one builder per thread.
   */
  public static class Builder {

    private UUID id;
    private String subjectId;
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

    /**
     * Sets the annotation's id. Unset or null, {@link #build()} generates a new time-ordered id.
     *
     * @param id the id, or null to generate one
     * @return this builder
     */
    public Builder withId(UUID id) {
      this.id = id;
      return this;
    }

    /**
     * Sets the run id or span id the annotation is attached to; required. The store's column holds
     * at most 64 characters, and a longer id fails the write.
     *
     * @param subjectId the run or span id; {@link #build()} rejects null or blank
     * @return this builder
     */
    public Builder withSubjectId(String subjectId) {
      this.subjectId = subjectId;
      return this;
    }

    /**
     * Sets the named sub-coordinate within the subject, such as an evaluation dimension, so one
     * author can hold several judgments with the same label on one subject. Helios does not
     * interpret it. Unset means none.
     *
     * @param facet the facet, or null for none
     * @return this builder
     */
    public Builder withFacet(String facet) {
      this.facet = facet;
      return this;
    }

    /**
     * Sets the category of the judgment, such as {@code "quality"} or {@code "relevance"};
     * required.
     *
     * @param label the label; {@link #build()} rejects null or blank
     * @return this builder
     */
    public Builder withLabel(String label) {
      this.label = label;
      return this;
    }

    /**
     * Sets the class of author that produced the annotation; required.
     *
     * @param authorKind the author kind; {@link #build()} rejects null
     * @return this builder
     */
    public Builder withAuthorKind(AuthorKind authorKind) {
      this.authorKind = authorKind;
      return this;
    }

    /**
     * Sets the precise author identity. With an author id, {@link
     * PgAnnotationStore#upsertAnnotation} updates the author's earlier annotation on the same
     * subject, facet and label; without one, every write inserts a new row. Unset means none.
     *
     * @param authorId the author id, or null for none
     * @return this builder
     */
    public Builder withAuthorId(String authorId) {
      this.authorId = authorId;
      return this;
    }

    /**
     * Sets the numeric rating. On a run, a positive rating counts as a thumbs-up and a negative one
     * as a thumbs-down in {@link Trace} and {@link TraceRollup}; zero and null count as neither.
     *
     * @param rating the rating, or null for none
     * @return this builder
     */
    public Builder withRating(Integer rating) {
      this.rating = rating;
      return this;
    }

    /**
     * Sets the free-text comment. Unset means none.
     *
     * @param comment the comment, or null for none
     * @return this builder
     */
    public Builder withComment(String comment) {
      this.comment = comment;
      return this;
    }

    /**
     * Sets the consumer-owned key/value context, stored as JSON and returned uninterpreted. The map
     * is copied when the annotation is built; its values may be null.
     *
     * @param metadata the metadata, or null for an empty map
     * @return this builder
     */
    public Builder withMetadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    /**
     * Sets when the annotation was first created. Unset or null, {@link #build()} uses the current
     * UTC time. An upsert that updates an existing row keeps that row's creation time.
     *
     * @param createdAt the creation time, or null for now
     * @return this builder
     */
    public Builder withCreatedAt(OffsetDateTime createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    /**
     * Sets when the annotation was last written. Unset or null, {@link #build()} uses the creation
     * time.
     *
     * @param updatedAt the last write time, or null for the creation time
     * @return this builder
     */
    public Builder withUpdatedAt(OffsetDateTime updatedAt) {
      this.updatedAt = updatedAt;
      return this;
    }

    /**
     * Builds the Annotation. Auto-generates {@code id} and {@code createdAt} when unset, and
     * defaults {@code updatedAt} to {@code createdAt} for a first write.
     *
     * @return the annotation
     * @throws IllegalStateException if {@code subjectId} or {@code label} is null or blank, or
     *     {@code authorKind} is null
     */
    public Annotation build() {
      if (Strings.isBlank(subjectId)) {
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
