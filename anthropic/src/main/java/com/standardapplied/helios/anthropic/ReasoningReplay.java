/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.ToolDefinition;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.provider.PromptStamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which assistant turns go back with their reasoning to a model that binds each thinking block to
 * its prefix. Every assistant turn records the {@link #PROMPT_STAMP_KEY} of the request that
 * produced it. A request keeps reasoning only on the turns after the last one produced under
 * another stamp, so it removes the oldest blocks and never leaves a gap, as "Preserved thinking"
 * requires; and a block removed once stays removed, since a turn with another stamp never leaves
 * the history, it only gets older.
 */
final class ReasoningReplay {

  /** Metadata key carrying the stamp of the request that produced an assistant turn. */
  static final String PROMPT_STAMP_KEY = "anthropic.promptStamp";

  /** The text of a turn whose only content was reasoning, sent so the turn order holds. */
  static final String OMITTED = "[earlier output omitted]";

  private static final Set<String> REASONING_BLOCKS = Set.of("thinking", "redacted_thinking");

  private ReasoningReplay() {}

  /** The stamp of a request sending {@code system} and {@code tools}, cache breakpoints aside. */
  static String stamp(String system, List<ToolDefinition> tools) {
    try {
      return PromptStamp.of(
          AnthropicJson.CANONICAL.writeValueAsBytes(Arrays.asList(system, tools)));
    } catch (RuntimeException e) {
      throw new AnthropicException("Failed to serialize request", e);
    }
  }

  /**
   * The index of the first message of {@code messages} whose reasoning a request under {@code
   * stamp} replays: the one after the last assistant turn stamped otherwise, or a missing stamp;
   * {@code 0} when the model does not bind.
   */
  static int keptFrom(List<Message> messages, String stamp, boolean binds) {
    if (!binds) {
      return 0;
    }
    for (var i = messages.size() - 1; i >= 0; i--) {
      var message = messages.get(i);
      if (message.role() == Role.ASSISTANT && !stamp.equals(stampOf(message))) {
        return i + 1;
      }
    }
    return 0;
  }

  /** {@code blocks} without their {@code thinking} and {@code redacted_thinking} blocks. */
  static List<Object> withoutReasoning(List<Object> blocks) {
    return blocks.stream()
        .filter(
            block ->
                !(block instanceof Map<?, ?> raw && REASONING_BLOCKS.contains(raw.get("type"))))
        .toList();
  }

  private static String stampOf(Message message) {
    return message.metadata() == null ? null : message.metadata().get(PROMPT_STAMP_KEY);
  }
}
