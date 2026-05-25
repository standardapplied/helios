/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import ai.singlr.core.common.Strings;

/**
 * Supported OpenAI model identifiers.
 *
 * <p>Each enum constant maps to a specific model available through the Responses API.
 */
public enum OpenAIModelId {
  // maxOutputTokens reflects the documented per-model output ceiling at time of writing —
  // operators can override per-call via ModelConfig.Builder.withMaxOutputTokens. Reasoning models
  // (o3, o4-mini) carry higher caps because their output includes reasoning tokens.
  //
  // supportsXhighEffort: gpt-5.4 and gpt-5.5 model pages explicitly document `xhigh` as an
  // accepted value for `reasoning.effort`. The mini/nano variants share their parent's reasoning
  // family but OpenAI has not published the per-variant value matrix; left false (conservative
  // clamp to "high") until OpenAI documents otherwise. o-series reasoning models (o3, o4-mini)
  // pre-date the xhigh tier and are documented to accept low/medium/high only.
  GPT_5_5("gpt-5.5", 1_050_000, 128_000, true),
  GPT_5_4("gpt-5.4", 1_050_000, 128_000, true),
  GPT_5_4_MINI("gpt-5.4-mini", 400_000, 128_000, false),
  GPT_5_4_NANO("gpt-5.4-nano", 400_000, 128_000, false),
  GPT_4_1("gpt-4.1", 1_000_000, 32_000, false),
  GPT_4_1_MINI("gpt-4.1-mini", 1_000_000, 32_000, false),
  GPT_4_1_NANO("gpt-4.1-nano", 1_000_000, 16_000, false),
  GPT_4O("gpt-4o", 128_000, 16_384, false),
  GPT_4O_MINI("gpt-4o-mini", 128_000, 16_384, false),
  O3("o3", 200_000, 100_000, false),
  O4_MINI("o4-mini", 200_000, 100_000, false);

  private final String id;
  private final int contextWindow;
  private final int maxOutputTokens;
  private final boolean supportsXhighEffort;

  OpenAIModelId(String id, int contextWindow, int maxOutputTokens, boolean supportsXhighEffort) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
    this.supportsXhighEffort = supportsXhighEffort;
  }

  /**
   * Whether this model accepts the {@code "xhigh"} value on the {@code reasoning.effort} parameter
   * of the Responses API. Confirmed against the gpt-5.4 and gpt-5.5 model pages on OpenAI's
   * developer docs (2026-05-24). Models without confirmed support clamp {@link
   * ai.singlr.core.model.ThinkingLevel#XHIGH} and {@link ai.singlr.core.model.ThinkingLevel#MAX} to
   * {@code "high"} so requests stay valid; flip this flag to {@code true} when OpenAI publishes
   * wider per-model support.
   *
   * @return true when {@code reasoning.effort=xhigh} is a valid request value for this model
   */
  public boolean supportsXhighEffort() {
    return supportsXhighEffort;
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
   * Finds an OpenAIModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching OpenAIModelId, or null if not found
   */
  public static OpenAIModelId fromId(String id) {
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
