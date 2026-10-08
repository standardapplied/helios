/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.provider.JsonPost;
import com.standardapplied.helios.core.provider.SseReader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Sends a Messages API request and opens its response as a stream of events. */
final class AnthropicStreams {

  static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";

  private static final String API_VERSION = "2023-06-01";

  private static final String BETA_HEADER = "anthropic-beta";

  private final ModelConfig config;
  private final HttpClient httpClient;

  /**
   * Streams sent with {@code betas} in the {@code anthropic-beta} header, after any betas the
   * configured headers already name.
   */
  AnthropicStreams(ModelConfig config, HttpClient httpClient, List<String> betas) {
    this.config = betas.isEmpty() ? config : withBetas(config, betas);
    this.httpClient = httpClient;
  }

  /** The stream answering {@code request}. */
  SseReader open(MessagesRequest request) throws IOException, InterruptedException {
    return SseReader.open(
        httpClient,
        httpRequest(serialize(request)),
        config.streamIdleTimeout(),
        new AnthropicStreamParser(),
        AnthropicException::new);
  }

  /** The HTTP request carrying {@code jsonBody}, authenticated by the configured API key if any. */
  HttpRequest httpRequest(String jsonBody) {
    var headers = new LinkedHashMap<String, String>();
    if (!Strings.isBlank(config.apiKey())) {
      headers.put("x-api-key", config.apiKey());
    }
    headers.put("anthropic-version", API_VERSION);
    return JsonPost.toBaseUrl(DEFAULT_BASE_URL, config, headers, jsonBody);
  }

  private static ModelConfig withBetas(ModelConfig config, List<String> betas) {
    var headers = new LinkedHashMap<>(config.headers());
    var values = new ArrayList<String>();
    headers.keySet().stream()
        .filter(BETA_HEADER::equalsIgnoreCase)
        .toList()
        .forEach(name -> values.add(headers.remove(name)));
    values.addAll(betas);
    headers.put(BETA_HEADER, String.join(",", values));
    return ModelConfig.newBuilder(config).withHeaders(headers).build();
  }

  private static String serialize(MessagesRequest request) {
    try {
      return AnthropicJson.LENIENT.writeValueAsString(request);
    } catch (RuntimeException e) {
      throw new AnthropicException("Failed to serialize request", e);
    }
  }
}
