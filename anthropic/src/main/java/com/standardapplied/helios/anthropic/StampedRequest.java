/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.anthropic.api.MessagesRequest.MessageEntry;
import java.util.List;

/**
 * A Messages API request and the stamp of the prompt it was built under, which the assistant turn
 * it produces records as {@link ReasoningReplay#PROMPT_STAMP_KEY}.
 *
 * @param body the request sent
 * @param promptStamp the stamp of its system prompt and tools
 */
record StampedRequest(MessagesRequest body, String promptStamp) {

  /** The {@code pause_turn} continuation of this request, under the same stamp. */
  StampedRequest continuationWith(List<MessageEntry> messages) {
    return new StampedRequest(body.continuationWith(messages), promptStamp);
  }
}
