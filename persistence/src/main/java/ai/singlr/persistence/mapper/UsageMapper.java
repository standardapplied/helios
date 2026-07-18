/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Response.Usage;
import io.helidon.dbclient.DbRow;

/**
 * Maps the shared usage/cost columns ({@code input_tokens}, {@code output_tokens}, {@code
 * cache_creation_tokens}, {@code cache_read_tokens}, {@code cost_micro_usd}) carried by both {@code
 * helios_traces} and {@code helios_spans}. All-null token columns mean "no usage recorded" and map
 * to {@code null}, distinct from a recorded zero.
 */
public final class UsageMapper {

  private UsageMapper() {}

  /**
   * Reads the four token-class columns into a {@link Usage}, or {@code null} when none of them was
   * recorded. A row with any non-null class treats the absent classes as zero.
   *
   * <p>Only the four classes are persisted, so the reconstructed {@code totalTokens} is their sum.
   * A provider-reported total covering token classes outside the four (possible via the canonical
   * {@code Usage} constructor) is not round-tripped; the trace-level {@code total_tokens} column
   * preserves the trace's original total independently.
   */
  public static Usage usage(DbRow row) {
    var input = intOrNull(row, "input_tokens");
    var output = intOrNull(row, "output_tokens");
    var cacheCreation = intOrNull(row, "cache_creation_tokens");
    var cacheRead = intOrNull(row, "cache_read_tokens");
    if (input == null && output == null && cacheCreation == null && cacheRead == null) {
      return null;
    }
    return Usage.of(
        input != null ? input : 0,
        output != null ? output : 0,
        cacheCreation != null ? cacheCreation : 0,
        cacheRead != null ? cacheRead : 0);
  }

  /**
   * Reads the {@code cost_micro_usd} column into a {@link CostEstimate}, or {@code null} when no
   * cost was recorded.
   */
  public static CostEstimate cost(DbRow row) {
    var costObj = row.column("cost_micro_usd").get(Object.class);
    return costObj != null ? CostEstimate.ofMicroUsd(((Number) costObj).longValue()) : null;
  }

  private static Integer intOrNull(DbRow row, String column) {
    var value = row.column(column).get(Object.class);
    return value != null ? ((Number) value).intValue() : null;
  }
}
