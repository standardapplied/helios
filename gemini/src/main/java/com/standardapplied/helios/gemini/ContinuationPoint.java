/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import java.util.List;

/**
 * Where a conversation can continue server-side: the latest assistant turn that recorded its
 * interaction id, when it was produced under the request's prompt stamp. The server replays
 * everything up to that turn from {@code previous_interaction_id}, so only the messages after it
 * are sent. An interaction keeps the system instruction and tools it was created with, so a turn
 * produced under another stamp is never continued.
 *
 * @param interactionId the interaction to continue
 * @param startIndex the index of the first message to send
 */
record ContinuationPoint(String interactionId, int startIndex) {

  static final String INTERACTION_ID_KEY = "gemini.interactionId";

  /** Metadata key carrying the stamp of the request that produced an assistant turn. */
  static final String PROMPT_STAMP_KEY = "gemini.promptStamp";

  /**
   * The continuation point of {@code messages} for a request under {@code stamp}, or {@code null}
   * when there is none.
   */
  static ContinuationPoint find(List<Message> messages, String stamp) {
    for (var i = messages.size() - 1; i >= 0; i--) {
      var message = messages.get(i);
      var metadata = message.role() == Role.ASSISTANT ? message.metadata() : null;
      var interactionId = metadata != null ? metadata.get(INTERACTION_ID_KEY) : null;
      if (interactionId != null && !interactionId.isEmpty()) {
        return stamp.equals(metadata.get(PROMPT_STAMP_KEY))
            ? new ContinuationPoint(interactionId, i + 1)
            : null;
      }
    }
    return null;
  }
}
