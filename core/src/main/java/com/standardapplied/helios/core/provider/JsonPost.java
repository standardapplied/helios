/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.ModelConfig;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON {@code POST} a provider sends: its body, a JSON content type and the provider's default
 * headers, each overridden by a configured header of the same name, and the configured response
 * timeout. Send it with a client from {@code HttpClientFactory}, which never follows a redirect.
 */
public final class JsonPost {

  private JsonPost() {}

  /**
   * A post to the configured base URL, or to {@code defaultUrl} when none is configured.
   *
   * @param defaultUrl the provider's endpoint
   * @param config the model configuration
   * @param headers the provider's default headers, after the content type
   * @param body the JSON body
   * @return the request
   */
  public static HttpRequest toBaseUrl(
      String defaultUrl, ModelConfig config, Map<String, String> headers, String body) {
    return to(URI.create(config.effectiveBaseUrl(defaultUrl)), config, headers, body);
  }

  /**
   * A post to {@code endpoint}.
   *
   * @param endpoint where to send it
   * @param config the model configuration
   * @param headers the provider's default headers, after the content type
   * @param body the JSON body
   * @return the request
   */
  public static HttpRequest to(
      URI endpoint, ModelConfig config, Map<String, String> headers, String body) {
    var defaults = new LinkedHashMap<String, String>();
    defaults.put("Content-Type", "application/json");
    defaults.putAll(headers);
    var request =
        HttpRequest.newBuilder().uri(endpoint).POST(HttpRequest.BodyPublishers.ofString(body));
    config.effectiveHeaders(defaults).forEach(request::header);
    if (config.responseTimeout() != null) {
      request.timeout(config.responseTimeout());
    }
    return request.build();
  }
}
