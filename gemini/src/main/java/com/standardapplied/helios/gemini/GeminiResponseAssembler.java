/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.gemini.api.InteractionUsage;
import java.util.HashMap;
import java.util.Map;

/**
 * Assembles the response at the end of a streamed interaction: its output, finish reason and usage,
 * and the metadata a later turn needs — the API version, the thought signatures to replay before
 * its tool calls, and the interaction id to continue from.
 */
final class GeminiResponseAssembler {

  private static final Map<String, FinishReason> FINISH_REASON =
      Map.of(
          "failed", FinishReason.ERROR,
          "budget_exceeded", FinishReason.ERROR,
          "incomplete", FinishReason.LENGTH);

  private GeminiResponseAssembler() {}

  /**
   * The completion event of an interaction.
   *
   * @param interactionId the id to record for continuation, or {@code null} to record none
   */
  static StreamEvent.Done done(
      StepOutput.Result output,
      InteractionUsage usage,
      String status,
      String interactionId,
      String apiVersion) {
    var metadata = new HashMap<String, String>();
    metadata.put(GeminiModel.API_VERSION_KEY, apiVersion);
    if (!output.signatures().isEmpty()) {
      metadata.put(
          GeminiModel.THOUGHT_SIGNATURES_KEY,
          String.join(GeminiModel.SIGNATURE_DELIMITER, output.signatures()));
    }
    if (interactionId != null) {
      metadata.put(GeminiModel.INTERACTION_ID_KEY, interactionId);
    }
    var response =
        Response.newBuilder()
            .withContent(output.content())
            .withToolCalls(output.calls())
            .withFinishReason(finishReason(output, status))
            .withUsage(usage(usage))
            .withThinking(output.thinking())
            .withCitations(output.citations())
            .withMetadata(Map.copyOf(metadata))
            .build();
    return new StreamEvent.Done(response);
  }

  private static FinishReason finishReason(StepOutput.Result output, String status) {
    if (!output.calls().isEmpty()) {
      return FinishReason.TOOL_CALLS;
    }
    return status == null
        ? FinishReason.STOP
        : FINISH_REASON.getOrDefault(status, FinishReason.STOP);
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
