/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.context;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Char-based {@link TokenCounter}: roughly {@code ceil(chars/4)} per message plus a small
 * per-message overhead. Conservative (overestimates) for typical English text so the watermark
 * fires slightly earlier than the model's true context fill.
 *
 * <p>Counts characters across:
 *
 * <ul>
 *   <li>{@code Message.content} (the text body of user, assistant, or tool-result messages)
 *   <li>each {@code ToolCall.id} + {@code name} + the key/value pairs of its argument map
 *   <li>{@code Message.toolCallId} + {@code toolName} on tool-result rows
 * </ul>
 *
 * <p>{@code inlineFiles} are intentionally not counted by the default char-based estimator —
 * provider-specific tokenizers handle multimodal cost much more accurately than a char ratio could.
 * For multimodal-heavy workloads, wire a provider-specific {@link TokenCounter}.
 *
 * <p>Each message adds a fixed {@value #PER_MESSAGE_OVERHEAD_TOKENS}-token overhead to account for
 * the role markers, separators, and wire-format framing that providers add around each turn.
 */
final class CharBasedTokenCounter implements TokenCounter {

  static final CharBasedTokenCounter INSTANCE = new CharBasedTokenCounter();

  private static final int CHARS_PER_TOKEN = 4;
  private static final int PER_MESSAGE_OVERHEAD_TOKENS = 4;

  private CharBasedTokenCounter() {}

  @Override
  public long count(List<Message> messages) {
    Objects.requireNonNull(messages, "messages must not be null");
    var total = 0L;
    for (var m : messages) {
      total = Math.addExact(total, perMessage(m));
    }
    return total;
  }

  private static long perMessage(Message m) {
    var chars = 0L;
    if (m.content() != null) {
      chars += m.content().length();
    }
    if (m.toolCallId() != null) {
      chars += m.toolCallId().length();
    }
    if (m.toolName() != null) {
      chars += m.toolName().length();
    }
    if (m.toolCalls() != null) {
      for (var c : m.toolCalls()) {
        chars += toolCallChars(c);
      }
    }
    return ((chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN) + PER_MESSAGE_OVERHEAD_TOKENS;
  }

  private static long toolCallChars(ToolCall c) {
    var chars = 0L;
    if (c.id() != null) {
      chars += c.id().length();
    }
    if (c.name() != null) {
      chars += c.name().length();
    }
    var args = c.arguments();
    if (args != null) {
      for (Map.Entry<String, Object> e : args.entrySet()) {
        chars += e.getKey().length();
        var value = e.getValue();
        if (value != null) {
          chars += String.valueOf(value).length();
        }
      }
    }
    return chars;
  }
}
