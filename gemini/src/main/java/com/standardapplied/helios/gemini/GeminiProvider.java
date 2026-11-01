/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ModelProvider;
import com.standardapplied.helios.core.provider.StreamingModel;
import com.standardapplied.helios.gemini.api.GeminiJson;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ModelProvider implementation for Google's Gemini API.
 *
 * <p>Supports the {@link GeminiModelId} catalogue through the Interactions API, at the configured
 * API version. Every request streams over SSE, so a blocking {@code chat} drains the stream rather
 * than waiting on one long HTTP read, and a per-line idle timeout turns a stalled stream into a
 * {@link GeminiException}, which is not retried. A turn continuing an interaction the server no
 * longer holds is resent once with the whole conversation.
 */
public class GeminiProvider implements ModelProvider {

  static final String PROVIDER_NAME = "gemini";

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  /**
   * A model for {@code modelId}.
   *
   * @throws IllegalArgumentException if the model is unsupported, or {@code config} is missing, has
   *     neither an API key nor a base URL, sets a sampling parameter the Interactions API removed,
   *     or names an API version other than {@code v1} or {@code v1beta}
   */
  @Override
  public Model create(String modelId, ModelConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    var id = GeminiModelId.fromId(modelId);
    if (id == null) {
      throw new IllegalArgumentException(
          "Unsupported model: " + modelId + ". Supported models: " + supportedIds());
    }
    validate(config);
    var endpoint = GeminiEndpoint.of(config);
    var requests = new GeminiRequestBuilder(id, config);
    var httpClient = HttpClientFactory.create(config);
    var streams = new GeminiStreams(config, httpClient, endpoint);
    return StreamingModel.<InteractionTurn>newBuilder()
        .withId(id.id())
        .withProvider(PROVIDER_NAME)
        .withConfig(config)
        .withDefaultContextWindow(id.contextWindow())
        .withMaxOutputTokens(id.maxOutputTokens())
        .withHttpClient(httpClient)
        .withRequests(requests)
        .withExchange(new InteractionResend(streams::open))
        .withFailure(GeminiException::new)
        .withJson(GeminiJson.STRUCTURED)
        .build();
  }

  @Override
  public boolean supports(String modelId) {
    return GeminiModelId.isSupported(modelId);
  }

  private static String supportedIds() {
    return Stream.of(GeminiModelId.values())
        .map(GeminiModelId::id)
        .collect(Collectors.joining(", "));
  }

  private static void validate(ModelConfig config) {
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
  }
}
