/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.persistence.sql;

/** SQL constants for session registry operations. */
public final class SessionSql {

  private SessionSql() {}

  public static final String UPSERT =
      """
      INSERT INTO %s.helios_sessions (id, agent_id, user_id, created_at, last_active_at)
      VALUES (CAST(? AS UUID), ?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET last_active_at = EXCLUDED.last_active_at
      """;

  public static final String FIND_LATEST =
      """
      SELECT id
      FROM %s.helios_sessions
      WHERE agent_id = ? AND user_id = ?
      ORDER BY last_active_at DESC
      LIMIT 1
      """;

  public static final String FIND_BY_USER =
      """
      SELECT id
      FROM %s.helios_sessions
      WHERE agent_id = ? AND user_id = ?
      ORDER BY last_active_at DESC
      """;

  /**
   * Delete sessions with {@code last_active_at} older than the supplied cutoff. Messages cascade
   * automatically via the {@code helios_messages.session_id} FK declared with {@code ON DELETE
   * CASCADE}. Scoped to the active agent via the bound {@code agent_id}.
   */
  public static final String PURGE_OLDER_THAN =
      """
      DELETE FROM %s.helios_sessions
      WHERE agent_id = ? AND last_active_at < ?
      """;
}
