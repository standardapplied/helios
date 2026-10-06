/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ModelConfig;
import java.net.URI;

/**
 * Where a model's interactions go: the Interactions API version and its base URL. An explicit
 * version must be {@code v1} or {@code v1beta}; a configured base URL ending in one of them implies
 * it, and any other base URL is reported as {@code custom}.
 *
 * @param apiVersion the non-sensitive version reported for diagnostics
 * @param baseUrl the base URL, without a trailing slash
 */
record GeminiEndpoint(String apiVersion, String baseUrl) {

  static final String DEFAULT_API_ROOT = "https://generativelanguage.googleapis.com";
  static final String DEFAULT_API_VERSION = "v1";

  /** The endpoint {@code config} addresses. */
  static GeminiEndpoint of(ModelConfig config) {
    var apiVersion = apiVersion(config);
    var baseUrl =
        Strings.isBlank(config.baseUrl())
            ? DEFAULT_API_ROOT + "/" + apiVersion
            : withoutTrailingSlash(config.baseUrl());
    return new GeminiEndpoint(apiVersion, baseUrl);
  }

  /** The streaming interactions endpoint. */
  URI interactions() {
    return URI.create(baseUrl + "/interactions?alt=sse");
  }

  private static String apiVersion(ModelConfig config) {
    if (config.apiVersion() != null) {
      if ("v1".equals(config.apiVersion()) || "v1beta".equals(config.apiVersion())) {
        return config.apiVersion();
      }
      throw new IllegalArgumentException("apiVersion must be exactly 'v1' or 'v1beta'");
    }
    if (Strings.isBlank(config.baseUrl())) {
      return DEFAULT_API_VERSION;
    }
    var baseUrl = withoutTrailingSlash(config.baseUrl());
    if (baseUrl.endsWith("/v1beta")) {
      return "v1beta";
    }
    return baseUrl.endsWith("/v1") ? DEFAULT_API_VERSION : "custom";
  }

  private static String withoutTrailingSlash(String url) {
    return url.replaceFirst("/+$", "");
  }
}
