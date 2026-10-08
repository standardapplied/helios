/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.AnthropicModelId.Off;
import com.standardapplied.helios.anthropic.AnthropicModelId.ReasoningRules;
import com.standardapplied.helios.anthropic.AnthropicModelId.Sampling;
import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.anthropic.api.OutputConfig;
import com.standardapplied.helios.anthropic.api.ThinkingConfig;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.provider.RequestFactory;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the Messages API request for one turn of one model: the conversation, the output-schema
 * instruction, tools and tool choice, reasoning and sampling settings, and prompt-cache
 * breakpoints. It is the one place that reads a model's {@link ReasoningRules}: what the
 * configuration may ask for is checked when it is built, and an accepted {@link Reasoning} is
 * looked up into its wire fields exactly, never clamped or substituted.
 */
final class AnthropicRequestBuilder implements RequestFactory<MessagesRequest> {

  /**
   * Output-token ceiling assumed for a Claude model ID this build does not recognise (one not in
   * {@link AnthropicModelId}). Matches the current Opus ceiling — a sane non-zero default so an
   * unrecognised model's requests aren't rejected for {@code max_tokens=0}. Callers override via
   * {@link ModelConfig.Builder#withMaxOutputTokens(Integer)}.
   */
  static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_000;

  /** The beta a request carrying {@code thinking.display=updates} needs. */
  static final String PROGRESS_DISPLAY_BETA = "thinking-display-updates-2026-08-18";

  private static final double LOWEST_TOP_P_WITH_EFFORT = 0.95;

  private static final Map<Display, String> DISPLAY =
      Map.of(
          Display.HIDDEN, "omitted",
          Display.SUMMARY, "summarized",
          Display.PROGRESS, "updates");

  private static final Map<Off, ThinkingConfig> OFF =
      Map.of(
          Off.DISABLED,
          ThinkingConfig.disabled(),
          Off.BETWEEN_TOOLS,
          ThinkingConfig.betweenTools());

  private final String wireModelId;
  private final int defaultMaxTokens;
  private final ModelConfig config;
  private final CachePolicy cachePolicy;
  private final ThinkingConfig thinking;
  private final OutputConfig outputConfig;
  private final List<String> betas;

  /**
   * A builder for {@code wireModelId}, checking the configuration against what the model accepts.
   * An unrecognised model ({@code knownModel} null) takes {@link ReasoningRules#UNCATALOGUED}.
   *
   * @throws IllegalArgumentException if the configuration forces a tool call on a model that
   *     rejects forced tool use, or sets a reasoning or sampling parameter the model does not
   *     accept
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
    var rules = knownModel != null ? knownModel.reasoning() : ReasoningRules.UNCATALOGUED;
    rules.support().require(wireModelId, config.reasoning());
    requireSampling(wireModelId, rules.sampling(), config);
    var effort = config.reasoning().orElse(null) instanceof Reasoning.Effort e ? e : null;
    this.wireModelId = wireModelId;
    this.defaultMaxTokens =
        knownModel != null ? knownModel.maxOutputTokens() : DEFAULT_MAX_OUTPUT_TOKENS;
    this.config = config;
    this.cachePolicy = cachePolicy;
    this.thinking =
        effort != null
            ? ThinkingConfig.adaptive(DISPLAY.get(effort.display()))
            : config.reasoning().map(_ -> OFF.get(rules.off())).orElse(null);
    this.outputConfig =
        effort != null ? new OutputConfig(effort.level().name().toLowerCase(Locale.ROOT)) : null;
    this.betas =
        effort != null && effort.display() == Display.PROGRESS
            ? List.of(PROGRESS_DISPLAY_BETA)
            : List.of();
  }

  /** The output-token ceiling a request carries when the configuration sets none. */
  int defaultMaxTokens() {
    return defaultMaxTokens;
  }

  /** The betas every request needs: the progress display's, when it is asked for. */
  List<String> betas() {
    return betas;
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
    var request =
        MessagesRequest.newBuilder()
            .withModel(wireModelId)
            .withMaxTokens(requestedMaxTokens())
            .withStream(true)
            .withToolChoice(toolChoice)
            .withTemperature(config.temperature())
            .withTopP(config.topP())
            .withStopSequences(config.stopSequences())
            .withThinking(thinking)
            .withOutputConfig(outputConfig);
    PromptCache.apply(cachePolicy, request, system, toolDefinitions, conversation.entries());
    return request.build();
  }

  /**
   * Rejects a {@code temperature} or {@code top_p} the model does not accept, so none is ever
   * silently dropped from a request.
   */
  private static void requireSampling(String model, Sampling sampling, ModelConfig config) {
    var temperature = config.temperature();
    var topP = config.topP();
    var effort = config.reasoning().orElse(null) instanceof Reasoning.Effort;
    switch (sampling) {
      case UNCHECKED -> {}
      case REJECTED -> {
        reject(model, "temperature", temperature != null, "is rejected whenever it is set");
        reject(model, "topP", topP != null, "is rejected whenever it is set");
      }
      case ONE_OF, ONE_OF_WITHOUT_EFFORT -> {
        reject(model, "temperature", temperature != null && topP != null, "and topP together");
        var limited = sampling == Sampling.ONE_OF_WITHOUT_EFFORT && effort;
        reject(model, "temperature", limited && temperature != null, "with Reasoning.Effort");
        reject(
            model,
            "topP",
            limited && topP != null && (topP < LOWEST_TOP_P_WITH_EFFORT || topP > 1),
            "outside 0.95-1 with Reasoning.Effort");
      }
    }
  }

  private static void reject(String model, String parameter, boolean rejected, String rule) {
    if (rejected) {
      throw new IllegalArgumentException(
          "Model " + model + " does not accept " + parameter + " " + rule + ".");
    }
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
