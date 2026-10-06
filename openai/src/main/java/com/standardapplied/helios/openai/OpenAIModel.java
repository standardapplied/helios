/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.ChatExchange;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.openai.api.OpenAIJson;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import java.net.http.HttpClient;
import java.util.List;

/**
 * OpenAI model implementation using the Responses API.
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link OpenAIException}.
 */
public class OpenAIModel implements Model {

  private static final String PROVIDER_NAME = "openai";
  static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/responses";

  static final String REASONING_KEY = "openai.reasoning";

  private final String wireModelId;
  private final OpenAIModelId knownModel;
  private final ModelConfig config;
  private final HttpClient httpClient;
  final OpenAIRequestBuilder requests;
  final OpenAIStreams streams;
  final ChatExchange<ResponsesRequest> exchange;

  OpenAIModel(OpenAIModelId modelId, ModelConfig config) {
    this(modelId != null ? modelId.id() : null, modelId, config);
  }

  OpenAIModel(String wireModelId, ModelConfig config) {
    this(wireModelId, OpenAIModelId.fromId(wireModelId), config);
  }

  private OpenAIModel(String wireModelId, OpenAIModelId knownModel, ModelConfig config) {
    if (Strings.isBlank(wireModelId)) {
      throw new IllegalArgumentException("modelId is required");
    }
    if (config == null) {
      throw new IllegalArgumentException("config is required");
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
    this.wireModelId = wireModelId;
    this.knownModel = knownModel;
    this.config = config;
    this.httpClient = HttpClientFactory.create(config);
    this.requests = new OpenAIRequestBuilder(wireModelId, knownModel, config);
    this.streams = new OpenAIStreams(config, httpClient);
    this.exchange =
        new ChatExchange<>(
            PROVIDER_NAME,
            "OpenAI API",
            streams::open,
            OpenAIException::new,
            OpenAIJson.STRUCTURED,
            config.rawOutputCapturePolicy());
  }

  @Override
  public String id() {
    return wireModelId;
  }

  @Override
  public String provider() {
    return PROVIDER_NAME;
  }

  @Override
  public int contextWindow() {
    if (config.contextWindow() != null) {
      return config.contextWindow();
    }
    return knownModel != null ? knownModel.contextWindow() : 0;
  }

  @Override
  public int maxOutputTokens() {
    return requests.defaultMaxTokens();
  }

  @Override
  public RawOutputCapturePolicy rawOutputCapturePolicy() {
    return config.rawOutputCapturePolicy();
  }

  @Override
  public void close() {
    HttpClientFactory.shutdownGracefully(httpClient);
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    return exchange.chat(requests.build(messages, tools, null));
  }

  @Override
  public <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    var request = requests.build(messages, tools, outputSchema.schema().toMap());
    return exchange.structured(exchange.chat(request), outputSchema);
  }

  @Override
  public CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    return exchange.stream(requests.build(messages, tools, null));
  }
}
