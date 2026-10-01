/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code POST /interactions} request body for the stable Interactions API.
 *
 * <p>The {@code input} field is a {@code StepList} per the spec: every element is a {@link Step}
 * with its own {@link Step#type()} discriminator (e.g. {@code user_input}, {@code model_output},
 * {@code thought}, {@code function_call}, {@code function_result}). The role-keyed turn-list shape
 * used by earlier Gemini APIs is no longer accepted by the server.
 *
 * <p>{@code tool_choice} lives inside {@link InteractionGenerationConfig}, not at the top level —
 * setting it at the root is silently ignored by the server.
 *
 * @param model the model identifier (e.g. {@code gemini-3-flash-preview})
 * @param input the step timeline being sent to the model
 * @param previousInteractionId interaction ID to continue from (enables stateful continuation)
 * @param systemInstruction system-level guidance for the model
 * @param tools available tools for function calling and server tools
 * @param generationConfig generation parameters, including {@code tool_choice}
 * @param responseFormat polymorphic response-format selector (text / JSON / image)
 * @param stream whether the interaction will be streamed via Server-Sent Events
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InteractionRequest(
    String model,
    List<Step> input,
    @JsonProperty("previous_interaction_id") String previousInteractionId,
    @JsonProperty("system_instruction") String systemInstruction,
    List<ToolDefinition> tools,
    @JsonProperty("generation_config") InteractionGenerationConfig generationConfig,
    @JsonProperty("response_format") ResponseFormat responseFormat,
    Boolean stream) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String model;
    private List<Step> input;
    private String previousInteractionId;
    private String systemInstruction;
    private List<ToolDefinition> tools;
    private InteractionGenerationConfig generationConfig;
    private ResponseFormat responseFormat;
    private Boolean stream;

    private Builder() {}

    public Builder withModel(String model) {
      this.model = model;
      return this;
    }

    public Builder withInput(List<Step> input) {
      this.input = input;
      return this;
    }

    public Builder withPreviousInteractionId(String previousInteractionId) {
      this.previousInteractionId = previousInteractionId;
      return this;
    }

    public Builder withSystemInstruction(String systemInstruction) {
      this.systemInstruction = systemInstruction;
      return this;
    }

    public Builder withTools(List<ToolDefinition> tools) {
      this.tools = tools;
      return this;
    }

    public Builder withGenerationConfig(InteractionGenerationConfig generationConfig) {
      this.generationConfig = generationConfig;
      return this;
    }

    public Builder withResponseFormat(ResponseFormat responseFormat) {
      this.responseFormat = responseFormat;
      return this;
    }

    public Builder withStream(Boolean stream) {
      this.stream = stream;
      return this;
    }

    public InteractionRequest build() {
      return new InteractionRequest(
          model,
          input,
          previousInteractionId,
          systemInstruction,
          tools,
          generationConfig,
          responseFormat,
          stream);
    }
  }
}
