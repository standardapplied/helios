/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence.sql;

/** SQL constants for trace operations. */
public final class TraceSql {

  private TraceSql() {}

  private static final String COLUMNS =
      """
      id, otel_trace_id, otel_span_id, name, start_time, end_time, error, attributes,
          input_text, output_text, session_id, model_id, total_tokens,
          input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens, cost_micro_usd\
      """;

  public static final String INSERT =
      "INSERT INTO %s.helios_traces ("
          + COLUMNS
          + """
          )
          VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB),
              ?, ?, ?, ?, ?,
              ?, ?, ?, ?, ?)
          """;

  public static final String FIND_BY_ID =
      "SELECT "
          + COLUMNS
          + """
          , thumbs_up_count, thumbs_down_count
          FROM %s.helios_traces
          WHERE id = ?
          """;

  public static final String LIST_PREFIX =
      "SELECT "
          + COLUMNS
          + """
          , thumbs_up_count, thumbs_down_count
          FROM %s.helios_traces
          """;

  public static final String LIST_SUFFIX =
      """
      ORDER BY start_time DESC
      LIMIT :limit OFFSET :offset
      """;
}
