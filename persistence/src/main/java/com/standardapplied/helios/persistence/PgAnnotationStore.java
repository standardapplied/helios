/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import ai.singlr.scimsql.ScimEngine;
import com.standardapplied.helios.core.common.Paginate;
import com.standardapplied.helios.core.common.PaginatedList;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.trace.Annotation;
import com.standardapplied.helios.persistence.mapper.AnnotationMapper;
import com.standardapplied.helios.persistence.mapper.JsonbMapper;
import com.standardapplied.helios.persistence.sql.AnnotationSql;
import io.helidon.dbclient.DbClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PostgreSQL-backed store for {@link Annotation}s, the structured judgments attached to a stored
 * trace or span. Writes insert or upsert the full record including {@code metadata}; reads find by
 * subject, by a batch of subjects, or page through all annotations under an optional SCIM filter.
 */
public class PgAnnotationStore {

  private final PgConfig config;
  private final DbClient dbClient;

  public PgAnnotationStore(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
  }

  /** Stores an annotation, persisting the full record including {@code metadata}. */
  public Annotation storeAnnotation(Annotation annotation) {
    try {
      dbClient.execute().dml(config.qualify(AnnotationSql.INSERT), annotationParams(annotation));
      return annotation;
    } catch (Exception e) {
      throw new PgException("Failed to store annotation: " + annotation.id(), e);
    }
  }

  /**
   * Stores or updates an annotation, persisting the full record including {@code metadata}. When
   * the annotation has an {@code authorId} and one already exists for the same {@code (subjectId,
   * facet, label, authorId)} key, the existing row is updated in place ({@code createdAt} is
   * preserved, {@code updatedAt} is advanced). Author-less annotations always insert.
   */
  public Annotation upsertAnnotation(Annotation annotation) {
    try {
      dbClient.execute().dml(config.qualify(AnnotationSql.UPSERT), annotationParams(annotation));
      return annotation;
    } catch (Exception e) {
      throw new PgException("Failed to upsert annotation: " + annotation.id(), e);
    }
  }

  /** Finds all annotations for a given subject (trace or span), oldest first. */
  public List<Annotation> findAnnotationsBySubject(UUID subjectId) {
    try {
      return AnnotationMapper.mapAll(
          dbClient
              .execute()
              .query(config.qualify(AnnotationSql.FIND_BY_SUBJECT), subjectId.toString()));
    } catch (Exception e) {
      throw new PgException("Failed to find annotations for subject: " + subjectId, e);
    }
  }

  /**
   * Finds all annotations for a batch of subjects in a single query. Returns an empty list for a
   * null or empty input.
   */
  public List<Annotation> findAnnotationsBySubjects(List<UUID> subjectIds) {
    if (subjectIds == null || subjectIds.isEmpty()) {
      return List.of();
    }
    try {
      var placeholders =
          subjectIds.stream().map(id -> "CAST(? AS UUID)").collect(Collectors.joining(", "));
      var sql =
          config.qualify(AnnotationSql.FIND_BY_SUBJECTS_PREFIX)
              + placeholders
              + AnnotationSql.FIND_BY_SUBJECTS_SUFFIX;
      var params = subjectIds.stream().map(UUID::toString).toArray();
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

  private static Object[] annotationParams(Annotation annotation) {
    return new Object[] {
      annotation.id().toString(),
      annotation.subjectId().toString(),
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
