/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import com.standardapplied.helios.core.common.Validate;
import com.standardapplied.helios.core.model.Message;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One entry of a session's stored log, written by the run {@link #runId()} at {@link #at()}: a
 * message the model received, or a replacement of the model's whole view. A later kind is a new
 * subtype, which a reader that does not know it refuses.
 */
public sealed interface SessionEntry {

  /**
   * The run that wrote the entry.
   *
   * @return non-blank run id
   */
  String runId();

  /**
   * When the entry was written, on the session's clock.
   *
   * @return non-null instant
   */
  Instant at();

  /**
   * One message added to the conversation, exactly as the model received it.
   *
   * @param runId the writing run; non-blank
   * @param at when it was written; non-null
   * @param message the message; non-null
   */
  record MessageAdded(String runId, Instant at, Message message) implements SessionEntry {

    /**
     * @throws NullPointerException if a component is null
     * @throws IllegalArgumentException if {@code runId} is blank
     */
    public MessageAdded {
      Validate.notBlank("runId", runId);
      Objects.requireNonNull(at, "at must not be null");
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * The model's view replaced wholesale, by compaction or a {@code MutateHistory} hook: {@code
   * history} is the new view, without the system prompt.
   *
   * @param runId the writing run; non-blank
   * @param at when it was written; non-null
   * @param history the new view; copied, no null element
   */
  record HistoryReplaced(String runId, Instant at, List<Message> history) implements SessionEntry {

    /**
     * @throws NullPointerException if a component is null or {@code history} holds a null
     * @throws IllegalArgumentException if {@code runId} is blank
     */
    public HistoryReplaced {
      Validate.notBlank("runId", runId);
      Objects.requireNonNull(at, "at must not be null");
      history = List.copyOf(Objects.requireNonNull(history, "history must not be null"));
    }
  }
}
