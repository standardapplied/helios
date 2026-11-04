/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence.sql;

import com.standardapplied.helios.persistence.TraceFilter;
import com.standardapplied.helios.persistence.TraceRollupKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the parameterized aggregation query behind {@code PgTraceStore.summarize}. Filter values
 * always travel as positional bind parameters — never concatenated into the SQL — and the schema
 * stays a {@code %s} placeholder for {@code PgConfig.qualify}. The key-to-column mapping lives only
 * here; {@code TraceRollupMapper} reads the dimension through {@link #dimension(TraceRollupKey)} so
 * query and mapper cannot drift.
 */
public final class TraceRollupSql {

  private TraceRollupSql() {}

  /**
   * One grouping dimension: the {@code helios_traces} column it groups by and the camelCase key
   * under which its value appears in {@code TraceRollup.key()}.
   */
  public record Dimension(String column, String mapKey) {}

  /**
   * A rollup query ready for execution: SQL with {@code %s} schema placeholder and the positional
   * bind parameters in order.
   */
  public record RollupQuery(String sql, List<Object> params) {
    public RollupQuery {
      params = List.copyOf(params);
    }
  }

  private static final String DURATION_PERCENTILES =
      "percentile_cont(ARRAY[0.5, 0.95]) WITHIN GROUP (ORDER BY "
          + "CAST(EXTRACT(EPOCH FROM (end_time - start_time)) * 1000 AS DOUBLE PRECISION))";

  private static final String AGGREGATES =
      """
      COUNT(*) AS run_count,
      COUNT(error) AS error_count,
      CAST(COALESCE((%1$s)[1], 0) AS BIGINT) AS duration_p50_millis,
      CAST(COALESCE((%1$s)[2], 0) AS BIGINT) AS duration_p95_millis,
      CAST(COALESCE(SUM(input_tokens), 0) AS BIGINT) AS input_tokens,
      CAST(COALESCE(SUM(output_tokens), 0) AS BIGINT) AS output_tokens,
      CAST(COALESCE(SUM(cache_creation_tokens), 0) AS BIGINT) AS cache_creation_tokens,
      CAST(COALESCE(SUM(cache_read_tokens), 0) AS BIGINT) AS cache_read_tokens,
      CAST(COALESCE(SUM(total_tokens), 0) AS BIGINT) AS total_tokens,
      CAST(COALESCE(SUM(cost_micro_usd), 0) AS BIGINT) AS cost_micro_usd,
      CAST(COALESCE(SUM(thumbs_up_count), 0) AS BIGINT) AS thumbs_up_count,
      CAST(COALESCE(SUM(thumbs_down_count), 0) AS BIGINT) AS thumbs_down_count
      """
          .formatted(DURATION_PERCENTILES);

  /**
   * The grouping dimension of a rollup key.
   *
   * @param key the grouping dimension
   * @return the column to group by and its map key
   */
  public static Dimension dimension(TraceRollupKey key) {
    return switch (key) {
      case NAME -> new Dimension("name", "name");
      case MODEL_ID -> new Dimension("model_id", "modelId");
    };
  }

  /**
   * Builds the rollup query for the given key and filter.
   *
   * @param key the grouping dimension; non-null
   * @param filter the constraints; non-null, use {@link TraceFilter#none()} for all traces
   * @return the SQL and its positional bind parameters
   */
  public static RollupQuery build(TraceRollupKey key, TraceFilter filter) {
    var column = dimension(key).column();
    var where = new StringBuilder(column).append(" IS NOT NULL");
    var params = new ArrayList<Object>();
    if (filter.name() != null) {
      where.append(" AND name = ?");
      params.add(filter.name());
    }
    if (filter.since() != null) {
      where.append(" AND start_time >= ?");
      params.add(filter.since());
    }
    if (filter.until() != null) {
      where.append(" AND start_time < ?");
      params.add(filter.until());
    }
    var sql =
        "SELECT "
            + column
            + ",\n"
            + AGGREGATES
            + "FROM %s.helios_traces\nWHERE "
            + where
            + "\nGROUP BY "
            + column
            + "\nORDER BY "
            + column;
    return new RollupQuery(sql, params);
  }
}
