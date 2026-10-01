/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Output item from the OpenAI Responses API.
 *
 * <p>Represents assistant messages, function calls, or reasoning items in the response output.
 *
 * @param type the item type: "message", "function_call", "reasoning"
 * @param id unique identifier for this output item
 * @param role the role (for type "message", always "assistant")
 * @param content list of content parts (for type "message")
 * @param callId function call ID for correlation (for type "function_call")
 * @param name function name (for type "function_call")
 * @param arguments serialized JSON arguments (for type "function_call")
 * @param status item status: "completed", "incomplete", etc.
 * @param summary reasoning summary items (for type "reasoning")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutputItem(
    String type,
    String id,
    String role,
    List<ContentPart> content,
    @JsonProperty("call_id") String callId,
    String name,
    String arguments,
    String status,
    List<ReasoningSummary> summary) {

  public boolean hasTypeMessage() {
    return "message".equals(type);
  }

  public boolean hasTypeFunctionCall() {
    return "function_call".equals(type);
  }

  public boolean hasTypeReasoning() {
    return "reasoning".equals(type);
  }

  /**
   * Reasoning summary text block.
   *
   * @param type always "summary_text"
   * @param text the summary text
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReasoningSummary(String type, String text) {}
}
