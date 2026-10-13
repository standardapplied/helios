/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.openai.api.ApiUsage;
import java.util.HashMap;
import java.util.List;

/**
 * Assembles the response of a streamed turn, at its end or as far as it got: its text, tool calls,
 * finish reason, usage and reasoning summary, which also rides as {@link #REASONING_KEY} metadata,
 * with a refusal's text as {@link Response#REFUSAL_EXPLANATION_KEY} and the reason an incomplete
 * response stopped as {@link #INCOMPLETE_REASON_KEY}.
 */
final class OpenAIResponseAssembler {

  static final String REASONING_KEY = "openai.reasoning";

  /** Metadata key carrying {@code incomplete_details.reason} of an incomplete response. */
  static final String INCOMPLETE_REASON_KEY = "openai.incompleteReason";

  /**
   * What a turn streamed.
   *
   * @param content the output text
   * @param calls the completed function calls
   * @param thinking the reasoning summary, or {@code null}
   * @param refusal the refusal text, empty without one
   * @param incompleteReason why an incomplete response stopped, or {@code null}
   * @param usage the reported usage, or {@code null}
   */
  record Turn(
      String content,
      List<ToolCall> calls,
      String thinking,
      String refusal,
      String incompleteReason,
      ApiUsage usage) {}

  private OpenAIResponseAssembler() {}

  /** The response of {@code turn}, finishing with {@code finishReason}. */
  static Response<Void> response(Turn turn, FinishReason finishReason) {
    var metadata = new HashMap<String, String>();
    if (turn.thinking() != null) {
      metadata.put(REASONING_KEY, turn.thinking());
    }
    if (!turn.refusal().isEmpty()) {
      metadata.put(Response.REFUSAL_EXPLANATION_KEY, turn.refusal());
    }
    if (turn.incompleteReason() != null) {
      metadata.put(INCOMPLETE_REASON_KEY, turn.incompleteReason());
    }
    return Response.newBuilder()
        .withContent(turn.content())
        .withToolCalls(turn.calls())
        .withFinishReason(finishReason)
        .withUsage(usage(turn.usage()))
        .withThinking(turn.thinking())
        .withMetadata(metadata)
        .build();
  }

  /** The finish reason of a completed response: refused, calling tools or stopped. */
  static FinishReason completedReason(boolean refused, boolean calledTools) {
    if (refused) {
      return FinishReason.REFUSAL;
    }
    return calledTools ? FinishReason.TOOL_CALLS : FinishReason.STOP;
  }

  /**
   * The finish reason of an incomplete response: filtered for {@code content_filter}, truncated for
   * any other reason, known or not.
   */
  static FinishReason incompleteReason(String reason) {
    return "content_filter".equals(reason) ? FinishReason.CONTENT_FILTER : FinishReason.LENGTH;
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
