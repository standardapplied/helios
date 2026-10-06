/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ModelProvider;
import com.standardapplied.helios.core.provider.ChatExchange;
import com.standardapplied.helios.core.provider.StreamingModel;
import com.standardapplied.helios.openai.api.OpenAIJson;
import com.standardapplied.helios.openai.api.ResponsesRequest;

/**
 * ModelProvider implementation for OpenAI's Responses API.
 *
 * <p>Supports every {@link OpenAIModelId} out of the box. When {@link ModelConfig#baseUrl()} is set
 * — pointing at Azure OpenAI, an OpenAI-compatible proxy (LiteLLM, vLLM, Ollama), or Vertex AI —
 * any non-blank {@code modelId} is accepted. The string is used verbatim as the {@code model} field
 * in the request body, which Azure OpenAI maps to the deployment name. Context-window and
 * max-output-tokens metadata default to {@code 0} ("unknown") for unrecognised ids; callers can
 * override output tokens via {@link ModelConfig.Builder#withMaxOutputTokens(Integer)}.
 *
 * <p>Every request streams over SSE, including a blocking {@code chat}, which drains the stream; a
 * stream idle past {@link ModelConfig#streamIdleTimeout()} fails with a retryable {@link
 * OpenAIException}.
 */
public class OpenAIProvider implements ModelProvider {

  static final String PROVIDER_NAME = "openai";

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  @Override
  public Model create(String modelId, ModelConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    var known = OpenAIModelId.fromId(modelId);
    if (known != null) {
      return model(known.id(), known, config);
    }
    if (!Strings.isBlank(config.baseUrl())) {
      return model(modelId, null, config);
    }
    throw new IllegalArgumentException(
        "Unsupported model: "
            + modelId
            + ". Set ModelConfig.baseUrl for custom endpoints (Azure, proxy, Vertex).");
  }

  @Override
  public boolean supports(String modelId) {
    return OpenAIModelId.isSupported(modelId);
  }

  private static Model model(String wireModelId, OpenAIModelId knownModel, ModelConfig config) {
    validate(wireModelId, config);
    var httpClient = HttpClientFactory.create(config);
    var requests = new OpenAIRequestBuilder(wireModelId, knownModel, config);
    var streams = new OpenAIStreams(config, httpClient);
    return StreamingModel.<ResponsesRequest>newBuilder()
        .withId(wireModelId)
        .withProvider(PROVIDER_NAME)
        .withConfig(config)
        .withDefaultContextWindow(knownModel != null ? knownModel.contextWindow() : 0)
        .withMaxOutputTokens(requests.defaultMaxTokens())
        .withHttpClient(httpClient)
        .withRequests(requests)
        .withExchange(
            new ChatExchange<>(PROVIDER_NAME, "OpenAI API", streams::open, OpenAIException::new))
        .withJson(OpenAIJson.STRUCTURED)
        .build();
  }

  private static void validate(String wireModelId, ModelConfig config) {
    if (Strings.isBlank(wireModelId)) {
      throw new IllegalArgumentException("modelId is required");
    }
    if (Strings.isBlank(config.baseUrl()) && Strings.isBlank(config.apiKey())) {
      throw new IllegalArgumentException(
          "config with valid apiKey is required (or set baseUrl + auth header)");
    }
    if (config.webSearch()) {
      throw new IllegalArgumentException(
          "ModelConfig.webSearch is not supported by helios-openai; disable it or use a provider"
              + " with native web search (Anthropic, Gemini)");
    }
    if (config.webFetch()) {
      throw new IllegalArgumentException(
          "ModelConfig.webFetch is not supported by helios-openai; disable it or use a provider"
              + " with native web fetch (Anthropic, Gemini)");
    }
  }
}
