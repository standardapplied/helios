/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

import ai.singlr.core.trace.TraceFilter;
import ai.singlr.core.trace.TraceRollupKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the parameterized aggregation query behind {@code PgTraceStore.summarize}. Filter values
 * always travel as positional bind parameters — never concatenated into the SQL — and the schema
 * stays a {@code %s} placeholder for {@code PgConfig.qualify}. The key-to-column mapping lives only
 * here; {@code TraceRollupMapper} reads dimensions through {@link #dimensions(TraceRollupKey)} so
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
   * The grouping dimensions for a rollup key, in key-map order.
   *
   * @param key the grouping dimension
   * @return column + map-key pairs to group by
   */
  public static List<Dimension> dimensions(TraceRollupKey key) {
    return switch (key) {
      case GROUP_ID -> List.of(new Dimension("group_id", "groupId"));
      case PROMPT ->
          List.of(
              new Dimension("prompt_name", "promptName"),
              new Dimension("prompt_version", "promptVersion"));
      case NAME -> List.of(new Dimension("name", "name"));
      case MODEL_ID -> List.of(new Dimension("model_id", "modelId"));
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
    var columns = dimensions(key).stream().map(Dimension::column).toList();
    var dims = String.join(", ", columns);
    var where = new StringBuilder();
    var params = new ArrayList<Object>();
    for (var column : columns) {
      and(where).append(column).append(" IS NOT NULL");
    }
    if (filter.name() != null) {
      and(where).append("name = ?");
      params.add(filter.name());
    }
    if (filter.groupId() != null) {
      and(where).append("group_id = ?");
      params.add(filter.groupId());
    }
    if (filter.promptName() != null) {
      and(where).append("prompt_name = ?");
      params.add(filter.promptName());
    }
    if (filter.promptVersion() != null) {
      and(where).append("prompt_version = ?");
      params.add(filter.promptVersion());
    }
    if (filter.since() != null) {
      and(where).append("start_time >= ?");
      params.add(filter.since());
    }
    if (filter.until() != null) {
      and(where).append("start_time < ?");
      params.add(filter.until());
    }
    var sql =
        "SELECT "
            + dims
            + ",\n"
            + AGGREGATES
            + "FROM %s.helios_traces\nWHERE "
            + where
            + "\nGROUP BY "
            + dims
            + "\nORDER BY "
            + dims;
    return new RollupQuery(sql, params);
  }

  private static StringBuilder and(StringBuilder where) {
    if (!where.isEmpty()) {
      where.append(" AND ");
    }
    return where;
  }
}
