/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for trace operations. */
public final class TraceSql {

  private TraceSql() {}

  public static final String INSERT =
      """
      INSERT INTO %s.helios_traces (id, name, start_time, end_time, error, attributes,
          input_text, output_text, user_id, session_id, model_id,
          prompt_name, prompt_version, total_tokens,
          input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens, cost_micro_usd,
          group_id, labels)
      VALUES (CAST(? AS UUID), ?, ?, ?, ?, CAST(? AS JSONB),
          ?, ?, ?, CAST(? AS UUID), ?,
          ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?, CAST(? AS JSONB))
      """;

  public static final String FIND_BY_ID =
      """
      SELECT id, name, start_time, end_time, error, attributes,
          input_text, output_text, user_id, session_id, model_id,
          prompt_name, prompt_version, total_tokens,
          input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens, cost_micro_usd,
          thumbs_up_count, thumbs_down_count, group_id, labels
      FROM %s.helios_traces
      WHERE id = CAST(? AS UUID)
      """;

  public static final String LIST_PREFIX =
      """
      SELECT id, name, start_time, end_time, error, attributes,
          input_text, output_text, user_id, session_id, model_id,
          prompt_name, prompt_version, total_tokens,
          input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens, cost_micro_usd,
          thumbs_up_count, thumbs_down_count, group_id, labels
      FROM %s.helios_traces
      """;

  public static final String LIST_SUFFIX =
      """
      ORDER BY start_time DESC
      LIMIT :limit OFFSET :offset
      """;
}
