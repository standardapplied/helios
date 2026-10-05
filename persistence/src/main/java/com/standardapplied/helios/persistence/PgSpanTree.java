/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import com.standardapplied.helios.core.trace.Span;
import com.standardapplied.helios.persistence.mapper.JsonbMapper;
import com.standardapplied.helios.persistence.mapper.SpanMapper;
import com.standardapplied.helios.persistence.mapper.UsageMapper;
import com.standardapplied.helios.persistence.sql.SpanSql;
import io.helidon.dbclient.DbClient;
import io.helidon.dbclient.DbRow;
import io.helidon.dbclient.DbTransaction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A trace's span tree in {@code helios_spans}: inserts it as flat rows, parents before children,
 * and reconstructs the tree from those rows.
 */
final class PgSpanTree {

  private final PgConfig config;
  private final DbClient dbClient;

  PgSpanTree(PgConfig config) {
    this.config = config;
    this.dbClient = config.dbClient();
  }

  /**
   * Inserts spans in BFS order so parent spans are always inserted before their children.
   *
   * @param tx the active transaction
   * @param traceId the trace these spans belong to
   * @param topLevelSpans the top-level spans to insert
   */
  void insert(DbTransaction tx, UUID traceId, List<Span> topLevelSpans) {

    record SpanWithParent(Span span, UUID parentId) {}

    var queue = new ArrayDeque<SpanWithParent>();
    for (var span : topLevelSpans) {
      queue.add(new SpanWithParent(span, null));
    }

    while (!queue.isEmpty()) {
      var entry = queue.poll();
      var span = entry.span();
      var parentId = entry.parentId();

      var params = new ArrayList<Object>();
      params.add(span.id().toString());
      params.add(traceId.toString());
      params.add(parentId != null ? parentId.toString() : null);
      params.add(span.name());
      params.add(span.kind().name());
      params.add(span.startTime());
      params.add(span.endTime());
      params.add(config.redact(span.error()));
      params.add(JsonbMapper.toJsonb(config.redactValues(span.attributes())));
      UsageMapper.addParams(params, span.usage(), span.cost());
      tx.dml(config.qualify(SpanSql.INSERT), params.toArray());

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
  List<Span> find(UUID traceId) {
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
