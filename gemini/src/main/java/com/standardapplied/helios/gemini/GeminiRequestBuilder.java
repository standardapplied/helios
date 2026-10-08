/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.provider.RequestFactory;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.gemini.api.InteractionGenerationConfig;
import com.standardapplied.helios.gemini.api.InteractionRequest;
import com.standardapplied.helios.gemini.api.ResponseFormat;
import com.standardapplied.helios.gemini.api.ToolChoiceConfig;
import com.standardapplied.helios.gemini.api.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the Interactions API request for one turn of one model: the conversation, or only what
 * follows its continuation point when continuing server-side; the tools, with Google Search and URL
 * context when enabled; and the generation settings. It is the one place that reads a model's
 * reasoning support: what the configuration may ask for is checked when it is built, and an
 * accepted {@link Reasoning} is looked up into its wire fields exactly, never clamped or
 * substituted.
 */
final class GeminiRequestBuilder implements RequestFactory<InteractionRequest> {

  private static final Map<Display, String> THINKING_SUMMARIES =
      Map.of(Display.HIDDEN, "none", Display.SUMMARY, "auto");

  private final GeminiModelId modelId;
  private final ModelConfig config;
  private final Reasoning.Effort effort;

  /**
   * A builder for {@code modelId}, checking the configuration's reasoning against what the model
   * accepts.
   *
   * @throws IllegalArgumentException if the configuration sets a reasoning the model does not
   *     accept
   */
  GeminiRequestBuilder(GeminiModelId modelId, ModelConfig config) {
    modelId.reasoning().require(modelId.id(), config.reasoning());
    this.modelId = modelId;
    this.config = config;
    this.effort = config.reasoning().orElse(null) instanceof Reasoning.Effort e ? e : null;
  }

  /**
   * The request for {@code messages}, offering {@code tools} and answering as JSON matching {@code
   * outputSchema}, or in free text when it is {@code null}.
   */
  @Override
  public InteractionRequest build(
      List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema) {
    var request =
        InteractionRequest.newBuilder()
            .withModel(modelId.id())
            .withTools(toolDefinitions(tools))
            .withGenerationConfig(generationConfig())
            .withResponseFormat(outputSchema == null ? null : ResponseFormat.json(outputSchema))
            .withStream(true);
    var continuation = config.providerContinuation() ? ContinuationPoint.find(messages) : null;
    if (continuation != null) {
      return request
          .withSystemInstruction(GeminiConversation.extractSystemInstruction(messages))
          .withInput(GeminiConversation.continuationSteps(messages, continuation.startIndex()))
          .withPreviousInteractionId(continuation.interactionId())
          .build();
    }
    var conversation = GeminiConversation.of(messages);
    return request
        .withInput(conversation.steps())
        .withSystemInstruction(conversation.systemInstruction())
        .build();
  }

  private List<ToolDefinition> toolDefinitions(List<Tool> tools) {
    var hasTools = tools != null && !tools.isEmpty();
    var definitions = new ArrayList<ToolDefinition>();
    if (hasTools) {
      tools.forEach(
          tool ->
              definitions.add(
                  ToolDefinition.function(
                      tool.name(), tool.description(), tool.parametersAsJsonSchema())));
    }
    if (config.webFetch() && hasTools) {
      throw new IllegalStateException("URL context cannot be combined with function calling");
    }
    if (config.webSearch()) {
      definitions.add(ToolDefinition.googleSearch());
    }
    if (config.webFetch()) {
      definitions.add(ToolDefinition.urlContext());
    }
    return definitions.isEmpty() ? null : definitions;
  }

  /**
   * The generation settings. The output-token ceiling is always sent, the model's own when the
   * configuration sets none: the API's default can truncate a long structured answer without saying
   * why.
   */
  private InteractionGenerationConfig generationConfig() {
    return InteractionGenerationConfig.newBuilder()
        .withMaxOutputTokens(
            config.maxOutputTokens() != null ? config.maxOutputTokens() : modelId.maxOutputTokens())
        .withStopSequences(config.stopSequences())
        .withSeed(config.seed())
        .withThinkingLevel(effort == null ? null : effort.level().name().toLowerCase(Locale.ROOT))
        .withThinkingSummaries(effort == null ? null : THINKING_SUMMARIES.get(effort.display()))
        .withToolChoice(toolChoice(config.toolChoice()))
        .build();
  }

  private static ToolChoiceConfig toolChoice(ToolChoice toolChoice) {
    return switch (toolChoice) {
      case null -> null;
      case ToolChoice.Auto auto -> ToolChoiceConfig.auto();
      case ToolChoice.Any any -> ToolChoiceConfig.any();
      case ToolChoice.None none -> ToolChoiceConfig.none();
      case ToolChoice.Required required -> ToolChoiceConfig.validated(required.allowedTools());
    };
  }
}
