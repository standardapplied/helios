/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import ai.singlr.scimsql.ScimEngine;
import com.standardapplied.helios.core.common.Paginate;
import com.standardapplied.helios.core.common.PaginatedList;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.events.EventSink;
import com.standardapplied.helios.core.events.HeliosEvent;
import com.standardapplied.helios.core.trace.Trace;
import com.standardapplied.helios.core.trace.TraceFilter;
import com.standardapplied.helios.core.trace.TraceRollup;
import com.standardapplied.helios.core.trace.TraceRollupKey;
import com.standardapplied.helios.persistence.mapper.JsonbMapper;
import com.standardapplied.helios.persistence.mapper.TraceMapper;
import com.standardapplied.helios.persistence.mapper.TraceRollupMapper;
import com.standardapplied.helios.persistence.mapper.UsageMapper;
import com.standardapplied.helios.persistence.sql.TraceRollupSql;
import com.standardapplied.helios.persistence.sql.TraceSql;
import io.helidon.dbclient.DbClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL-backed store for traces and their spans. Annotations on a trace or span are stored by
 * {@link PgAnnotationStore}.
 *
 * <p>Implements {@link EventSink} so callers can wire it directly into any producer of {@link
 * HeliosEvent.RunCompleted} / {@link HeliosEvent.RunFailed} events; the store persists the carried
 * {@link Trace}.
 */
public class PgTraceStore implements EventSink {

  private final PgConfig config;
  private final DbClient dbClient;
  private final PgSpanTree spanTree;

  public PgTraceStore(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
    this.spanTree = new PgSpanTree(config);
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
      var params = new ArrayList<Object>();
      params.add(trace.id().toString());
      params.add(trace.name());
      params.add(trace.startTime());
      params.add(trace.endTime());
      params.add(config.redact(trace.error()));
      params.add(JsonbMapper.toJsonb(config.redactValues(trace.attributes())));
      params.add(config.redact(trace.inputText()));
      params.add(config.redact(trace.outputText()));
      params.add(trace.userId());
      params.add(trace.sessionId() != null ? trace.sessionId().toString() : null);
      params.add(trace.modelId());
      params.add(trace.promptName());
      params.add(trace.promptVersion());
      params.add(trace.totalTokens());
      UsageMapper.addParams(params, trace.usage(), trace.cost());
      params.add(trace.groupId());
      params.add(JsonbMapper.listToJsonb(trace.labels()));
      tx.dml(config.qualify(TraceSql.INSERT), params.toArray());

      spanTree.insert(tx, trace.id(), trace.spans());

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
      var spans = spanTree.find(id);

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

  /**
   * Aggregates stored traces grouped by the given key: run and error counts, duration percentiles,
   * token and cost sums, and feedback counts per group. Traces with a null value for a grouping
   * dimension are excluded — a trace without a {@code groupId} belongs to no eval group.
   *
   * @param key the grouping dimension; non-null
   * @param filter equality/time-window constraints; non-null, {@link TraceFilter#none()} for all
   * @return one rollup per group, ordered by the grouping dimension values
   */
  public List<TraceRollup> summarize(TraceRollupKey key, TraceFilter filter) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(filter, "filter");
    var query = TraceRollupSql.build(key, filter);
    try {
      return dbClient
          .execute()
          .query(config.qualify(query.sql()), query.params().toArray())
          .map(row -> TraceRollupMapper.map(row, key))
          .toList();
    } catch (Exception e) {
      throw new PgException("Failed to summarize traces by " + key, e);
    }
  }
}
