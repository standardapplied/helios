/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import com.standardapplied.helios.core.common.Validate;
import com.standardapplied.helios.core.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A session as its store holds it: the history a reopened session continues from, the last sequence
 * number, against which the session's next append is checked, and whether the run that wrote last
 * ended.
 *
 * <p>A host that runs turns from a durable queue tells after a crash whether its run {@code runId}
 * ended with {@code lastRunEnded().filter(ended -> ended.runId().equals(runId))}. When that names
 * {@code Success}, the run's answer is the last {@link
 * com.standardapplied.helios.core.model.Role#ASSISTANT ASSISTANT} message of {@link #history()}
 * that carries no {@link MessageOrigin}.
 *
 * @param sessionId the session id; non-blank
 * @param lastSeq the last sequence number, {@code 0} for a session never written; non-negative
 * @param history the model's view, without the system prompt; copied, no null element
 * @param lastRunEnded the log's last entry when it is a {@link SessionEntry.RunEnded}: the run that
 *     wrote last ended; empty for an empty log, or when that run had not ended, as after a crash;
 *     non-null
 */
public record StoredSession(
    String sessionId,
    long lastSeq,
    List<Message> history,
    Optional<SessionEntry.RunEnded> lastRunEnded) {

  /**
   * Validates the session and copies {@code history}.
   *
   * @throws NullPointerException if {@code sessionId}, {@code history} or {@code lastRunEnded} is
   *     null, or {@code history} holds a null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code lastSeq} is negative
   */
  public StoredSession {
    Validate.notBlank("sessionId", sessionId);
    if (lastSeq < 0) {
      throw new IllegalArgumentException("lastSeq must not be negative, got " + lastSeq);
    }
    history = List.copyOf(Objects.requireNonNull(history, "history must not be null"));
    Objects.requireNonNull(lastRunEnded, "lastRunEnded must not be null");
  }

  /**
   * A session whose last writer is not known to have ended, as a 3.0.0 store loaded it.
   *
   * @param sessionId the session id; non-blank
   * @param lastSeq the last sequence number, {@code 0} for a session never written; non-negative
   * @param history the model's view, without the system prompt; copied, no null element
   * @throws NullPointerException if {@code sessionId} or {@code history} is null, or {@code
   *     history} holds a null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code lastSeq} is negative
   */
  public StoredSession(String sessionId, long lastSeq, List<Message> history) {
    this(sessionId, lastSeq, history, Optional.empty());
  }

  /**
   * Fold a log into the session it stores, the one load rule every store shares: the history of the
   * last {@link SessionEntry.HistoryReplaced} in {@code tail}, or none, followed by the message of
   * every {@link SessionEntry.MessageAdded} after it; {@link #lastRunEnded()} is {@code tail}'s
   * last entry when it is a {@link SessionEntry.RunEnded}, which adds no message.
   *
   * @param sessionId the session id; non-blank
   * @param lastSeq the sequence number of the log's last entry; non-negative
   * @param tail the log in order, starting at its last {@code HistoryReplaced} or earlier
   * @return the stored session
   * @throws NullPointerException if {@code tail} is null or holds a null
   */
  public static StoredSession fold(String sessionId, long lastSeq, List<SessionEntry> tail) {
    var history = new ArrayList<Message>();
    Optional<SessionEntry.RunEnded> lastRunEnded = Optional.empty();
    for (var entry : Objects.requireNonNull(tail, "tail must not be null")) {
      lastRunEnded = Optional.empty();
      switch (Objects.requireNonNull(entry, "tail must not contain null")) {
        case SessionEntry.MessageAdded added -> history.add(added.message());
        case SessionEntry.HistoryReplaced replaced -> {
          history.clear();
          history.addAll(replaced.history());
        }
        case SessionEntry.RunEnded ended -> lastRunEnded = Optional.of(ended);
      }
    }
    return new StoredSession(sessionId, lastSeq, history, lastRunEnded);
  }
}
