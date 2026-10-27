/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

/**
 * Another writer appended to the session since this writer last read or wrote it: the store's last
 * sequence number is not the one the append expected, and nothing was written. A session whose
 * append conflicts ends its run with {@code ErrorDuringExecution} of kind {@code SessionConflict}
 * and closes itself; reopening it loads what the store holds.
 */
public final class SessionConflictException extends SessionStoreException {

  /**
   * @param sessionId the session written to
   * @param expectedLastSeq the last sequence number the writer expected
   * @param lastSeq the last sequence number the store holds
   */
  public SessionConflictException(String sessionId, long expectedLastSeq, long lastSeq) {
    super(
        "session "
            + sessionId
            + " was written by another writer: the append expected last sequence number "
            + expectedLastSeq
            + ", the store holds "
            + lastSeq,
        null);
  }
}
