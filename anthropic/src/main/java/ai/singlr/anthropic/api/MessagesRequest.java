/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request body for the Claude Messages API.
 *
 * <p>The {@code system} field accepts either a plain {@link String} (legacy shape, no prompt
 * caching) or a {@code List<SystemContent>} (cache-aware shape — required by the API when any
 * {@code SystemContent} block carries a {@code cache_control} annotation). {@link
 * AnthropicModel#buildRequest} selects the shape based on whether prompt caching is enabled for the
 * model; serialization is uniform because Jackson emits whichever runtime type the field holds.
 *
 * @param model the model identifier
 * @param maxTokens maximum tokens to generate (required by Claude)
 * @param messages conversation messages
 * @param system system prompt — {@link String} or {@code List<SystemContent>}, may be null
 * @param stream whether to stream the response
 * @param tools available tools for function calling
 * @param toolChoice controls how the model uses tools
 * @param temperature controls randomness
 * @param topP nucleus sampling threshold
 * @param stopSequences sequences that stop generation
 * @param thinking extended thinking configuration
 * @param outputConfig output-side controls (effort) — paired with {@code thinking.type=adaptive} on
 *     Opus 4.7+; omitted for legacy {@code thinking.type=enabled} models
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessagesRequest(
    String model,
    @JsonProperty("max_tokens") Integer maxTokens,
    List<MessageEntry> messages,
    Object system,
    Boolean stream,
    List<ToolDefinition> tools,
    @JsonProperty("tool_choice") ToolChoiceConfig toolChoice,
    Double temperature,
    @JsonProperty("top_p") Double topP,
    @JsonProperty("stop_sequences") List<String> stopSequences,
    ThinkingConfig thinking,
    @JsonProperty("output_config") OutputConfig outputConfig) {

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Diagnostic accessor that returns the system prompt as flat text regardless of which wire shape
   * ({@link String} or {@code List<SystemContent>}) the request carries. Concatenates {@code text}
   * fields of every {@link SystemContent} block in the array shape; returns {@code null} when no
   * system is set.
   *
   * <p>This is for tests, traces, and audit log surfaces. The Anthropic API only sees the typed
   * {@link #system()} field — never this projection.
   *
   * @return the flat system text, or {@code null} when no system content is set
   */
  public String systemAsText() {
    return switch (system) {
      case null -> null;
      case String s -> s;
      case List<?> list -> {
        var joined = new StringBuilder();
        for (var item : list) {
          if (item instanceof SystemContent block) {
            if (!joined.isEmpty()) {
              joined.append("\n\n");
            }
            joined.append(block.text());
          }
        }
        yield joined.length() == 0 ? null : joined.toString();
      }
      default ->
          throw new IllegalStateException(
              "unexpected system content type: " + system.getClass().getName());
    };
  }

  /**
   * A message entry in the conversation.
   *
   * @param role "user" or "assistant"
   * @param content text string or list of content blocks
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MessageEntry(String role, Object content) {

    public static MessageEntry user(String text) {
      return new MessageEntry("user", text);
    }

    public static MessageEntry user(List<ContentBlock> blocks) {
      return new MessageEntry("user", blocks);
    }

    public static MessageEntry assistant(String text) {
      return new MessageEntry("assistant", text);
    }

    public static MessageEntry assistant(List<ContentBlock> blocks) {
      return new MessageEntry("assistant", blocks);
    }
  }

  public static class Builder {
    private String model;
    private Integer maxTokens;
    private List<MessageEntry> messages;
    private Object system;
    private Boolean stream;
    private List<ToolDefinition> tools;
    private ToolChoiceConfig toolChoice;
    private Double temperature;
    private Double topP;
    private List<String> stopSequences;
    private ThinkingConfig thinking;
    private OutputConfig outputConfig;

    private Builder() {}

    public Builder withModel(String model) {
      this.model = model;
      return this;
    }

    public Builder withMaxTokens(Integer maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    public Builder withMessages(List<MessageEntry> messages) {
      this.messages = messages;
      return this;
    }

    public Builder withSystem(String system) {
      this.system = system;
      return this;
    }

    /**
     * Cache-aware system prompt: list of {@link SystemContent} blocks. The Anthropic API requires
     * this shape (not a plain string) whenever any block carries a {@code cache_control}
     * annotation. Pass {@code null} to clear.
     *
     * @param system the system content blocks; may be null
     * @return this builder
     */
    public Builder withSystem(List<SystemContent> system) {
      this.system = system;
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

    public Builder withToolChoice(ToolChoiceConfig toolChoice) {
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

    public Builder withStopSequences(List<String> stopSequences) {
      this.stopSequences = stopSequences;
      return this;
    }

    public Builder withThinking(ThinkingConfig thinking) {
      this.thinking = thinking;
      return this;
    }

    public Builder withOutputConfig(OutputConfig outputConfig) {
      this.outputConfig = outputConfig;
      return this;
    }

    public MessagesRequest build() {
      return new MessagesRequest(
          model,
          maxTokens,
          messages,
          system,
          stream,
          tools,
          toolChoice,
          temperature,
          topP,
          stopSequences,
          thinking,
          outputConfig);
    }
  }
}
