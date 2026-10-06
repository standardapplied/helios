/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.provider.JsonPost;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.openai.api.OpenAIJson;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Map;

/** Sends a Responses API request and opens its response as a stream of events. */
final class OpenAIStreams {

  static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/responses";

  private final ModelConfig config;
  private final HttpClient httpClient;

  OpenAIStreams(ModelConfig config, HttpClient httpClient) {
    this.config = config;
    this.httpClient = httpClient;
  }

  /** The stream answering {@code request}. */
  SseReader open(ResponsesRequest request) throws IOException, InterruptedException {
    return SseReader.open(
        httpClient,
        httpRequest(serialize(request)),
        config.streamIdleTimeout(),
        new OpenAIStreamParser(),
        OpenAIException::new);
  }

  /** The HTTP request carrying {@code jsonBody}, with the configured API key as a bearer token. */
  HttpRequest httpRequest(String jsonBody) {
    var headers =
        Strings.isBlank(config.apiKey())
            ? Map.<String, String>of()
            : Map.of("Authorization", "Bearer " + config.apiKey());
    return JsonPost.toBaseUrl(DEFAULT_BASE_URL, config, headers, jsonBody);
  }

  /** {@code request} as JSON. */
  String serialize(ResponsesRequest request) {
    try {
      return OpenAIJson.LENIENT.writeValueAsString(request);
    } catch (RuntimeException e) {
      throw new OpenAIException("Failed to serialize request", e);
    }
  }
}
