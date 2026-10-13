/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.gemini.api.InteractionUsage;
import java.util.HashMap;
import java.util.Map;

/**
 * Assembles the response of a streamed interaction, at its end or as far as it got: its output,
 * finish reason and usage, and the metadata a later turn needs — the API version, the thought
 * signatures to replay before its tool calls, and the interaction id to continue from.
 */
final class GeminiResponseAssembler {

  static final String API_VERSION_KEY = "gemini.apiVersion";
  static final String THOUGHT_SIGNATURES_KEY = "gemini.thoughtSignatures";
  static final String SIGNATURE_DELIMITER = "\u001E";

  private GeminiResponseAssembler() {}

  /**
   * The response of an interaction, finishing with {@code finishReason}.
   *
   * @param interactionId the id to record for continuation, or {@code null} to record none
   */
  static Response<Void> response(
      StepOutput.Result output,
      InteractionUsage usage,
      String interactionId,
      String apiVersion,
      FinishReason finishReason) {
    var metadata = new HashMap<String, String>();
    metadata.put(API_VERSION_KEY, apiVersion);
    if (!output.signatures().isEmpty()) {
      metadata.put(THOUGHT_SIGNATURES_KEY, String.join(SIGNATURE_DELIMITER, output.signatures()));
    }
    if (interactionId != null) {
      metadata.put(ContinuationPoint.INTERACTION_ID_KEY, interactionId);
    }
    return Response.newBuilder()
        .withContent(output.content())
        .withToolCalls(output.calls())
        .withFinishReason(finishReason)
        .withUsage(usage(usage))
        .withThinking(output.thinking())
        .withCitations(output.citations())
        .withMetadata(Map.copyOf(metadata))
        .build();
  }

  /**
   * The finish reason of an interaction that completed with {@code status}, or {@code null} when
   * the status ends no turn successfully: {@code failed}, {@code cancelled}, none, or one this
   * client does not know. A {@code completed} or {@code requires_action} interaction that called
   * tools finishes with {@code TOOL_CALLS}; an {@code incomplete} one was truncated.
   */
  static FinishReason finishReason(String status, boolean calledTools) {
    return switch (status) {
      case "completed", "requires_action" ->
          calledTools ? FinishReason.TOOL_CALLS : FinishReason.STOP;
      case "incomplete" -> FinishReason.LENGTH;
      case null, default -> null;
    };
  }

  /**
   * The usage in Helios's disjoint shape. Gemini reports {@code total_input_tokens} as the total
   * and {@code total_cached_tokens} as a subset of it, so the uncached input is their difference,
   * never below zero: a subset exceeding the total would be a server accounting bug. Gemini does
   * not charge a premium for implicit cache writes, so none are reported.
   */
  private static Response.Usage usage(InteractionUsage usage) {
    if (usage == null) {
      return null;
    }
    var input = usage.inputTokens() != null ? usage.inputTokens() : 0;
    var output = usage.outputTokens() != null ? usage.outputTokens() : 0;
    var cached = usage.cachedTokensOrZero();
    return Response.Usage.of(Math.max(0, input - cached), output, 0, cached);
  }
}
