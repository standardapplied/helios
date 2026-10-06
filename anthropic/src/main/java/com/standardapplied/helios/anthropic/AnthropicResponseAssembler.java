/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.ContentDelta;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the response at the end of a streamed turn: its text, tool calls, finish reason, usage,
 * thinking and citations, and the metadata a later turn needs to echo it — each signed thinking
 * block, the raw stop reason and refusal details, and the verbatim content when the turn needs it.
 */
final class AnthropicResponseAssembler {

  private AnthropicResponseAssembler() {}

  /** The completion event of the turn in {@code blocks}. */
  static StreamEvent.Done done(
      ContentBlocks blocks,
      Response.Usage usage,
      String stopReason,
      ContentDelta.StopDetails stopDetails) {
    var calls = blocks.toolUses().calls();
    var finishReason = mapStopReason(stopReason);
    if (!calls.isEmpty() && finishReason == FinishReason.STOP) {
      finishReason = FinishReason.TOOL_CALLS;
    }
    var thinkingBlocks = blocks.thinking().signed();
    var response =
        Response.newBuilder()
            .withContent(blocks.text().content())
            .withToolCalls(calls)
            .withFinishReason(finishReason)
            .withUsage(usage)
            .withThinking(joined(thinkingBlocks))
            .withCitations(blocks.text().citations())
            .withMetadata(metadata(blocks, thinkingBlocks, stopReason, stopDetails))
            .build();
    return new StreamEvent.Done(response);
  }

  /** The finish reason of the API's {@code stop_reason}. */
  static FinishReason mapStopReason(String stopReason) {
    if (stopReason == null) {
      return FinishReason.STOP;
    }
    return switch (stopReason) {
      case "tool_use" -> FinishReason.TOOL_CALLS;
      case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
      case "refusal" -> FinishReason.REFUSAL;
      default -> FinishReason.STOP;
    };
  }

  private static String joined(List<ThinkingBlock> thinkingBlocks) {
    var text = new StringBuilder();
    for (var block : thinkingBlocks) {
      if (!text.isEmpty()) {
        text.append("\n\n");
      }
      text.append(block.text());
    }
    return text.isEmpty() ? null : text.toString();
  }

  private static Map<String, String> metadata(
      ContentBlocks blocks,
      List<ThinkingBlock> thinkingBlocks,
      String stopReason,
      ContentDelta.StopDetails stopDetails) {
    var metadata = new HashMap<String, String>();
    if (!thinkingBlocks.isEmpty()) {
      metadata.put(AnthropicModel.THINKING_BLOCKS_KEY, ThinkingBlock.encodeAll(thinkingBlocks));
    }
    putIfPresent(metadata, AnthropicModel.STOP_REASON_KEY, stopReason);
    if (stopDetails != null) {
      putIfPresent(metadata, Response.REFUSAL_CATEGORY_KEY, stopDetails.category());
      putIfPresent(metadata, Response.REFUSAL_EXPLANATION_KEY, stopDetails.explanation());
    }
    if (RawContentEcho.needed(blocks, stopReason)) {
      metadata.put(AnthropicModel.RAW_CONTENT_KEY, RawContentEcho.assemble(blocks));
    }
    return Map.copyOf(metadata);
  }

  private static void putIfPresent(Map<String, String> metadata, String key, String value) {
    if (value != null) {
      metadata.put(key, value);
    }
  }
}
