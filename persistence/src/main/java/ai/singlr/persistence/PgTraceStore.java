/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import ai.singlr.core.common.Paginate;
import ai.singlr.core.common.PaginatedList;
import ai.singlr.core.common.Strings;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.trace.Annotation;
import ai.singlr.core.trace.Span;
import ai.singlr.core.trace.Trace;
import ai.singlr.persistence.mapper.AnnotationMapper;
import ai.singlr.persistence.mapper.JsonbMapper;
import ai.singlr.persistence.mapper.SpanMapper;
import ai.singlr.persistence.mapper.TraceMapper;
import ai.singlr.persistence.sql.AnnotationSql;
import ai.singlr.persistence.sql.SpanSql;
import ai.singlr.persistence.sql.TraceSql;
import ai.singlr.scimsql.ScimEngine;
import io.helidon.dbclient.DbClient;
import io.helidon.dbclient.DbRow;
import io.helidon.dbclient.DbTransaction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL-backed store for traces, spans, and annotations.
 *
 * <p>Implements {@link EventSink} so callers can wire it directly into any producer of {@link
 * HeliosEvent.RunCompleted} / {@link HeliosEvent.RunFailed} events; the store persists the carried
 * {@link Trace}.
 */
public class PgTraceStore implements EventSink {

  private final PgConfig config;
  private final DbClient dbClient;

  public PgTraceStore(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
  }

  @Override
  public void onEvent(HeliosEvent event) {
    switch (event) {
      case HeliosEvent.RunCompleted rc -> store(rc.trace());
      case HeliosEvent.RunFailed rf -> store(rf.trace());
      default -> {
        /* not interested */
      }
    }
  }

  /**
   * Stores a trace and all its spans in a single transaction.
   *
   * <p>Spans are inserted in BFS order (top-level first, then children) to satisfy the parent_id
   * foreign key constraint.
   */
  public void store(Trace trace) {
    var tx = dbClient.transaction();
    try {
      tx.dml(
          config.qualify(TraceSql.INSERT),
          trace.id().toString(),
          trace.name(),
          trace.startTime(),
          trace.endTime(),
          trace.error(),
          JsonbMapper.toJsonb(trace.attributes()),
          trace.inputText(),
          trace.outputText(),
          trace.userId(),
          trace.sessionId() != null ? trace.sessionId().toString() : null,
          trace.modelId(),
          trace.promptName(),
          trace.promptVersion(),
          trace.totalTokens(),
          trace.groupId(),
          JsonbMapper.listToJsonb(trace.labels()));

      insertSpansBfs(tx, trace.id(), trace.spans());

      tx.commit();
    } catch (Exception e) {
      try {
        tx.rollback();
      } catch (Exception rollbackEx) {
        e.addSuppressed(rollbackEx);
      }
      throw new PgException("Failed to store trace: " + trace.id(), e);
    }
  }

  /**
   * Finds a trace by id, reconstructing the full span tree.
   *
   * @return the trace with spans, or null if not found
   */
  public Trace findById(UUID id) {
    try {
      var traceOpt =
          dbClient
              .execute()
              .query(config.qualify(TraceSql.FIND_BY_ID), id.toString())
              .findFirst()
              .map(TraceMapper::map);

      if (traceOpt.isEmpty()) {
        return null;
      }

      var trace = traceOpt.get();
      var spans = reconstructSpanTree(id);

      return Trace.newBuilder(trace).withSpans(spans).build();
    } catch (Exception e) {
      throw new PgException("Failed to find trace: " + id, e);
    }
  }

  /**
   * Lists traces with optional SCIM filter and pagination. Returns traces without span trees
   * (summary mode).
   *
   * @param paginate pagination parameters (defaults to page 1, size 50 if null)
   * @param scimFilter optional SCIM filter string (e.g., {@code name eq "my-agent"})
   * @return paginated list of traces
   */
  public PaginatedList<Trace> list(Paginate paginate, String scimFilter) {
    if (paginate == null) {
      paginate = Paginate.of();
    }
    try {
      if (Strings.isBlank(scimFilter)) {
        var sql = config.qualify(TraceSql.LIST_PREFIX) + config.qualify(TraceSql.LIST_SUFFIX);
        var items =
            dbClient
                .execute()
                .createQuery(sql)
                .params(Map.of("limit", paginate.limit(), "offset", paginate.offset()))
                .execute()
                .map(TraceMapper::map)
                .toList();
        return PaginatedList.<Trace>newBuilder().withItems(items).withPaginate(paginate).build();
      }

      var engine = new ScimEngine();
      var filter = engine.parseFilter(scimFilter.trim(), "", null);
      var sql =
          config.qualify(TraceSql.LIST_PREFIX)
              + " WHERE "
              + filter.toClause()
              + " "
              + config.qualify(TraceSql.LIST_SUFFIX);
      var params = new HashMap<String, Object>(filter.context().indexedParams());
      params.put("limit", paginate.limit());
      params.put("offset", paginate.offset());
      var items =
          dbClient
              .execute()
              .createQuery(sql)
              .params(params)
              .execute()
              .map(TraceMapper::map)
              .toList();
      return PaginatedList.<Trace>newBuilder().withItems(items).withPaginate(paginate).build();
    } catch (Exception e) {
      throw new PgException("Failed to list traces", e);
    }
  }

  /** Stores an annotation. */
  public Annotation storeAnnotation(Annotation annotation) {
    try {
      dbClient
          .execute()
          .dml(
              config.qualify(AnnotationSql.INSERT),
              annotation.id().toString(),
              annotation.targetId().toString(),
              annotation.label(),
              annotation.rating(),
              annotation.comment(),
              annotation.createdAt(),
              annotation.authorId());
      return annotation;
    } catch (Exception e) {
      throw new PgException("Failed to store annotation: " + annotation.id(), e);
    }
  }

  /**
   * Stores or updates an annotation. When the annotation has an authorId and an annotation for the
   * same (targetId, authorId) already exists, it updates the existing annotation.
   */
  public Annotation upsertAnnotation(Annotation annotation) {
    try {
      dbClient
          .execute()
          .dml(
              config.qualify(AnnotationSql.UPSERT),
              annotation.id().toString(),
              annotation.targetId().toString(),
              annotation.label(),
              annotation.rating(),
              annotation.comment(),
              annotation.createdAt(),
              annotation.authorId());
      return annotation;
    } catch (Exception e) {
      throw new PgException("Failed to upsert annotation: " + annotation.id(), e);
    }
  }

  /** Finds all annotations for a given target (trace or span). */
  public List<Annotation> findAnnotations(UUID targetId) {
    try {
      return AnnotationMapper.mapAll(
          dbClient
              .execute()
              .query(config.qualify(AnnotationSql.FIND_BY_TARGET_ID), targetId.toString()));
    } catch (Exception e) {
      throw new PgException("Failed to find annotations for target: " + targetId, e);
    }
  }

  /**
   * Inserts spans in BFS order so parent spans are always inserted before their children.
   *
   * @param tx the active transaction
   * @param traceId the trace these spans belong to
   * @param topLevelSpans the top-level spans to insert
   */
  private void insertSpansBfs(DbTransaction tx, UUID traceId, List<Span> topLevelSpans) {

    record SpanWithParent(Span span, UUID parentId) {}

    var queue = new ArrayDeque<SpanWithParent>();
    for (var span : topLevelSpans) {
      queue.add(new SpanWithParent(span, null));
    }

    while (!queue.isEmpty()) {
      var entry = queue.poll();
      var span = entry.span();
      var parentId = entry.parentId();

      tx.dml(
          config.qualify(SpanSql.INSERT),
          span.id().toString(),
          traceId.toString(),
          parentId != null ? parentId.toString() : null,
          span.name(),
          span.kind().name(),
          span.startTime(),
          span.endTime(),
          span.error(),
          JsonbMapper.toJsonb(span.attributes()));

      for (var child : span.children()) {
        queue.add(new SpanWithParent(child, span.id()));
      }
    }
  }

  /**
   * Reconstructs the span tree from flat database rows.
   *
   * <p>Groups spans by parent_id and assembles children recursively.
   */
  private List<Span> reconstructSpanTree(UUID traceId) {
    record SpanRow(Span span, UUID parentId) {}

    var rows = new ArrayList<SpanRow>();
    dbClient
        .execute()
        .query(config.qualify(SpanSql.FIND_BY_TRACE_ID), traceId.toString())
        .forEach(
            (DbRow row) -> {
              var span = SpanMapper.map(row);
              var parentId = SpanMapper.parentId(row);
              rows.add(new SpanRow(span, parentId));
            });

    if (rows.isEmpty()) {
      return List.of();
    }

    Map<UUID, List<Span>> childrenByParentId = new LinkedHashMap<>();
    List<Span> roots = new ArrayList<>();

    for (var entry : rows) {
      if (entry.parentId() == null) {
        roots.add(entry.span());
      } else {
        childrenByParentId
            .computeIfAbsent(entry.parentId(), k -> new ArrayList<>())
            .add(entry.span());
      }
    }

    return roots.stream().map(root -> attachChildren(root, childrenByParentId)).toList();
  }

  private Span attachChildren(Span span, Map<UUID, List<Span>> childrenByParentId) {
    var children = childrenByParentId.get(span.id());
    if (children == null || children.isEmpty()) {
      return span;
    }

    var rebuiltChildren =
        children.stream().map(child -> attachChildren(child, childrenByParentId)).toList();

    return Span.newBuilder(span).withChildren(rebuiltChildren).build();
  }
}
