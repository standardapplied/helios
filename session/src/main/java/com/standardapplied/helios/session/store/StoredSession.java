/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import com.standardapplied.helios.core.common.Validate;
import com.standardapplied.helios.core.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A session as its store holds it: the history a reopened session continues from, and the last
 * sequence number, against which the session's next append is checked.
 *
 * @param sessionId the session id; non-blank
 * @param lastSeq the last sequence number, {@code 0} for a session never written; non-negative
 * @param history the model's view, without the system prompt; copied, no null element
 */
public record StoredSession(String sessionId, long lastSeq, List<Message> history) {

  /**
   * @throws NullPointerException if {@code sessionId} or {@code history} is null, or {@code
   *     history} holds a null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code lastSeq} is negative
   */
  public StoredSession {
    Validate.notBlank("sessionId", sessionId);
    if (lastSeq < 0) {
      throw new IllegalArgumentException("lastSeq must not be negative, got " + lastSeq);
    }
    history = List.copyOf(Objects.requireNonNull(history, "history must not be null"));
  }

  /**
   * Fold a log into the session it stores, the one load rule every store shares: the history of the
   * last {@link SessionEntry.HistoryReplaced} in {@code tail}, or none, followed by the message of
   * every {@link SessionEntry.MessageAdded} after it.
   *
   * @param sessionId the session id; non-blank
   * @param lastSeq the sequence number of the log's last entry; non-negative
   * @param tail the log in order, starting at its last {@code HistoryReplaced} or earlier
   * @return the stored session
   * @throws NullPointerException if {@code tail} is null or holds a null
   */
  public static StoredSession fold(String sessionId, long lastSeq, List<SessionEntry> tail) {
    var history = new ArrayList<Message>();
    for (var entry : Objects.requireNonNull(tail, "tail must not be null")) {
      switch (Objects.requireNonNull(entry, "tail must not contain null")) {
        case SessionEntry.MessageAdded added -> history.add(added.message());
        case SessionEntry.HistoryReplaced replaced -> {
          history.clear();
          history.addAll(replaced.history());
        }
      }
    }
    return new StoredSession(sessionId, lastSeq, history);
  }
}
