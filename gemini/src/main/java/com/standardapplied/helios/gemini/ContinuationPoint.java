/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import java.util.List;

/**
 * Where a conversation can continue server-side: the latest assistant turn that recorded its
 * interaction id. The server replays everything up to that turn from {@code
 * previous_interaction_id}, so only the messages after it are sent.
 *
 * @param interactionId the interaction to continue
 * @param startIndex the index of the first message to send
 */
record ContinuationPoint(String interactionId, int startIndex) {

  /** The continuation point of {@code messages}, or {@code null} when there is none. */
  static ContinuationPoint find(List<Message> messages) {
    for (var i = messages.size() - 1; i >= 0; i--) {
      var message = messages.get(i);
      var interactionId =
          message.role() == Role.ASSISTANT && message.metadata() != null
              ? message.metadata().get(GeminiModel.INTERACTION_ID_KEY)
              : null;
      if (interactionId != null && !interactionId.isEmpty()) {
        return new ContinuationPoint(interactionId, i + 1);
      }
    }
    return null;
  }
}
