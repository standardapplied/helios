/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for {@code helios_tool_calls} journal operations. */
public final class ToolCallJournalSql {

  private ToolCallJournalSql() {}

  public static final String INSERT =
      """
      INSERT INTO %s.helios_tool_calls (
          run_id, tool_call_id, iteration, tool_name, args, status,
          output, error, started_at, ended_at)
      VALUES (CAST(? AS UUID), ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?)
      """;

  public static final String COMPLETE =
      """
      UPDATE %s.helios_tool_calls
      SET status = ?, output = ?, ended_at = ?
      WHERE run_id = CAST(? AS UUID) AND tool_call_id = ? AND status = 'STARTED'
      """;

  public static final String FAIL =
      """
      UPDATE %s.helios_tool_calls
      SET status = ?, error = ?, ended_at = ?
      WHERE run_id = CAST(? AS UUID) AND tool_call_id = ? AND status = 'STARTED'
      """;

  public static final String FIND_INFLIGHT =
      """
      SELECT run_id, tool_call_id, iteration, tool_name, args, status,
             output, error, started_at, ended_at
      FROM %s.helios_tool_calls
      WHERE run_id = CAST(? AS UUID) AND status = 'STARTED'
      ORDER BY started_at
      """;

  public static final String FIND_ALL =
      """
      SELECT run_id, tool_call_id, iteration, tool_name, args, status,
             output, error, started_at, ended_at
      FROM %s.helios_tool_calls
      WHERE run_id = CAST(? AS UUID)
      ORDER BY started_at
      """;
}
