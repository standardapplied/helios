/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.openai.api.ApiUsage;
import java.util.List;
import java.util.Map;

/**
 * Assembles the response at the end of a streamed turn: its text, tool calls, finish reason, usage
 * and reasoning summary, which also rides as {@link #REASONING_KEY} metadata.
 */
final class OpenAIResponseAssembler {

  static final String REASONING_KEY = "openai.reasoning";

  private OpenAIResponseAssembler() {}

  /**
   * The completion event of a turn. A turn that called tools finishes with {@code TOOL_CALLS}
   * whatever the response status.
   */
  static StreamEvent.Done done(
      String content, List<ToolCall> calls, String reasoning, ApiUsage usage, String status) {
    var thinking = reasoning.isEmpty() ? null : reasoning;
    var response =
        Response.newBuilder()
            .withContent(content)
            .withToolCalls(calls)
            .withFinishReason(calls.isEmpty() ? mapStatus(status) : FinishReason.TOOL_CALLS)
            .withUsage(usage(usage))
            .withThinking(thinking)
            .withMetadata(thinking == null ? Map.of() : Map.of(REASONING_KEY, thinking))
            .build();
    return new StreamEvent.Done(response);
  }

  /** The finish reason of a response status. */
  static FinishReason mapStatus(String status) {
    if (status == null) {
      return FinishReason.STOP;
    }
    return switch (status) {
      case "incomplete" -> FinishReason.LENGTH;
      case "failed" -> FinishReason.ERROR;
      default -> FinishReason.STOP;
    };
  }

  /**
   * The usage in Helios's disjoint shape. OpenAI reports {@code input_tokens} as the total and the
   * cached and cache-write counts as subsets of it, so both are subtracted, never below zero: a
   * subset exceeding the total would be a server accounting bug, better under-reported than
   * negative. Cache writes bill at a premium on gpt-5.6 and later and are zero elsewhere.
   */
  private static Response.Usage usage(ApiUsage usage) {
    if (usage == null) {
      return null;
    }
    var input = usage.inputTokens() != null ? usage.inputTokens() : 0;
    var output = usage.outputTokens() != null ? usage.outputTokens() : 0;
    var cached = usage.cachedTokensOrZero();
    var written = usage.cacheWriteTokensOrZero();
    if (input <= 0 && output <= 0 && cached <= 0 && written <= 0) {
      return null;
    }
    return Response.Usage.of(Math.max(0, input - cached - written), output, written, cached);
  }
}
