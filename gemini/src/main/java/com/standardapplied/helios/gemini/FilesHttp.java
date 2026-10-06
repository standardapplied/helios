/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.gemini.api.GeminiJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The HTTP exchanges of the Gemini Files API: authenticated requests carrying the configured
 * headers, a status other than 2xx as a {@link GeminiException}, and JSON responses read up to 1
 * MB.
 */
final class FilesHttp {

  private static final int MAX_JSON_BYTES = 1024 * 1024;

  private final ModelConfig config;
  private final HttpClient httpClient;
  private final FilesEndpoint endpoint;

  FilesHttp(ModelConfig config, HttpClient httpClient, FilesEndpoint endpoint) {
    this.config = config;
    this.httpClient = httpClient;
    this.endpoint = endpoint;
  }

  /** A request to {@code path} under the API root, with the configured response timeout. */
  HttpRequest.Builder request(String path, Map<String, String> headers) {
    return request(endpoint.resolve(path), headers, true);
  }

  /**
   * A request to {@code uri} with {@code headers}, each overridden by a configured header of the
   * same name, and the configured response timeout when {@code boundedResponse}.
   */
  HttpRequest.Builder request(URI uri, Map<String, String> headers, boolean boundedResponse) {
    var builder = HttpRequest.newBuilder(uri);
    config.effectiveHeaders(headers).forEach(builder::header);
    if (boundedResponse && config.responseTimeout() != null) {
      builder.timeout(config.responseTimeout());
    }
    return builder;
  }

  /** The API key header, when one is configured, for the caller to add to. */
  LinkedHashMap<String, String> authenticatedHeaders() {
    var headers = new LinkedHashMap<String, String>();
    if (!Strings.isBlank(config.apiKey())) {
      headers.put("x-goog-api-key", config.apiKey());
    }
    return headers;
  }

  /** The response to {@code request}, whose status is 2xx. */
  HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new GeminiException(
          "Files API error (status " + response.statusCode() + "): " + errorBody(response.body()),
          response.statusCode());
    }
    return response;
  }

  private static String errorBody(InputStream body) throws IOException {
    try (body) {
      return HttpClientFactory.readBoundedErrorBody(body);
    }
  }

  /** The JSON body of {@code response} as a {@code type}. */
  <T> T readJson(HttpResponse<InputStream> response, Class<T> type) throws IOException {
    try (var body = response.body()) {
      var bytes = body.readNBytes(MAX_JSON_BYTES + 1);
      if (bytes.length > MAX_JSON_BYTES) {
        throw new GeminiException("Gemini Files API JSON response exceeded 1 MB");
      }
      try {
        return GeminiJson.LENIENT.readValue(bytes, type);
      } catch (RuntimeException e) {
        throw new GeminiException("Failed to parse Gemini Files API response", e);
      }
    }
  }

  /** Reads and discards the body of {@code response}, up to 1 MB. */
  void discard(HttpResponse<InputStream> response) throws IOException {
    try (var body = response.body()) {
      if (body.readNBytes(MAX_JSON_BYTES + 1).length > MAX_JSON_BYTES) {
        throw new GeminiException("Gemini Files API response exceeded 1 MB");
      }
    }
  }

  /** {@code value} as a JSON request body. */
  String serialize(Object value) {
    try {
      return GeminiJson.LENIENT.writeValueAsString(value);
    } catch (RuntimeException e) {
      throw new GeminiException("Failed to serialize Gemini Files API request", e);
    }
  }

  /** The current description of file {@code name}. */
  GeminiFileResource get(String name) throws IOException, InterruptedException {
    return readJson(
        send(request("/v1beta/" + name, authenticatedHeaders()).GET().build()),
        GeminiFileResource.class);
  }

  /**
   * Deletes file {@code resourceName}; a file already gone counts as deleted.
   *
   * @throws GeminiException if the API refuses or cannot be reached
   */
  void delete(String resourceName) {
    var request =
        request("/v1beta/" + GeminiFileResource.requireName(resourceName), authenticatedHeaders())
            .DELETE()
            .build();
    try {
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      response.body().close();
      var status = response.statusCode();
      if ((status >= 200 && status < 300) || status == 404) {
        return;
      }
      throw new GeminiException(
          "Gemini Files API delete failed (status " + response.statusCode() + ")",
          response.statusCode());
    } catch (IOException e) {
      throw new GeminiException("Failed to communicate with the Gemini Files API during delete");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException("Gemini file deletion interrupted");
    }
  }
}
