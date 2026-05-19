/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * A typed step in the Interactions API timeline ({@code Api-Revision: 2026-05-20}).
 *
 * <p>Steps are the unit that both flows through the request {@code input[]} and the response {@code
 * steps[]}. The {@link #type()} discriminator selects which fields are meaningful:
 *
 * <ul>
 *   <li>{@code user_input}, {@code model_output} — carry {@link #content()}.
 *   <li>{@code thought} — carries optional {@link #summary()} plus {@link #signature()}.
 *   <li>{@code function_call} — carries {@link #id()}, {@link #name()}, {@link #arguments()},
 *       optional {@link #signature()}.
 *   <li>{@code function_result} — carries {@link #callId()}, optional {@link #name()}, {@link
 *       #result()}, and optional {@link #errorFlag()}.
 *   <li>{@code google_search_call} — carries {@link #id()}, {@link #arguments()}, optional {@link
 *       #signature()}.
 *   <li>{@code google_search_result} — carries {@link #callId()}, {@link #result()}, optional
 *       {@link #signature()}.
 * </ul>
 *
 * @param type the step discriminator
 * @param content content items (for {@code user_input}, {@code model_output})
 * @param summary thought summary items (for {@code thought})
 * @param signature thought / tool-call signature for round-tripping
 * @param id call identifier (for {@code function_call}, {@code google_search_call})
 * @param name function name (for {@code function_call}, optional on {@code function_result})
 * @param arguments call arguments (for {@code function_call}, {@code google_search_call})
 * @param callId reference to the originating call (for {@code function_result}, {@code
 *     google_search_result})
 * @param result tool result payload (for {@code function_result}, {@code google_search_result});
 *     per spec one of object / string / list of {@code FunctionResultSubcontent}
 * @param errorFlag set to {@code true} on a {@code function_result} step when the tool invocation
 *     failed. Wire-encoded as {@code is_error}; the component is named {@code errorFlag} (not
 *     {@code isError}) so the record accessor does not collide with Jackson's {@code is*()}
 *     boolean-getter auto-detection, which would otherwise synthesise a parallel virtual property
 *     {@code error}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Step(
    String type,
    List<ContentItem> content,
    List<ContentItem> summary,
    String signature,
    String id,
    String name,
    @JsonDeserialize(using = ArgumentsDeserializer.class) Map<String, Object> arguments,
    @JsonProperty("call_id") String callId,
    Object result,
    @JsonProperty("is_error") Boolean errorFlag) {

  public static Step userInput(String text) {
    return userInput(List.of(ContentItem.text(text)));
  }

  public static Step userInput(List<ContentItem> content) {
    return new Step("user_input", content, null, null, null, null, null, null, null, null);
  }

  public static Step modelOutput(String text) {
    return modelOutput(List.of(ContentItem.text(text)));
  }

  public static Step modelOutput(List<ContentItem> content) {
    return new Step("model_output", content, null, null, null, null, null, null, null, null);
  }

  public static Step thought(String signature) {
    return new Step("thought", null, null, signature, null, null, null, null, null, null);
  }

  public static Step thought(String signature, List<ContentItem> summary) {
    return new Step("thought", null, summary, signature, null, null, null, null, null, null);
  }

  public static Step functionCall(String id, String name, Map<String, Object> arguments) {
    return new Step(
        "function_call",
        null,
        null,
        null,
        id,
        name,
        arguments == null ? Map.of() : arguments,
        null,
        null,
        null);
  }

  public static Step functionResult(String callId, String name, Object result) {
    return functionResult(callId, name, result, null);
  }

  public static Step functionResult(String callId, String name, Object result, Boolean errorFlag) {
    return new Step(
        "function_result", null, null, null, null, name, null, callId, result, errorFlag);
  }

  public boolean hasTypeUserInput() {
    return "user_input".equals(type);
  }

  public boolean hasTypeModelOutput() {
    return "model_output".equals(type);
  }

  public boolean hasTypeThought() {
    return "thought".equals(type);
  }

  public boolean hasTypeFunctionCall() {
    return "function_call".equals(type);
  }

  public boolean hasTypeFunctionResult() {
    return "function_result".equals(type);
  }

  public boolean hasTypeGoogleSearchCall() {
    return "google_search_call".equals(type);
  }

  public boolean hasTypeGoogleSearchResult() {
    return "google_search_result".equals(type);
  }

  public boolean hasContent() {
    return content != null && !content.isEmpty();
  }

  public boolean hasSummary() {
    return summary != null && !summary.isEmpty();
  }
}
