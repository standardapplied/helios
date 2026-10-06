/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The conversation history of one session: the messages sent to the model on every turn, appended
 * by the session and the loop and replaced wholesale only by the loop (compaction, or a {@code
 * PreModelTurnHook} returning {@code MutateHistory}).
 *
 * <h2>Thread-safety</h2>
 *
 * Backed by a {@link CopyOnWriteArrayList}: the loop thread writes, any thread may take a snapshot.
 */
public final class ConversationHistory {

  private final CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();

  ConversationHistory() {}

  /**
   * Append a message to the conversation history.
   *
   * @param message the message to append; non-null
   * @throws NullPointerException if {@code message} is null
   */
  public void append(Message message) {
    Objects.requireNonNull(message, "message must not be null");
    messages.add(message);
  }

  /**
   * Immutable snapshot of the conversation history at the moment of the call. The returned list
   * never reflects subsequent appends — a fresh call returns a fresh snapshot.
   *
   * @return a defensive immutable copy of the history
   */
  public List<Message> snapshot() {
    return List.copyOf(messages);
  }

  /**
   * Replace the entire history with the given list, defensively copied so subsequent caller
   * mutations don't leak in.
   *
   * @throws NullPointerException if {@code newHistory} or any element is null
   */
  void replace(List<Message> newHistory) {
    Objects.requireNonNull(newHistory, "newHistory must not be null");
    var copy = new ArrayList<Message>(newHistory.size());
    for (var m : newHistory) {
      copy.add(Objects.requireNonNull(m, "newHistory must not contain null"));
    }
    messages.clear();
    messages.addAll(copy);
  }
}
