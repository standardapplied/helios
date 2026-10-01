/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Generation configuration for the stable Interactions API.
 *
 * <p>{@code tool_choice} lives here per the spec, not at the top level of the request body.
 *
 * @param maxOutputTokens maximum number of tokens to generate
 * @param stopSequences sequences that stop generation
 * @param seed random seed for reproducibility
 * @param thinkingLevel thinking/reasoning level ({@code none}, {@code low}, {@code medium}, {@code
 *     high})
 * @param toolChoice tool-choice policy (bare string {@code auto}/{@code any}/{@code none}, or an
 *     allowed-tools restriction)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InteractionGenerationConfig(
    @JsonProperty("max_output_tokens") Integer maxOutputTokens,
    @JsonProperty("stop_sequences") List<String> stopSequences,
    Long seed,
    @JsonProperty("thinking_level") String thinkingLevel,
    @JsonProperty("tool_choice") ToolChoiceConfig toolChoice) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private Integer maxOutputTokens;
    private List<String> stopSequences;
    private Long seed;
    private String thinkingLevel;
    private ToolChoiceConfig toolChoice;

    private Builder() {}

    public Builder withMaxOutputTokens(Integer maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    public Builder withStopSequences(List<String> stopSequences) {
      this.stopSequences = stopSequences;
      return this;
    }

    public Builder withSeed(Long seed) {
      this.seed = seed;
      return this;
    }

    public Builder withThinkingLevel(String thinkingLevel) {
      this.thinkingLevel = thinkingLevel;
      return this;
    }

    public Builder withToolChoice(ToolChoiceConfig toolChoice) {
      this.toolChoice = toolChoice;
      return this;
    }

    public InteractionGenerationConfig build() {
      return new InteractionGenerationConfig(
          maxOutputTokens, stopSequences, seed, thinkingLevel, toolChoice);
    }
  }
}
