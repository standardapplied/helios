/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.provider.JsonPost;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.InteractionRequest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Map;

/** Sends an Interactions API request and opens its response as a stream of events. */
final class GeminiStreams {

  private final ModelConfig config;
  private final HttpClient httpClient;
  private final GeminiEndpoint endpoint;

  GeminiStreams(ModelConfig config, HttpClient httpClient, GeminiEndpoint endpoint) {
    this.config = config;
    this.httpClient = httpClient;
    this.endpoint = endpoint;
  }

  /** The stream answering {@code request}. */
  SseReader open(InteractionRequest request) throws IOException, InterruptedException {
    return SseReader.open(
        httpClient,
        httpRequest(serialize(request)),
        config.streamIdleTimeout(),
        new GeminiStreamParser(config.providerContinuation(), endpoint.apiVersion()),
        GeminiException::new);
  }

  /** The HTTP request carrying {@code jsonBody}, authenticated by the configured API key if any. */
  HttpRequest httpRequest(String jsonBody) {
    var headers =
        Strings.isBlank(config.apiKey())
            ? Map.<String, String>of()
            : Map.of("x-goog-api-key", config.apiKey());
    return JsonPost.to(endpoint.interactions(), config, headers, jsonBody);
  }

  private static String serialize(InteractionRequest request) {
    try {
      return GeminiJson.LENIENT.writeValueAsString(request);
    } catch (RuntimeException e) {
      throw new GeminiException("Failed to serialize request", e);
    }
  }
}
