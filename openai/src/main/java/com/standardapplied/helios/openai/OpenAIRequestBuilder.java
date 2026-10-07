/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.provider.RequestFactory;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import com.standardapplied.helios.openai.api.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Builds the Responses API request for one turn of one model: the conversation, tools and tool
 * choice, reasoning and sampling settings, and the output schema.
 */
final class OpenAIRequestBuilder implements RequestFactory<ResponsesRequest> {

  private final String wireModelId;
  private final OpenAIModelId.EffortSupport effortSupport;
  private final int defaultMaxTokens;
  private final ModelConfig config;

  /**
   * A builder for {@code wireModelId}; an unrecognised model ({@code knownModel} null) is standard.
   */
  OpenAIRequestBuilder(String wireModelId, OpenAIModelId knownModel, ModelConfig config) {
    this.wireModelId = wireModelId;
    this.effortSupport =
        knownModel != null ? knownModel.effortSupport() : OpenAIModelId.EffortSupport.STANDARD;
    this.defaultMaxTokens = knownModel != null ? knownModel.maxOutputTokens() : 0;
    this.config = config;
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
    var reasoning = OpenAIReasoning.of(effortSupport, config.thinkingLevel());
    var samplingAllowed = reasoning == null || "none".equals(reasoning.effort());
    var request =
        ResponsesRequest.newBuilder()
            .withModel(wireModelId)
            .withInput(input.items())
            .withInstructions(input.instructions())
            .withStream(true)
            .withTools(toolDefinitions)
            .withToolChoice(toolChoice)
            .withTemperature(samplingAllowed ? config.temperature() : null)
            .withTopP(samplingAllowed ? config.topP() : null)
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
