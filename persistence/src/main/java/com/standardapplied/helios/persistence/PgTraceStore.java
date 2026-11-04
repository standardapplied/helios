/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import ai.singlr.scimsql.ScimEngine;
import com.standardapplied.helios.core.common.Paginate;
import com.standardapplied.helios.core.common.PaginatedList;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.persistence.mapper.SpanMapper;
import com.standardapplied.helios.persistence.mapper.TraceMapper;
import com.standardapplied.helios.persistence.mapper.TraceRollupMapper;
import com.standardapplied.helios.persistence.sql.SpanSql;
import com.standardapplied.helios.persistence.sql.TraceRollupSql;
import com.standardapplied.helios.persistence.sql.TraceSql;
import io.helidon.dbclient.DbClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the runs and spans {@link PgSpanExporter} stored. Annotations on a run or span are stored
 * by {@link PgAnnotationStore}.
 *
 * <p>A run's span rows are written as its steps end and its run row once the run itself ended, so
 * the span rows of a run the process died in exist with no run row: {@link #findById} returns null
 * for that run, and nothing cleans the rows up.
 */
public class PgTraceStore {

  private final PgConfig config;
  private final DbClient dbClient;

  public PgTraceStore(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
  }

  /**
   * Finds a run by its id, with its spans ordered by start time, then id.
   *
   * @return the run with its spans, or null if no run row was stored under {@code runId}
   */
  public Trace findById(String runId) {
    try {
      var trace =
          dbClient
              .execute()
              .query(config.qualify(TraceSql.FIND_BY_ID), runId)
              .findFirst()
              .map(TraceMapper::map);
      if (trace.isEmpty()) {
        return null;
      }
      var spans =
          dbClient
              .execute()
              .query(config.qualify(SpanSql.FIND_BY_RUN_ID), runId)
              .map(SpanMapper::map)
              .toList();
      return Trace.newBuilder(trace.get()).withSpans(spans).build();
    } catch (Exception e) {
      throw new PgException("Failed to find trace: " + runId, e);
    }
  }

  /**
   * Lists runs with optional SCIM filter and pagination, without their spans.
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
   * Aggregates stored runs grouped by the given key: run and error counts, duration percentiles,
   * token and cost sums, and feedback counts per group. Runs with a null value for the grouping
   * dimension are excluded.
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
