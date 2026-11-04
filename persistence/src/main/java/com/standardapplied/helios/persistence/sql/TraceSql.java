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

  static final String FROM_TRACES_WITH_FEEDBACK =
      """
      FROM %s.helios_traces
      CROSS JOIN LATERAL (
          SELECT CAST(COUNT(*) FILTER (WHERE rating > 0) AS INT) AS up,
              CAST(COUNT(*) FILTER (WHERE rating < 0) AS INT) AS down
          FROM %s.helios_annotations
          WHERE subject_id = helios_traces.id
      ) feedback
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

  private static final String SELECT_WITH_FEEDBACK =
      "SELECT "
          + COLUMNS
          + """
          , feedback.up AS thumbs_up_count, feedback.down AS thumbs_down_count
          """
          + FROM_TRACES_WITH_FEEDBACK;

  public static final String FIND_BY_ID = SELECT_WITH_FEEDBACK + "WHERE id = ?\n";

  /**
   * Wraps the projection so a filter appended after it resolves {@code thumbs_up_count} and {@code
   * thumbs_down_count} to the derived counts, not the stored columns.
   */
  public static final String LIST_PREFIX = "SELECT * FROM (" + SELECT_WITH_FEEDBACK + ") traces\n";

  public static final String LIST_SUFFIX =
      """
      ORDER BY start_time DESC
      LIMIT :limit OFFSET :offset
      """;
}
