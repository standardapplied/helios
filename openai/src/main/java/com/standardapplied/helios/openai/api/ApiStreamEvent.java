/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSE event envelope from the OpenAI Responses API streaming.
 *
 * <p>The {@code type} field discriminates between event kinds. Only {@code data:} lines carry
 * payload (simpler than Anthropic's paired {@code event:}/{@code data:} format).
 *
 * @param type event type discriminator
 * @param outputIndex index of the output item
 * @param contentIndex index of the content part within the output item
 * @param itemId the output item ID
 * @param item the output item (for output_item.added/done)
 * @param part the content part (for content_part.added/done)
 * @param delta text delta string
 * @param callId function call ID (for function_call_arguments events)
 * @param name function name (for function_call_arguments events)
 * @param arguments function call arguments (for function_call_arguments.done)
 * @param response the full response object (for response.completed/failed)
 * @param text reasoning summary text delta
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiStreamEvent(
    String type,
    @JsonProperty("output_index") Integer outputIndex,
    @JsonProperty("content_index") Integer contentIndex,
    @JsonProperty("item_id") String itemId,
    OutputItem item,
    ContentPart part,
    String delta,
    @JsonProperty("call_id") String callId,
    String name,
    String arguments,
    ResponsesResponse response,
    String text) {

  public boolean hasTypeResponseOutputTextDelta() {
    return "response.output_text.delta".equals(type);
  }

  public boolean hasTypeResponseOutputItemAdded() {
    return "response.output_item.added".equals(type);
  }

  public boolean hasTypeResponseOutputItemDone() {
    return "response.output_item.done".equals(type);
  }

  public boolean hasTypeFunctionCallArgumentsDelta() {
    return "response.function_call_arguments.delta".equals(type);
  }

  public boolean hasTypeFunctionCallArgumentsDone() {
    return "response.function_call_arguments.done".equals(type);
  }

  public boolean hasTypeResponseCompleted() {
    return "response.completed".equals(type);
  }

  public boolean hasTypeResponseFailed() {
    return "response.failed".equals(type);
  }

  public boolean hasTypeError() {
    return "error".equals(type);
  }

  public boolean hasTypeReasoningSummaryTextDelta() {
    return "response.reasoning_summary_text.delta".equals(type);
  }

  /**
   * Terminal event for a reasoning_summary block. OpenAI Responses API emits this after the last
   * {@code reasoning_summary_text.delta} chunk so consumers can flush a {@link
   * ai.singlr.core.model.StreamEvent.ThinkingComplete}.
   */
  public boolean hasTypeReasoningSummaryTextDone() {
    return "response.reasoning_summary_text.done".equals(type);
  }

  public boolean hasTypeContentPartAdded() {
    return "response.content_part.added".equals(type);
  }

  public boolean hasTypeContentPartDone() {
    return "response.content_part.done".equals(type);
  }
}
