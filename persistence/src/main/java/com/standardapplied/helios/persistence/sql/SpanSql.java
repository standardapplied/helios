/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence.sql;

/** SQL constants for span operations. */
public final class SpanSql {

  private SpanSql() {}

  public static final String INSERT =
      """
      INSERT INTO %s.helios_spans (id, trace_id, parent_id, name, kind, start_time, end_time,
          error, attributes,
          input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens, cost_micro_usd)
      VALUES (CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?,
          ?, CAST(? AS JSONB),
          ?, ?, ?, ?, ?)
      """;

  public static final String FIND_BY_TRACE_ID =
      """
      SELECT id, trace_id, parent_id, name, kind, start_time, end_time, error, attributes,
          input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens, cost_micro_usd
      FROM %s.helios_spans
      WHERE trace_id = CAST(? AS UUID)
      """;
}
