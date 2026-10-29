/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence.sql;

/** SQL constants for {@code helios_session_entries} operations. */
public final class SessionEntrySql {

  private SessionEntrySql() {}

  /**
   * Insert a batch's first entry only when the session's last sequence number is the one the writer
   * expects; parameters are the row's six columns, then the session id and that number.
   */
  public static final String INSERT_FIRST =
      """
      INSERT INTO %s.helios_session_entries (session_id, seq, type, run_id, entry, created_at)
      SELECT ?, ?, ?, ?, ?, ?
      WHERE (SELECT COALESCE(MAX(seq), 0) FROM %s.helios_session_entries WHERE session_id = ?) = ?
      """;

  public static final String INSERT =
      """
      INSERT INTO %s.helios_session_entries (session_id, seq, type, run_id, entry, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
      """;

  /** The rows from the session's last {@code replaced} row, or its first row, to its last. */
  public static final String LOAD =
      """
      SELECT seq, entry
      FROM %s.helios_session_entries
      WHERE session_id = ?
        AND seq >= COALESCE(
            (SELECT MAX(seq) FROM %s.helios_session_entries
             WHERE session_id = ? AND type = 'replaced'),
            0)
      ORDER BY seq
      """;

  public static final String ENTRIES =
      """
      SELECT seq, entry
      FROM %s.helios_session_entries
      WHERE session_id = ?
      ORDER BY seq
      """;

  public static final String LAST_SEQ =
      """
      SELECT COALESCE(MAX(seq), 0) AS last_seq
      FROM %s.helios_session_entries
      WHERE session_id = ?
      """;

  public static final String DELETE =
      """
      DELETE FROM %s.helios_session_entries
      WHERE session_id = ?
      """;
}
