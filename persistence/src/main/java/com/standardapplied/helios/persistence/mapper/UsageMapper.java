/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence.mapper;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import io.helidon.dbclient.DbRow;
import java.util.List;

/**
 * Maps the shared usage/cost columns ({@code total_tokens}, {@code input_tokens}, {@code
 * output_tokens}, {@code cache_creation_tokens}, {@code cache_read_tokens}, {@code
 * reasoning_tokens}, {@code cost_micro_usd}) carried by both {@code helios_traces} and {@code
 * helios_spans}. All-null token class columns mean "no usage recorded" and map to {@code null},
 * distinct from a recorded zero.
 */
public final class UsageMapper {

  private UsageMapper() {}

  /**
   * Reads the token columns into a {@link Usage}, or {@code null} when none of the four token
   * classes was recorded. A row with any non-null class treats the absent counts as zero, a null
   * {@code reasoning_tokens} (a row written before the column existed) included. The total is the
   * four classes' sum; the stored {@code total_tokens} is not read.
   */
  public static Usage usage(DbRow row) {
    var input = intOrNull(row, "input_tokens");
    var output = intOrNull(row, "output_tokens");
    var cacheCreation = intOrNull(row, "cache_creation_tokens");
    var cacheRead = intOrNull(row, "cache_read_tokens");
    if (input == null && output == null && cacheCreation == null && cacheRead == null) {
      return null;
    }
    var reasoning = intOrNull(row, "reasoning_tokens");
    return Usage.of(
        input != null ? input : 0,
        output != null ? output : 0,
        cacheCreation != null ? cacheCreation : 0,
        cacheRead != null ? cacheRead : 0,
        reasoning != null ? reasoning : 0);
  }

  /**
   * Reads the {@code cost_micro_usd} column into a {@link CostEstimate}, or {@code null} when no
   * cost was recorded.
   */
  public static CostEstimate cost(DbRow row) {
    var costObj = row.column("cost_micro_usd").get(Object.class);
    return costObj != null ? CostEstimate.ofMicroUsd(((Number) costObj).longValue()) : null;
  }

  /**
   * Appends the six usage/cost bind values in column order ({@code input_tokens, output_tokens,
   * cache_creation_tokens, cache_read_tokens, reasoning_tokens, cost_micro_usd}), the write-side
   * counterpart of {@link #usage(DbRow)} and {@link #cost(DbRow)}; the caller binds {@code
   * total_tokens}, whose null handling differs by table. Null usage/cost bind all-null columns,
   * meaning "not recorded".
   */
  public static void addParams(List<Object> params, Usage usage, CostEstimate cost) {
    params.add(usage != null ? usage.inputTokens() : null);
    params.add(usage != null ? usage.outputTokens() : null);
    params.add(usage != null ? usage.cacheCreationInputTokens() : null);
    params.add(usage != null ? usage.cacheReadInputTokens() : null);
    params.add(usage != null ? usage.reasoningTokens() : null);
    params.add(cost != null ? cost.microUsd() : null);
  }

  private static Integer intOrNull(DbRow row, String column) {
    var value = row.column(column).get(Object.class);
    return value != null ? ((Number) value).intValue() : null;
  }
}
