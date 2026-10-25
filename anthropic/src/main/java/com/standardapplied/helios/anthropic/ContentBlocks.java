/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.anthropic.api.ContentDelta;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.Map;

/**
 * The content blocks of a streamed assistant turn, by stream index. Routes each block's opening,
 * deltas and closing to the accumulator of its type.
 */
final class ContentBlocks {

  private final TextBlocks text = new TextBlocks();
  private final ThinkingBlocks thinking = new ThinkingBlocks();
  private final ToolUseBlocks toolUses = new ToolUseBlocks();
  private final VerbatimBlocks verbatim = new VerbatimBlocks();

  TextBlocks text() {
    return text;
  }

  ThinkingBlocks thinking() {
    return thinking;
  }

  ToolUseBlocks toolUses() {
    return toolUses;
  }

  VerbatimBlocks verbatim() {
    return verbatim;
  }

  /**
   * A {@code content_block_start} for {@code block} at {@code index}, from {@code payload}: the
   * event it yields, if any.
   */
  StreamEvent start(Integer index, ContentBlock block, String payload) {
    if (block == null || index == null) {
      return null;
    }
    if ("tool_use".equals(block.type())) {
      return toolUses.start(index, block.id(), block.name());
    }
    switch (block.type()) {
      case "thinking" -> thinking.start(index);
      case "text" -> text.start(index, block.text());
      case "server_tool_use" -> verbatim.startServerToolUse(index, block);
      case null, default -> {
        if (VerbatimBlocks.arrivesWhole(block.type())) {
          verbatim.capture(index, payload);
        }
      }
    }
    return null;
  }

  /** A {@code content_block_delta} for the block at {@code index}: the event it yields, if any. */
  StreamEvent delta(Integer index, ContentDelta delta) {
    return switch (delta.type()) {
      case "text_delta" -> delta.text() == null ? null : text.append(index, delta.text());
      case "input_json_delta" -> inputJson(index, delta.partialJson());
      case "citations_delta" -> citation(index, delta.citation());
      case "thinking_delta" ->
          delta.thinking() == null || index == null
              ? null
              : thinking.append(index, delta.thinking());
      case "signature_delta" -> signature(index, delta.signature());
      case null, default -> null;
    };
  }

  /**
   * A {@code content_block_stop} for the block at {@code index}: the tool call it completes, if
   * any; a thinking block's stop yields nothing, its text and signature being in the response.
   */
  StreamEvent stop(Integer index) {
    if (index == null || verbatim.stop(index) || thinking.indices().contains(index)) {
      return null;
    }
    return toolUses.stop(index);
  }

  private StreamEvent inputJson(Integer index, String json) {
    if (json == null || index == null || verbatim.appendInput(index, json)) {
      return null;
    }
    return toolUses.appendInput(index, json);
  }

  private StreamEvent citation(Integer index, Map<String, Object> citation) {
    if (citation != null && index != null) {
      text.cite(index, citation);
    }
    return null;
  }

  private StreamEvent signature(Integer index, String signature) {
    if (signature != null && index != null) {
      thinking.sign(index, signature);
    }
    return null;
  }
}
