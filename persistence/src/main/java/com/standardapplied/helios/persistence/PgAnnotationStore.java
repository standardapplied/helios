/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import ai.singlr.scimsql.ScimEngine;
import com.standardapplied.helios.core.common.Paginate;
import com.standardapplied.helios.core.common.PaginatedList;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.persistence.mapper.AnnotationMapper;
import com.standardapplied.helios.persistence.mapper.JsonbMapper;
import com.standardapplied.helios.persistence.sql.AnnotationSql;
import io.helidon.dbclient.DbClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * PostgreSQL-backed store for {@link Annotation}s, the structured judgments attached to a stored
 * trace or span. Writes insert or upsert the full record including {@code metadata}; reads find by
 * subject, by a batch of subjects, or page through all annotations under an optional SCIM filter.
 *
 * <p>Every method throws {@link PgException} when the database fails. Thread-safe: the store holds
 * no mutable state, and each call runs its own statements through the shared {@code DbClient}.
 */
public class PgAnnotationStore {

  private final PgConfig config;
  private final DbClient dbClient;

  /**
   * Creates a store over the {@code helios_annotations} table of {@code config}'s schema.
   *
   * @param config the database and schema; non-null
   * @throws NullPointerException if {@code config} is null
   */
  public PgAnnotationStore(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
  }

  /**
   * Stores an annotation, persisting the full record including {@code metadata}.
   *
   * @param annotation the annotation to insert; non-null
   * @return the stored row
   * @throws PgException if the insert fails, such as for an id already stored or, for an annotation
   *     with an {@code authorId}, one already stored under its {@code (subjectId, facet, label,
   *     authorId)} key
   * @throws NullPointerException if {@code annotation} is null
   */
  public Annotation storeAnnotation(Annotation annotation) {
    try {
      return written(AnnotationSql.INSERT, annotation);
    } catch (Exception e) {
      throw new PgException("Failed to store annotation: " + annotation.id(), e);
    }
  }

  /**
   * Stores or updates an annotation, persisting the full record including {@code metadata}. When
   * the annotation has an {@code authorId} and one already exists for the same {@code (subjectId,
   * facet, label, authorId)} key, the existing row is updated in place ({@code createdAt} is
   * preserved, {@code updatedAt} is advanced). Author-less annotations always insert.
   *
   * @param annotation the annotation to insert or merge; non-null
   * @return the stored row: on an update, the existing {@code id} and {@code createdAt} with the
   *     new values and {@code updatedAt}
   * @throws PgException if the write fails
   * @throws NullPointerException if {@code annotation} is null
   */
  public Annotation upsertAnnotation(Annotation annotation) {
    try {
      return written(AnnotationSql.UPSERT, annotation);
    } catch (Exception e) {
      throw new PgException("Failed to upsert annotation: " + annotation.id(), e);
    }
  }

  /**
   * Finds all annotations for a given subject (run or span), oldest first.
   *
   * @param subjectId the run id or span id
   * @return the subject's annotations, empty when it has none
   * @throws PgException if the query fails
   */
  public List<Annotation> findAnnotationsBySubject(String subjectId) {
    try {
      return AnnotationMapper.mapAll(
          dbClient.execute().query(config.qualify(AnnotationSql.FIND_BY_SUBJECT), subjectId));
    } catch (Exception e) {
      throw new PgException("Failed to find annotations for subject: " + subjectId, e);
    }
  }

  /**
   * Finds all annotations for a batch of subjects in a single query. Returns an empty list for a
   * null or empty input, without querying.
   *
   * @param subjectIds the run ids or span ids, or null for none
   * @return the annotations of every listed subject, empty when they have none
   * @throws PgException if the query fails
   */
  public List<Annotation> findAnnotationsBySubjects(List<String> subjectIds) {
    if (subjectIds == null || subjectIds.isEmpty()) {
      return List.of();
    }
    try {
      var placeholders = subjectIds.stream().map(id -> "?").collect(Collectors.joining(", "));
      var sql =
          config.qualify(AnnotationSql.FIND_BY_SUBJECTS_PREFIX)
              + placeholders
              + AnnotationSql.FIND_BY_SUBJECTS_SUFFIX;
      var params = subjectIds.toArray();
      return AnnotationMapper.mapAll(dbClient.execute().query(sql, params));
    } catch (Exception e) {
      throw new PgException("Failed to find annotations for subjects", e);
    }
  }

  /**
   * Lists annotations with optional SCIM filter and pagination. The filter operates over the
   * first-class columns ({@code subject_id}, {@code facet}, {@code label}, {@code author_kind},
   * {@code rating}); filtering over {@code metadata} keys is not yet supported.
   *
   * @param paginate pagination parameters (defaults to page 1, size 50 if null)
   * @param scimFilter optional SCIM filter string (e.g., {@code label eq "relevance"})
   * @return paginated list of annotations
   */
  public PaginatedList<Annotation> listAnnotations(Paginate paginate, String scimFilter) {
    if (paginate == null) {
      paginate = Paginate.of();
    }
    try {
      if (Strings.isBlank(scimFilter)) {
        var sql =
            config.qualify(AnnotationSql.LIST_PREFIX) + config.qualify(AnnotationSql.LIST_SUFFIX);
        var items =
            dbClient
                .execute()
                .createQuery(sql)
                .params(Map.of("limit", paginate.limit(), "offset", paginate.offset()))
                .execute()
                .map(AnnotationMapper::map)
                .toList();
        return PaginatedList.<Annotation>newBuilder()
            .withItems(items)
            .withPaginate(paginate)
            .build();
      }

      var engine = new ScimEngine();
      var filter = engine.parseFilter(scimFilter.trim(), "", null);
      var sql =
          config.qualify(AnnotationSql.LIST_PREFIX)
              + " WHERE "
              + filter.toClause()
              + " "
              + config.qualify(AnnotationSql.LIST_SUFFIX);
      var params = new HashMap<String, Object>(filter.context().indexedParams());
      params.put("limit", paginate.limit());
      params.put("offset", paginate.offset());
      var items =
          dbClient
              .execute()
              .createQuery(sql)
              .params(params)
              .execute()
              .map(AnnotationMapper::map)
              .toList();
      return PaginatedList.<Annotation>newBuilder().withItems(items).withPaginate(paginate).build();
    } catch (Exception e) {
      throw new PgException("Failed to list annotations", e);
    }
  }

  private Annotation written(String sql, Annotation annotation) {
    return AnnotationMapper.map(
        dbClient.execute().get(config.qualify(sql), annotationParams(annotation)).orElseThrow());
  }

  private static Object[] annotationParams(Annotation annotation) {
    return new Object[] {
      annotation.id().toString(),
      annotation.subjectId(),
      annotation.facet(),
      annotation.label(),
      annotation.authorKind().name(),
      annotation.authorId(),
      annotation.rating(),
      annotation.comment(),
      JsonbMapper.objectToJsonb(annotation.metadata()),
      annotation.createdAt(),
      annotation.updatedAt()
    };
  }
}
