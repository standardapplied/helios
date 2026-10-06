/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

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
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.InteractionRequest;
import com.standardapplied.helios.gemini.api.ResponseFormat;
import java.net.http.HttpClient;
import java.util.List;

/**
 * Gemini model implementation using the configured Interactions API version.
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link GeminiException}.
 */
public class GeminiModel implements Model {

  private static final String PROVIDER_NAME = "gemini";
  static final String DEFAULT_API_ROOT = "https://generativelanguage.googleapis.com";
  static final String DEFAULT_API_VERSION = "v1";
  static final String THOUGHT_SIGNATURES_KEY = "gemini.thoughtSignatures";
  static final String INTERACTION_ID_KEY = "gemini.interactionId";
  static final String API_VERSION_KEY = "gemini.apiVersion";
  static final String SIGNATURE_DELIMITER = "\u001E";

  private final GeminiModelId modelId;
  private final ModelConfig config;
  private final GeminiEndpoint endpoint;
  private final HttpClient httpClient;
  final GeminiRequestBuilder requests;
  final GeminiStreams streams;
  final ChatExchange<InteractionRequest> exchange;

  GeminiModel(GeminiModelId modelId, ModelConfig config) {
    if (modelId == null) {
      throw new IllegalArgumentException("modelId is required");
    }
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    if (Strings.isBlank(config.baseUrl()) && Strings.isBlank(config.apiKey())) {
      throw new IllegalArgumentException(
          "config with valid apiKey is required (or set baseUrl + auth header)");
    }
    if (config.temperature() != null) {
      throw new IllegalArgumentException(
          "temperature is not supported by the Gemini Interactions API");
    }
    if (config.topP() != null) {
      throw new IllegalArgumentException("topP is not supported by the Gemini Interactions API");
    }
    this.modelId = modelId;
    this.config = config;
    this.endpoint = GeminiEndpoint.of(config);
    this.httpClient = HttpClientFactory.create(config);
    this.requests = new GeminiRequestBuilder(modelId, config);
    this.streams = new GeminiStreams(config, httpClient, endpoint);
    this.exchange =
        new ChatExchange<>(
            PROVIDER_NAME,
            "Gemini API",
            streams::open,
            GeminiException::new,
            GeminiJson.STRUCTURED,
            config.rawOutputCapturePolicy());
  }

  @Override
  public String id() {
    return modelId.id();
  }

  @Override
  public String provider() {
    return PROVIDER_NAME;
  }

  /** Effective non-sensitive Gemini Interactions API version for diagnostics. */
  public String apiVersion() {
    return endpoint.apiVersion();
  }

  @Override
  public int contextWindow() {
    return config.contextWindow() != null ? config.contextWindow() : modelId.contextWindow();
  }

  @Override
  public int maxOutputTokens() {
    return modelId.maxOutputTokens();
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
    var request =
        requests.build(messages, tools, ResponseFormat.json(outputSchema.schema().toMap()));
    return exchange.structured(exchange.chat(request), outputSchema);
  }

  @Override
  public CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    return exchange.stream(requests.build(messages, tools, null));
  }
}
