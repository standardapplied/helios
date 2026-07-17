/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.trace.TraceRollup;
import ai.singlr.core.trace.TraceRollupKey;
import io.helidon.dbclient.DbRow;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps Helidon {@link DbRow} results of a rollup query to {@link TraceRollup} records. */
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
    return new TraceRollup(
        keyValues(row, key),
        row.column("run_count").getLong(),
        row.column("error_count").getLong(),
        row.column("duration_p50_millis").getLong(),
        row.column("duration_p95_millis").getLong(),
        row.column("input_tokens").getLong(),
        row.column("output_tokens").getLong(),
        row.column("cache_creation_tokens").getLong(),
        row.column("cache_read_tokens").getLong(),
        row.column("total_tokens").getLong(),
        row.column("cost_micro_usd").getLong(),
        row.column("thumbs_up_count").getLong(),
        row.column("thumbs_down_count").getLong());
  }

  private static Map<String, String> keyValues(DbRow row, TraceRollupKey key) {
    var values = new LinkedHashMap<String, String>();
    switch (key) {
      case GROUP_ID -> values.put("groupId", row.column("group_id").getString());
      case PROMPT -> {
        values.put("promptName", row.column("prompt_name").getString());
        values.put("promptVersion", String.valueOf(row.column("prompt_version").getInt()));
      }
      case NAME -> values.put("name", row.column("name").getString());
      case MODEL_ID -> values.put("modelId", row.column("model_id").getString());
    }
    return values;
  }
}
