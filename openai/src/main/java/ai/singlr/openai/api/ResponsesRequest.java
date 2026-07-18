/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request body for the OpenAI Responses API ({@code POST /v1/responses}).
 *
 * @param model the model identifier
 * @param input array of input items (messages, function_call round-trips, function_call_output)
 * @param instructions system instructions (top-level, not in input array)
 * @param stream whether to stream the response
 * @param tools available tool definitions
 * @param toolChoice controls how the model uses tools
 * @param temperature controls randomness (0.0 - 2.0)
 * @param topP nucleus sampling threshold
 * @param maxOutputTokens maximum tokens to generate
 * @param stop stop sequences
 * @param text text format configuration for structured output
 * @param reasoning reasoning configuration for thinking models
 * @param promptCacheKey stable cache-routing key; groups requests sharing a long common prefix so
 *     they land on machines holding the cached prefix (required for improved cache matching on
 *     gpt-5.6+)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponsesRequest(
    String model,
    List<InputItem> input,
    String instructions,
    Boolean stream,
    List<ToolDefinition> tools,
    @JsonProperty("tool_choice") Object toolChoice,
    Double temperature,
    @JsonProperty("top_p") Double topP,
    @JsonProperty("max_output_tokens") Integer maxOutputTokens,
    List<String> stop,
    TextConfig text,
    ReasoningConfig reasoning,
    @JsonProperty("prompt_cache_key") String promptCacheKey) {

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Text configuration wrapper.
   *
   * @param format the text format configuration
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TextConfig(TextFormatConfig format) {}

  /**
   * Reasoning configuration for thinking models. Both fields serialize as JSON strings — OpenAI's
   * Responses API rejects the request with HTTP 400 if {@code summary} is sent as an object.
   *
   * @param effort reasoning effort level: "low", "medium", "high"
   * @param summary reasoning summary verbosity: "auto", "concise", or "detailed"
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReasoningConfig(String effort, String summary) {

    public static ReasoningConfig of(String effort) {
      return new ReasoningConfig(effort, "auto");
    }
  }

  public static class Builder {
    private String model;
    private List<InputItem> input;
    private String instructions;
    private Boolean stream;
    private List<ToolDefinition> tools;
    private Object toolChoice;
    private Double temperature;
    private Double topP;
    private Integer maxOutputTokens;
    private List<String> stop;
    private TextConfig text;
    private ReasoningConfig reasoning;
    private String promptCacheKey;

    private Builder() {}

    public Builder withModel(String model) {
      this.model = model;
      return this;
    }

    public Builder withInput(List<InputItem> input) {
      this.input = input;
      return this;
    }

    public Builder withInstructions(String instructions) {
      this.instructions = instructions;
      return this;
    }

    public Builder withStream(Boolean stream) {
      this.stream = stream;
      return this;
    }

    public Builder withTools(List<ToolDefinition> tools) {
      this.tools = tools;
      return this;
    }

    public Builder withToolChoice(Object toolChoice) {
      this.toolChoice = toolChoice;
      return this;
    }

    public Builder withTemperature(Double temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder withTopP(Double topP) {
      this.topP = topP;
      return this;
    }

    public Builder withMaxOutputTokens(Integer maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    public Builder withStop(List<String> stop) {
      this.stop = stop;
      return this;
    }

    public Builder withText(TextConfig text) {
      this.text = text;
      return this;
    }

    public Builder withReasoning(ReasoningConfig reasoning) {
      this.reasoning = reasoning;
      return this;
    }

    public Builder withPromptCacheKey(String promptCacheKey) {
      this.promptCacheKey = promptCacheKey;
      return this;
    }

    public ResponsesRequest build() {
      return new ResponsesRequest(
          model,
          input,
          instructions,
          stream,
          tools,
          toolChoice,
          temperature,
          topP,
          maxOutputTokens,
          stop,
          text,
          reasoning,
          promptCacheKey);
    }
  }
}
