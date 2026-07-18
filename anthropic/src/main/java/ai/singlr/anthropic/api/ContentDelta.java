/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Delta content in Claude SSE streaming events.
 *
 * <p>Used for both content_block_delta (text_delta, input_json_delta, thinking_delta,
 * signature_delta, citations_delta) and message_delta (stop_reason, stop_sequence) events.
 *
 * @param type delta type: "text_delta", "input_json_delta", "thinking_delta", "signature_delta", or
 *     "citations_delta"
 * @param text incremental text (for type "text_delta")
 * @param partialJson partial JSON string (for type "input_json_delta")
 * @param thinking incremental thinking text (for type "thinking_delta")
 * @param signature incremental signature (for type "signature_delta")
 * @param citation one citation object attached to the current text block (for type
 *     "citations_delta"; e.g. a {@code web_search_result_location}). Kept generic — the shape
 *     varies by citation kind and must round-trip verbatim on later turns
 * @param stopReason stop reason from message_delta
 * @param stopSequence matched stop sequence from message_delta
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentDelta(
    String type,
    String text,
    @JsonProperty("partial_json") String partialJson,
    String thinking,
    String signature,
    Map<String, Object> citation,
    @JsonProperty("stop_reason") String stopReason,
    @JsonProperty("stop_sequence") String stopSequence) {

  public boolean hasTypeTextDelta() {
    return "text_delta".equals(type);
  }

  public boolean hasTypeInputJsonDelta() {
    return "input_json_delta".equals(type);
  }

  public boolean hasTypeThinkingDelta() {
    return "thinking_delta".equals(type);
  }

  public boolean hasTypeSignatureDelta() {
    return "signature_delta".equals(type);
  }

  public boolean hasTypeCitationsDelta() {
    return "citations_delta".equals(type);
  }
}
