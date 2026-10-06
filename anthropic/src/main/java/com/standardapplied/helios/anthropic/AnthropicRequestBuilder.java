/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.AnthropicModelId.ThinkingShape;
import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.provider.RequestFactory;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;
import java.util.Map;

/**
 * Builds the Messages API request for one turn of one model: the conversation, the output-schema
 * instruction, tools and tool choice, thinking and sampling settings, and prompt-cache breakpoints.
 */
final class AnthropicRequestBuilder implements RequestFactory<MessagesRequest> {

  /**
   * Output-token ceiling assumed for a Claude model ID this build does not recognise (one not in
   * {@link AnthropicModelId}). Matches the current Opus ceiling — a sane non-zero default so an
   * unrecognised model's requests aren't rejected for {@code max_tokens=0}. Callers override via
   * {@link ModelConfig.Builder#withMaxOutputTokens(Integer)}.
   */
  static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_000;

  private final String wireModelId;
  private final ThinkingShape thinkingShape;
  private final int defaultMaxTokens;
  private final ModelConfig config;
  private final CachePolicy cachePolicy;

  /**
   * A builder for {@code wireModelId}. An unrecognised model ({@code knownModel} null) takes the
   * adaptive thinking shape, the one new releases adopt.
   *
   * @throws IllegalArgumentException if the configuration forces a tool call on a model that
   *     rejects forced tool use
   */
  AnthropicRequestBuilder(
      String wireModelId,
      AnthropicModelId knownModel,
      ModelConfig config,
      CachePolicy cachePolicy) {
    if (knownModel != null
        && !knownModel.acceptsForcedToolChoice()
        && AnthropicTools.forces(config.toolChoice())) {
      throw new IllegalArgumentException(
          "Model "
              + wireModelId
              + " rejects forced tool use (tool_choice any/required); use ToolChoice.auto() and"
              + " instruct the model in the prompt, or a structured OutputSchema.");
    }
    this.wireModelId = wireModelId;
    this.thinkingShape = knownModel != null ? knownModel.thinkingShape() : ThinkingShape.ADAPTIVE;
    this.defaultMaxTokens =
        knownModel != null ? knownModel.maxOutputTokens() : DEFAULT_MAX_OUTPUT_TOKENS;
    this.config = config;
    this.cachePolicy = cachePolicy;
  }

  /** The output-token ceiling a request carries when the configuration sets none. */
  int defaultMaxTokens() {
    return defaultMaxTokens;
  }

  /**
   * The request for {@code messages}, offering {@code tools} and, when {@code outputSchema} is not
   * null, instructing the model to answer with JSON matching it.
   */
  @Override
  public MessagesRequest build(
      List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema) {
    var conversation = AnthropicMessages.of(messages);
    var system = withSchemaInstruction(conversation.system(), outputSchema, tools);
    var toolDefinitions = AnthropicTools.of(tools, config);
    var toolChoice = AnthropicTools.choice(config.toolChoice());
    var thinking = AnthropicThinking.of(thinkingShape, config.thinkingLevel(), wireModelId);
    var request =
        MessagesRequest.newBuilder()
            .withModel(wireModelId)
            .withMaxTokens(thinking.maxTokens(requestedMaxTokens()))
            .withMessages(conversation.entries())
            .withStream(true)
            .withToolChoice(toolChoice)
            .withTemperature(
                thinkingShape.acceptsSamplingParameters() && !thinking.thinks()
                    ? config.temperature()
                    : null)
            .withTopP(thinkingShape.acceptsSamplingParameters() ? config.topP() : null)
            .withStopSequences(config.stopSequences())
            .withThinking(thinking.thinking())
            .withOutputConfig(thinking.outputConfig());
    PromptCache.apply(cachePolicy, request, system, toolDefinitions, conversation.entries());
    return request.build();
  }

  private int requestedMaxTokens() {
    return config.maxOutputTokens() != null ? config.maxOutputTokens() : defaultMaxTokens;
  }

  /**
   * {@code system} followed by the instruction to answer with JSON matching {@code outputSchema}.
   * With tools on offer the instruction allows tool calls first: an unconditional "respond with
   * JSON" outshouts the deployer's "use tools first" guidance and the model skips its tools.
   */
  private static String withSchemaInstruction(
      String system, Map<String, Object> outputSchema, List<Tool> tools) {
    if (outputSchema == null) {
      return system;
    }
    var schemaJson = serialize(outputSchema);
    var instruction =
        (tools == null || tools.isEmpty())
            ? "You must respond with valid JSON matching this schema:\n"
                + schemaJson
                + "\nDo not wrap the JSON in markdown code blocks. Output only the raw JSON."
            : "You may call the available tools to gather information."
                + " When you are ready to emit your final answer (not a tool call),"
                + " it must be valid JSON matching this schema:\n"
                + schemaJson
                + "\nDo not wrap the JSON in markdown code blocks. Output only the raw JSON.";
    return AnthropicMessages.appendSystemText(system, instruction);
  }

  private static String serialize(Map<String, Object> outputSchema) {
    try {
      return AnthropicJson.LENIENT.writeValueAsString(outputSchema);
    } catch (RuntimeException e) {
      throw new AnthropicException("Failed to serialize value", e);
    }
  }
}
