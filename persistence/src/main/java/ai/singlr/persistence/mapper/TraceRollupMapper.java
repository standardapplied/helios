/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.trace.TraceRollup;
import ai.singlr.core.trace.TraceRollupKey;
import ai.singlr.persistence.sql.TraceRollupSql;
import io.helidon.dbclient.DbRow;

/**
 * Maps Helidon {@link DbRow} results of a rollup query to {@link TraceRollup} records. Dimension
 * columns and key names come from {@link TraceRollupSql#dimensions(TraceRollupKey)} — the single
 * definition the query builder also uses.
 */
public final class TraceRollupMapper {

  private TraceRollupMapper() {}

  /**
   * Maps a single aggregation row for the given grouping key.
   *
   * @param row the database row
   * @param key the grouping dimension the query ran with
   * @return the mapped rollup
   */
  public static TraceRollup map(DbRow row, TraceRollupKey key) {
    var builder = TraceRollup.newBuilder();
    for (var dimension : TraceRollupSql.dimensions(key)) {
      builder.withKeyValue(
          dimension.mapKey(), String.valueOf(row.column(dimension.column()).get(Object.class)));
    }
    return builder
        .withRunCount(row.column("run_count").getLong())
        .withErrorCount(row.column("error_count").getLong())
        .withDurationP50Millis(row.column("duration_p50_millis").getLong())
        .withDurationP95Millis(row.column("duration_p95_millis").getLong())
        .withInputTokens(row.column("input_tokens").getLong())
        .withOutputTokens(row.column("output_tokens").getLong())
        .withCacheCreationTokens(row.column("cache_creation_tokens").getLong())
        .withCacheReadTokens(row.column("cache_read_tokens").getLong())
        .withTotalTokens(row.column("total_tokens").getLong())
        .withCostMicroUsd(row.column("cost_micro_usd").getLong())
        .withThumbsUpCount(row.column("thumbs_up_count").getLong())
        .withThumbsDownCount(row.column("thumbs_down_count").getLong())
        .build();
  }
}
