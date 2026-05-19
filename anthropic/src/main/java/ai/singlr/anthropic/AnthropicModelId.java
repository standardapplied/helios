/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.core.common.Strings;

/**
 * Supported Anthropic Claude model identifiers.
 *
 * <p>Each enum constant maps to a specific Claude model available through the Messages API.
 */
public enum AnthropicModelId {
  // Opus 4.7 moved to the adaptive thinking shape; older models still use enabled+budget_tokens.
  // maxOutputTokens reflects the documented per-model output ceiling at time of writing — operators
  // can override per-call via ModelConfig.Builder.withMaxOutputTokens.
  CLAUDE_OPUS_4_7("claude-opus-4-7", 1_000_000, 32_000, true),
  CLAUDE_OPUS_4_6("claude-opus-4-6", 1_000_000, 32_000, false),
  CLAUDE_SONNET_4_6("claude-sonnet-4-6", 1_000_000, 64_000, false);

  private final String id;
  private final int contextWindow;
  private final int maxOutputTokens;
  private final boolean usesAdaptiveThinking;

  AnthropicModelId(
      String id, int contextWindow, int maxOutputTokens, boolean usesAdaptiveThinking) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
    this.usesAdaptiveThinking = usesAdaptiveThinking;
  }

  /**
   * Whether this model uses the new {@code thinking.type=adaptive} + {@code
   * output_config.effort=low|medium|high} request shape (Opus 4.7+) vs. the legacy {@code
   * thinking.type=enabled} + {@code budget_tokens=N} shape (Opus 4.6 and Sonnet 4.6).
   *
   * <p>Opus 4.7 explicitly rejects the legacy shape with {@code "thinking.type.enabled" is not
   * supported for this model}. New models are likely to be adaptive-only — set this {@code true}
   * for any future model when in doubt.
   *
   * @return true when the request must use the adaptive shape
   */
  public boolean usesAdaptiveThinking() {
    return usesAdaptiveThinking;
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
   * fallback when {@code ModelConfig.maxOutputTokens()} is unset, so callers don't silently get
   * truncated at a hardcoded framework default.
   *
   * @return the per-model output ceiling
   */
  public int maxOutputTokens() {
    return maxOutputTokens;
  }

  /**
   * Finds an AnthropicModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching AnthropicModelId, or null if not found
   */
  public static AnthropicModelId fromId(String id) {
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
