/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence.mapper;

import com.standardapplied.helios.persistence.TraceRollup;
import com.standardapplied.helios.persistence.TraceRollupKey;
import com.standardapplied.helios.persistence.sql.TraceRollupSql;
import io.helidon.dbclient.DbRow;

/**
 * Maps Helidon {@link DbRow} results of a rollup query to {@link TraceRollup} records. The
 * dimension's column and key name come from {@link TraceRollupSql#dimension(TraceRollupKey)} — the
 * single definition the query builder also uses.
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
    var dimension = TraceRollupSql.dimension(key);
    return TraceRollup.newBuilder()
        .withKeyValue(dimension.mapKey(), row.column(dimension.column()).getString())
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
