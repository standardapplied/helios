/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import java.util.List;
import java.util.Objects;

/**
 * Where a session's conversation lives between processes: an append-only log of {@link SessionEntry
 * entries} per session id, numbered from {@code 1} without gaps. A session loads the log when it
 * opens and appends one batch per turn; the store refuses a batch written against a last sequence
 * number it no longer holds, so two writers to one session are detected, never merged.
 *
 * <p>Implementations are thread-safe.
 */
public interface SessionStore {

  /**
   * Load a session.
   *
   * @param sessionId the session id; valid per {@link #requireValidId}
   * @return the session's history and last sequence number; for a session the store has never seen,
   *     an empty history at {@code 0}
   * @throws SessionStoreException if the store cannot be read
   */
  StoredSession load(String sessionId);

  /**
   * Append {@code entries} as one batch, all or nothing: they get the sequence numbers {@code
   * expectedLastSeq + 1}, {@code + 2}, and so on.
   *
   * @param sessionId the session id; valid per {@link #requireValidId}
   * @param expectedLastSeq the last sequence number the writer read or wrote
   * @param entries the batch; non-empty
   * @throws IllegalArgumentException if {@code entries} is empty
   * @throws SessionConflictException if the store's last sequence number is not {@code
   *     expectedLastSeq}; nothing is written
   * @throws SessionStoreException if the store cannot be written
   */
  void append(String sessionId, long expectedLastSeq, List<SessionEntry> entries);

  /**
   * Check a session id against the rule every store relies on: one to 128 characters of {@code
   * [A-Za-z0-9._:-]}, not starting with {@code .}, so an id can never name a path outside a file
   * store's directory.
   *
   * @param sessionId the id to check
   * @return {@code sessionId}
   * @throws NullPointerException if {@code sessionId} is null
   * @throws IllegalArgumentException if {@code sessionId} breaks the rule
   */
  static String requireValidId(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (!sessionId.matches("(?!\\.)[A-Za-z0-9._:-]{1,128}")) {
      throw new IllegalArgumentException(
          "sessionId must be 1 to 128 characters of [A-Za-z0-9._:-] not starting with '.'");
    }
    return sessionId;
  }
}
