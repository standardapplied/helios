/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Input item for the OpenAI Responses API.
 *
 * <p>Represents messages, function call round-trips, and function call outputs in the input array.
 *
 * @param type the item type: "message", "function_call", "function_call_output"
 * @param role the role: "user", "assistant", "system" (for type "message")
 * @param content text content or list of content parts (for type "message")
 * @param callId function call ID for correlation (for function_call and function_call_output)
 * @param name function name (for type "function_call")
 * @param arguments serialized JSON arguments (for type "function_call")
 * @param output function result (for type "function_call_output")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InputItem(
    String type,
    String role,
    Object content,
    @JsonProperty("call_id") String callId,
    String name,
    String arguments,
    String output) {

  public static InputItem userMessage(String text) {
    return new InputItem("message", "user", text, null, null, null, null);
  }

  /**
   * User message with structured content parts (text + inline image / file). Use this overload when
   * the user message carries attachments; the Responses API wants a content array, not a bare
   * string.
   *
   * @param parts the content parts; non-null, non-empty
   * @return a fresh user-message input item
   */
  public static InputItem userMessage(List<ContentPart> parts) {
    return new InputItem("message", "user", parts, null, null, null, null);
  }

  public static InputItem assistantMessage(String text) {
    return new InputItem(
        "message", "assistant", List.of(ContentPart.outputText(text)), null, null, null, null);
  }

  public static InputItem assistantMessage(List<ContentPart> parts) {
    return new InputItem("message", "assistant", parts, null, null, null, null);
  }

  public static InputItem functionCall(String callId, String name, String arguments) {
    return new InputItem("function_call", null, null, callId, name, arguments, null);
  }

  public static InputItem functionCallOutput(String callId, String output) {
    return new InputItem("function_call_output", null, null, callId, null, null, output);
  }

  public boolean hasTypeMessage() {
    return "message".equals(type);
  }

  public boolean hasTypeFunctionCall() {
    return "function_call".equals(type);
  }

  public boolean hasTypeFunctionCallOutput() {
    return "function_call_output".equals(type);
  }
}
