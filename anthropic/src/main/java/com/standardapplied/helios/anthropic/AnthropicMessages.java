/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.anthropic.api.MessagesRequest.MessageEntry;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A conversation in the Messages API's shape: system messages joined into the system prompt, each
 * user and assistant turn one entry, and each run of tool results one user entry. An assistant turn
 * before the point {@link ReasoningReplay} names goes without its reasoning.
 */
final class AnthropicMessages {

  private AnthropicMessages() {}

  /** The system prompt of {@code messages}, their system messages joined, or {@code null}. */
  static String system(List<Message> messages) {
    String system = null;
    for (var message : messages) {
      if (message.role() == Role.SYSTEM) {
        system = appendSystemText(system, message.content());
      }
    }
    return system;
  }

  /**
   * The wire messages of {@code messages}, their system messages left out; an assistant turn
   * replays its reasoning only from index {@code reasoningFrom} on.
   */
  static List<MessageEntry> entries(List<Message> messages, int reasoningFrom) {
    var entries = new ArrayList<MessageEntry>();
    for (var i = 0; i < messages.size(); i++) {
      var message = messages.get(i);
      switch (message.role()) {
        case SYSTEM -> {}
        case USER -> entries.add(user(message));
        case ASSISTANT -> entries.add(assistant(message, i >= reasoningFrom));
        case TOOL -> {
          var results = toolResultsFrom(messages, i);
          entries.add(MessageEntry.user(results));
          i += results.size() - 1;
        }
      }
    }
    return List.copyOf(entries);
  }

  /** {@code additional} appended to {@code existing} as a new paragraph. */
  static String appendSystemText(String existing, String additional) {
    if (existing == null) {
      return additional;
    }
    return existing + "\n\n" + additional;
  }

  /**
   * An assistant turn on the wire. A turn recorded with {@link RawContentEcho#RAW_CONTENT_KEY}
   * replays that content verbatim; any other turn sends its thinking blocks, text and tool calls.
   * Without {@code withReasoning} its {@code thinking} and {@code redacted_thinking} blocks are
   * left out, and a turn left with no content is sent as {@link ReasoningReplay#OMITTED}.
   */
  static MessageEntry assistant(Message message, boolean withReasoning) {
    var metadata = message.metadata() == null ? Map.<String, String>of() : message.metadata();
    var rawContent = metadata.get(RawContentEcho.RAW_CONTENT_KEY);
    if (rawContent != null && !rawContent.isEmpty()) {
      var blocks = verbatim(rawContent);
      return withReasoning ? new MessageEntry("assistant", blocks) : withoutReasoning(blocks);
    }
    var recorded = ThinkingBlock.decodeAll(metadata);
    var thinkingBlocks = withReasoning ? recorded : List.<ThinkingBlock>of();
    if (!message.hasToolCalls() && thinkingBlocks.isEmpty()) {
      var text = message.content() != null ? message.content() : "";
      return MessageEntry.assistant(
          text.isEmpty() && !withReasoning ? ReasoningReplay.OMITTED : text);
    }
    return MessageEntry.assistant(blocks(message, thinkingBlocks));
  }

  private static MessageEntry withoutReasoning(List<Object> blocks) {
    var kept = ReasoningReplay.withoutReasoning(blocks);
    return kept.isEmpty()
        ? MessageEntry.assistant(ReasoningReplay.OMITTED)
        : new MessageEntry("assistant", kept);
  }

  private static List<ContentBlock> blocks(Message message, List<ThinkingBlock> thinkingBlocks) {
    var blocks = new ArrayList<ContentBlock>();
    for (var block : thinkingBlocks) {
      blocks.add(ContentBlock.thinking(block.text(), block.signature()));
    }
    if (message.content() != null && !message.content().isEmpty()) {
      blocks.add(ContentBlock.text(message.content()));
    }
    for (var call : message.toolCalls()) {
      blocks.add(ContentBlock.toolUse(call.id(), call.name(), call.arguments()));
    }
    return blocks;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> verbatim(String rawContent) {
    try {
      return (List<Object>) AnthropicJson.DEFAULT.readValue(rawContent, List.class);
    } catch (RuntimeException e) {
      throw new AnthropicException(
          "Corrupted raw content on assistant message; refusing to echo a truncated"
              + " turn (the API would reject or mis-read it)",
          e);
    }
  }

  private static List<ContentBlock> toolResultsFrom(List<Message> messages, int first) {
    var results = new ArrayList<ContentBlock>();
    for (var i = first; i < messages.size() && messages.get(i).role() == Role.TOOL; i++) {
      var result = messages.get(i);
      results.add(ContentBlock.toolResult(result.toolCallId(), result.content()));
    }
    return results;
  }

  private static MessageEntry user(Message message) {
    if (message.hasFileReferences()) {
      throw new IllegalArgumentException(
          "Anthropic does not support URI file references through this message API");
    }
    var text = message.content() != null ? message.content() : "";
    if (!message.hasInlineFiles()) {
      return MessageEntry.user(text);
    }
    var blocks = new ArrayList<ContentBlock>(message.inlineFiles().size() + 1);
    for (var file : message.inlineFiles()) {
      blocks.add(attachment(file.mimeType(), file.data()));
    }
    if (!text.isEmpty()) {
      blocks.add(ContentBlock.text(text));
    }
    return MessageEntry.user(blocks);
  }

  /**
   * A PDF as a document, an image as an image, and anything else as a text block naming its type:
   * the Messages API has no generic file block.
   */
  private static ContentBlock attachment(String mediaType, byte[] data) {
    if ("application/pdf".equals(mediaType)) {
      return ContentBlock.document(mediaType, Base64.getEncoder().encodeToString(data));
    }
    if (mediaType.startsWith("image/")) {
      return ContentBlock.image(mediaType, Base64.getEncoder().encodeToString(data));
    }
    return ContentBlock.text(
        "[attachment " + mediaType + "]\n" + new String(data, StandardCharsets.UTF_8));
  }
}
