/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import ai.singlr.core.common.Strings;

/**
 * Supported Gemini model identifiers.
 *
 * <p>Each enum constant maps to a specific Gemini model available through the Interactions API.
 */
public enum GeminiModelId {
  // maxOutputTokens reflects the documented per-model output ceiling at time of writing —
  // operators can override per-call via ModelConfig.Builder.withMaxOutputTokens.
  GEMINI_3_FLASH_PREVIEW("gemini-3-flash-preview", 1_048_576, 65_536),
  GEMINI_3_1_PRO_PREVIEW("gemini-3.1-pro-preview", 1_048_576, 65_536),
  GEMINI_3_1_FLASH_LITE("gemini-3.1-flash-lite", 1_048_576, 65_536),
  GEMINI_3_5_FLASH("gemini-3.5-flash", 1_048_576, 65_536);

  private final String id;
  private final int contextWindow;
  private final int maxOutputTokens;

  GeminiModelId(String id, int contextWindow, int maxOutputTokens) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
  }

  /**
   * Returns the API model identifier string.
   *
   * @return the model ID used in API requests
   */
  public String id() {
    return id;
  }

  /**
   * Returns the context window size in tokens.
   *
   * @return the context window size
   */
  public int contextWindow() {
    return contextWindow;
  }

  /**
   * Returns the maximum output tokens this model can generate in a single response. Used as the
   * fallback when {@code ModelConfig.maxOutputTokens()} is unset.
   *
   * @return the per-model output ceiling
   */
  public int maxOutputTokens() {
    return maxOutputTokens;
  }

  /**
   * Finds a GeminiModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching GeminiModelId, or null if not found
   */
  public static GeminiModelId fromId(String id) {
    if (Strings.isBlank(id)) {
      return null;
    }
    for (var model : values()) {
      if (model.id.equals(id)) {
        return model;
      }
    }
    return null;
  }

  /**
   * Checks if the given model ID is supported.
   *
   * @param id the model identifier string
   * @return true if the model is supported
   */
  public static boolean isSupported(String id) {
    return fromId(id) != null;
  }
}
