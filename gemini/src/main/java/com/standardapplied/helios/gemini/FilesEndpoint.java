/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ModelConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;

/**
 * The Gemini Files API root a configuration addresses, and the validation of every URL the API
 * hands back: an upload URL must be absolute, carry no user info or fragment, and stay on the
 * root's origin, so an API key never travels to another host.
 *
 * @param root the API root, without a version segment
 */
record FilesEndpoint(URI root) {

  private static final URI DEFAULT_ROOT = URI.create("https://generativelanguage.googleapis.com");

  /**
   * {@code config}, when it can address an authenticated Gemini endpoint.
   *
   * @throws IllegalArgumentException if it has neither an API key nor a base URL
   */
  static ModelConfig requireConfig(ModelConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    if (Strings.isBlank(config.apiKey()) && Strings.isBlank(config.baseUrl())) {
      throw new IllegalArgumentException(
          "config with valid apiKey is required (or set baseUrl + auth header)");
    }
    return config;
  }

  /** The HTTP client for {@code config}, once the configuration and its endpoint are valid. */
  static HttpClient createHttpClient(ModelConfig config) {
    var validated = requireConfig(config);
    of(validated);
    return HttpClientFactory.create(validated);
  }

  /**
   * The root {@code config} addresses: its base URL without a trailing slash or version segment, or
   * the public API.
   *
   * @throws IllegalArgumentException if the base URL is not an absolute http(s) URL without a query
   */
  static FilesEndpoint of(ModelConfig config) {
    if (Strings.isBlank(config.baseUrl())) {
      return new FilesEndpoint(DEFAULT_ROOT);
    }
    var configured = absolute(config.baseUrl(), "baseUrl must be a valid absolute URI");
    var scheme = configured.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("baseUrl must use the http or https scheme");
    }
    if (configured.getRawQuery() != null) {
      throw new IllegalArgumentException("baseUrl must not include a query string");
    }
    var root = configured.toString().replaceFirst("/+$", "").replaceFirst("/(?:v1|v1beta)$", "");
    return new FilesEndpoint(URI.create(root));
  }

  /** {@code path} under the root. */
  URI resolve(String path) {
    return URI.create(root + path);
  }

  /**
   * The upload URL the API returned in {@code value}.
   *
   * @throws GeminiException if it is missing, invalid or on another origin
   */
  URI uploadUrl(String value) {
    if (Strings.isBlank(value)) {
      throw new GeminiException("Gemini Files API did not return an upload URL");
    }
    URI uploadUrl;
    try {
      uploadUrl = absolute(value, "Gemini Files API returned an invalid upload URL");
    } catch (IllegalArgumentException e) {
      throw new GeminiException("Gemini Files API returned an invalid upload URL", e);
    }
    if (!sameOrigin(uploadUrl)) {
      throw new GeminiException("Gemini Files API returned an upload URL on a different origin");
    }
    return uploadUrl;
  }

  private static URI absolute(String value, String message) {
    try {
      var uri = URI.create(value);
      if (!uri.isAbsolute()
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null) {
        throw new IllegalArgumentException(message);
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(message, e);
    }
  }

  private boolean sameOrigin(URI other) {
    return root.getScheme().equalsIgnoreCase(other.getScheme())
        && root.getHost().equalsIgnoreCase(other.getHost())
        && effectivePort(root) == effectivePort(other);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
  }
}
