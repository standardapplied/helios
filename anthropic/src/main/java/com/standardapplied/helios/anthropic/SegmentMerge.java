/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges two segments of one assistant turn split by {@code pause_turn}. The later segment's
 * scalars — finish reason and raw stop reason — win; text, tool calls, citations, thinking, usage,
 * thinking blocks and verbatim content combine in order.
 */
final class SegmentMerge {

  private SegmentMerge() {}

  /** {@code first} followed by {@code second}, as one response. */
  static Response<Void> merge(Response<Void> first, Response<Void> second) {
    var toolCalls = new ArrayList<ToolCall>(first.toolCalls());
    toolCalls.addAll(second.toolCalls());
    var metadata = new HashMap<String, String>(second.metadata());
    var verbatim = new ArrayList<Object>(blocksOf(first));
    verbatim.addAll(blocksOf(second));
    if (!verbatim.isEmpty()) {
      metadata.put(RawContentEcho.RAW_CONTENT_KEY, serialize(verbatim));
    }
    var thinkingBlocks = new ArrayList<ThinkingBlock>(ThinkingBlock.decodeAll(first.metadata()));
    if (!thinkingBlocks.isEmpty()) {
      thinkingBlocks.addAll(ThinkingBlock.decodeAll(metadata));
      metadata.put(ThinkingBlock.THINKING_BLOCKS_KEY, ThinkingBlock.encodeAll(thinkingBlocks));
    }
    return Response.newBuilder()
        .withContent(orEmpty(first.content()) + orEmpty(second.content()))
        .withToolCalls(toolCalls)
        .withFinishReason(finishReason(second.finishReason(), toolCalls))
        .withUsage(usage(first.usage(), second.usage()))
        .withThinking(thinking(first.thinking(), second.thinking()))
        .withCitations(citations(first.citations(), second.citations()))
        .withMetadata(Map.copyOf(metadata))
        .build();
  }

  /**
   * Promotes only a {@code STOP} to {@code TOOL_CALLS}, for a stream whose final stop reason lags
   * behind a tool call it emitted; a refusal, truncation or error from the final segment survives
   * so the session loop routes it correctly.
   */
  private static FinishReason finishReason(FinishReason last, List<ToolCall> toolCalls) {
    return !toolCalls.isEmpty() && last == FinishReason.STOP ? FinishReason.TOOL_CALLS : last;
  }

  private static Response.Usage usage(Response.Usage first, Response.Usage second) {
    if (first == null) {
      return second;
    }
    return second == null ? first : first.plus(second);
  }

  private static String thinking(String first, String second) {
    if (first == null) {
      return second;
    }
    return second == null ? first : first + "\n\n" + second;
  }

  private static List<Citation> citations(List<Citation> first, List<Citation> second) {
    var citations = new ArrayList<Citation>();
    if (first != null) {
      citations.addAll(first);
    }
    if (second != null) {
      citations.addAll(second);
    }
    return citations;
  }

  private static String orEmpty(String text) {
    return text == null ? "" : text;
  }

  /**
   * The content-block array of {@code segment}: its verbatim content, or for a segment without any
   * — a text-only continuation, say — its signed thinking, text and tool calls rebuilt, so the
   * merged echo carries the entire turn rather than only the paused prefix.
   */
  @SuppressWarnings("unchecked")
  private static List<Object> blocksOf(Response<Void> segment) {
    var raw = segment.metadata().get(RawContentEcho.RAW_CONTENT_KEY);
    if (raw != null && !raw.isEmpty()) {
      try {
        return (List<Object>) AnthropicJson.LENIENT.readValue(raw, List.class);
      } catch (RuntimeException e) {
        throw new AnthropicException("Failed to decode segment content array", e);
      }
    }
    var blocks = new ArrayList<Object>();
    for (var thinking : ThinkingBlock.decodeAll(segment.metadata())) {
      blocks.add(block("thinking", "thinking", thinking.text(), "signature", thinking.signature()));
    }
    if (segment.content() != null && !segment.content().isEmpty()) {
      blocks.add(block("text", "text", segment.content()));
    }
    for (var call : segment.toolCalls()) {
      blocks.add(
          block("tool_use", "id", call.id(), "name", call.name(), "input", call.arguments()));
    }
    return blocks;
  }

  private static String serialize(List<Object> blocks) {
    try {
      return AnthropicJson.LENIENT.writeValueAsString(blocks);
    } catch (RuntimeException e) {
      throw new AnthropicException("Failed to merge paused-turn content arrays", e);
    }
  }

  private static Map<String, Object> block(String type, Object... fields) {
    var block = new LinkedHashMap<String, Object>();
    block.put("type", type);
    for (var i = 0; i < fields.length; i += 2) {
      block.put((String) fields[i], fields[i + 1]);
    }
    return block;
  }
}
