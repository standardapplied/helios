/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for {@code helios_agent_runs} operations. */
public final class AgentRunSql {

  private AgentRunSql() {}

  public static final String UPSERT =
      """
      INSERT INTO %s.helios_agent_runs (
          run_id, session_id, agent_id, user_id, status, iteration,
          started_at, last_checkpoint_at, ended_at, error)
      VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (run_id) DO UPDATE SET
          session_id = EXCLUDED.session_id,
          agent_id = EXCLUDED.agent_id,
          user_id = EXCLUDED.user_id,
          status = EXCLUDED.status,
          iteration = EXCLUDED.iteration,
          last_checkpoint_at = EXCLUDED.last_checkpoint_at,
          ended_at = EXCLUDED.ended_at,
          error = EXCLUDED.error
      """;

  public static final String FIND_BY_ID =
      """
      SELECT run_id, session_id, agent_id, user_id, status, iteration,
             started_at, last_checkpoint_at, ended_at, error
      FROM %s.helios_agent_runs
      WHERE run_id = CAST(? AS UUID)
      """;

  public static final String FIND_BY_STATUS =
      """
      SELECT run_id, session_id, agent_id, user_id, status, iteration,
             started_at, last_checkpoint_at, ended_at, error
      FROM %s.helios_agent_runs
      WHERE status = ?
      ORDER BY last_checkpoint_at DESC
      """;

  public static final String PURGE_TOOL_CALLS_FOR_TERMINAL_RUNS_OLDER_THAN =
      """
      DELETE FROM %s.helios_tool_calls
      WHERE run_id IN (
          SELECT run_id FROM %s.helios_agent_runs
          WHERE status IN ('COMPLETED', 'FAILED')
            AND ended_at IS NOT NULL
            AND ended_at < ?
      )
      """;

  public static final String PURGE_TERMINAL_RUNS_OLDER_THAN =
      """
      DELETE FROM %s.helios_agent_runs
      WHERE status IN ('COMPLETED', 'FAILED')
        AND ended_at IS NOT NULL
        AND ended_at < ?
      """;
}
