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
 * Assembles the response of a streamed turn, at its end or as far as it got: its text, tool calls,
 * finish reason, usage, thinking and citations, and the metadata a later turn needs to echo it —
 * each signed thinking block, the raw stop reason and refusal details, and the verbatim content
 * when the turn needs it.
 */
final class AnthropicResponseAssembler {

  /** Metadata key carrying the provider's raw {@code stop_reason} string. */
  static final String STOP_REASON_KEY = "anthropic.stopReason";

  private AnthropicResponseAssembler() {}

  /**
   * The completion event of the turn in {@code blocks}, whose {@code stopReason} {@link
   * #finishReason} knows. A turn that called tools and stopped finishes with {@code TOOL_CALLS}.
   */
  static StreamEvent.Done done(
      ContentBlocks blocks,
      Response.Usage usage,
      String stopReason,
      ContentDelta.StopDetails stopDetails) {
    var finishReason = finishReason(stopReason);
    if (!blocks.toolUses().calls().isEmpty() && finishReason == FinishReason.STOP) {
      finishReason = FinishReason.TOOL_CALLS;
    }
    return new StreamEvent.Done(response(blocks, usage, stopReason, stopDetails, finishReason));
  }

  /** The response of the turn in {@code blocks} so far, finishing with {@code finishReason}. */
  static Response<Void> response(
      ContentBlocks blocks,
      Response.Usage usage,
      String stopReason,
      ContentDelta.StopDetails stopDetails,
      FinishReason finishReason) {
    var thinkingBlocks = blocks.thinking().signed();
    return Response.newBuilder()
        .withContent(blocks.text().content())
        .withToolCalls(blocks.toolUses().calls())
        .withFinishReason(finishReason)
        .withUsage(usage)
        .withThinking(blocks.thinking().text())
        .withCitations(blocks.text().citations())
        .withMetadata(metadata(blocks, thinkingBlocks, stopReason, stopDetails))
        .build();
  }

  /**
   * The finish reason of the API's {@code stop_reason}, or {@code null} for none or one this client
   * does not know, which ends no turn successfully. {@code pause_turn} stops a segment the turn
   * continues from.
   */
  static FinishReason finishReason(String stopReason) {
    return switch (stopReason) {
      case "end_turn", "stop_sequence", "pause_turn" -> FinishReason.STOP;
      case "tool_use" -> FinishReason.TOOL_CALLS;
      case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
      case "refusal" -> FinishReason.REFUSAL;
      case null, default -> null;
    };
  }

  private static Map<String, String> metadata(
      ContentBlocks blocks,
      List<ThinkingBlock> thinkingBlocks,
      String stopReason,
      ContentDelta.StopDetails stopDetails) {
    var metadata = new HashMap<String, String>();
    if (!thinkingBlocks.isEmpty()) {
      metadata.put(ThinkingBlock.THINKING_BLOCKS_KEY, ThinkingBlock.encodeAll(thinkingBlocks));
    }
    putIfPresent(metadata, STOP_REASON_KEY, stopReason);
    if (stopDetails != null) {
      putIfPresent(metadata, Response.REFUSAL_CATEGORY_KEY, stopDetails.category());
      putIfPresent(metadata, Response.REFUSAL_EXPLANATION_KEY, stopDetails.explanation());
    }
    if (RawContentEcho.needed(blocks, stopReason)) {
      metadata.put(RawContentEcho.RAW_CONTENT_KEY, RawContentEcho.assemble(blocks));
    }
    return Map.copyOf(metadata);
  }

  private static void putIfPresent(Map<String, String> metadata, String key, String value) {
    if (value != null) {
      metadata.put(key, value);
    }
  }
}
