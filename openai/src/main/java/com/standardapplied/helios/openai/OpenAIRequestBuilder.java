/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.provider.RequestFactory;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.openai.OpenAIModelId.Off;
import com.standardapplied.helios.openai.OpenAIModelId.ReasoningRules;
import com.standardapplied.helios.openai.OpenAIModelId.Sampling;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import com.standardapplied.helios.openai.api.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Builds the Responses API request for one turn of one model: the conversation, tools and tool
 * choice, reasoning and sampling settings, and the output schema. It is the one place that reads a
 * model's {@link ReasoningRules}: what the configuration may ask for is checked when it is built,
 * and an accepted {@link Reasoning} is looked up into its wire fields exactly, never clamped or
 * substituted.
 */
final class OpenAIRequestBuilder implements RequestFactory<ResponsesRequest> {

  private final String wireModelId;
  private final int defaultMaxTokens;
  private final ModelConfig config;
  private final ResponsesRequest.ReasoningConfig reasoning;

  /**
   * A builder for {@code wireModelId}, checking the configuration against what the model accepts.
   * An unrecognised model ({@code knownModel} null) takes {@link ReasoningRules#UNCATALOGUED}.
   *
   * @throws IllegalArgumentException if the configuration sets a reasoning or sampling parameter
   *     the model does not accept
   */
  OpenAIRequestBuilder(String wireModelId, OpenAIModelId knownModel, ModelConfig config) {
    var rules = knownModel != null ? knownModel.reasoning() : ReasoningRules.UNCATALOGUED;
    rules.support().require(wireModelId, config.reasoning());
    requireSampling(wireModelId, rules.sampling(), config);
    this.wireModelId = wireModelId;
    this.defaultMaxTokens = knownModel != null ? knownModel.maxOutputTokens() : 0;
    this.config = config;
    this.reasoning =
        switch (config.reasoning().orElse(null)) {
          case null -> null;
          case Reasoning.Off _ ->
              rules.off() == Off.NONE ? new ResponsesRequest.ReasoningConfig("none", null) : null;
          case Reasoning.Effort effort ->
              new ResponsesRequest.ReasoningConfig(
                  effort.level().name().toLowerCase(Locale.ROOT),
                  effort.display() == Display.SUMMARY ? "auto" : null);
        };
  }

  /** The output-token ceiling a request carries when the configuration sets none. */
  int defaultMaxTokens() {
    return defaultMaxTokens;
  }

  /**
   * The request for {@code messages}, offering {@code tools} and, when {@code outputSchema} is not
   * null, asking for JSON matching it.
   */
  @Override
  public ResponsesRequest build(
      List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema) {
    var input = OpenAIInput.of(messages);
    var toolDefinitions = definitions(tools);
    var toolChoice = choice(config.toolChoice());
    var request =
        ResponsesRequest.newBuilder()
            .withModel(wireModelId)
            .withInput(input.items())
            .withInstructions(input.instructions())
            .withStream(true)
            .withTools(toolDefinitions)
            .withToolChoice(toolChoice)
            .withTemperature(config.temperature())
            .withTopP(config.topP())
            .withMaxOutputTokens(
                config.maxOutputTokens() != null ? config.maxOutputTokens() : defaultMaxTokens)
            .withStop(config.stopSequences())
            .withReasoning(reasoning)
            .withPromptCacheKey(config.promptCacheKey());
    if (outputSchema != null) {
      request.withText(StrictSchema.textConfig(outputSchema));
    }
    return request.build();
  }

  /**
   * Rejects a {@code temperature} or {@code top_p} the model does not accept, so none is ever
   * silently dropped from a request.
   */
  private static void requireSampling(String model, Sampling sampling, ModelConfig config) {
    var reasoning = config.reasoning().orElse(null);
    var rejected =
        switch (sampling) {
          case ACCEPTED, UNCHECKED -> null;
          case REJECTED -> "whenever it is set";
          case WITH_OFF ->
              reasoning instanceof Reasoning.Off ? null : "unless Reasoning.Off is set";
          case WITHOUT_EFFORT ->
              reasoning instanceof Reasoning.Effort ? "with Reasoning.Effort" : null;
        };
    rejectIfSet(model, "temperature", config.temperature(), rejected);
    rejectIfSet(model, "topP", config.topP(), rejected);
  }

  private static void rejectIfSet(String model, String parameter, Double value, String rule) {
    if (value != null && rule != null) {
      throw new IllegalArgumentException(
          "Model " + model + " does not accept " + parameter + " " + rule + ".");
    }
  }

  private static List<ToolDefinition> definitions(List<Tool> tools) {
    if (tools == null || tools.isEmpty()) {
      return null;
    }
    return tools.stream()
        .map(
            tool ->
                ToolDefinition.function(
                    tool.name(), tool.description(), tool.parametersAsJsonSchema()))
        .toList();
  }

  /**
   * The wire tool choice: a mode string, the one function a required choice names, or the
   * allowed-tools choice requiring one of several functions, in order.
   */
  private static Object choice(ToolChoice toolChoice) {
    return switch (toolChoice) {
      case null -> null;
      case ToolChoice.Auto auto -> "auto";
      case ToolChoice.Any any -> "required";
      case ToolChoice.None none -> "none";
      case ToolChoice.Required required -> required(required.allowedTools());
    };
  }

  private static Map<String, Object> required(SequencedSet<String> names) {
    if (names.size() == 1) {
      return function(names.getFirst());
    }
    var choice = new LinkedHashMap<String, Object>();
    choice.put("type", "allowed_tools");
    choice.put("mode", "required");
    choice.put("tools", names.stream().map(OpenAIRequestBuilder::function).toList());
    return choice;
  }

  private static Map<String, Object> function(String name) {
    var function = new LinkedHashMap<String, Object>();
    function.put("type", "function");
    function.put("name", name);
    return function;
  }
}
