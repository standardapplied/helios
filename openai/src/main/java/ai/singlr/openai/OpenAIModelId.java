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
  // EffortSupport per model: the gpt-5.6 family documents the full none..max reasoning.effort
  // range (latest-model guide, 2026-07-18); gpt-5.4 and gpt-5.5 document low..xhigh; mini/nano
  // variants and o-series models are documented for low..high only, so higher tiers clamp.
  // gpt-5.6-terra and gpt-5.6-luna share the gpt-5.6 family limits per the latest-model guide;
  // adjust when OpenAI publishes per-variant pages.
  GPT_5_6("gpt-5.6", 1_050_000, 128_000, EffortSupport.FULL),
  GPT_5_6_TERRA("gpt-5.6-terra", 1_050_000, 128_000, EffortSupport.FULL),
  GPT_5_6_LUNA("gpt-5.6-luna", 1_050_000, 128_000, EffortSupport.FULL),
  GPT_5_5("gpt-5.5", 1_050_000, 128_000, EffortSupport.EXTENDED),
  GPT_5_4("gpt-5.4", 1_050_000, 128_000, EffortSupport.EXTENDED),
  GPT_5_4_MINI("gpt-5.4-mini", 400_000, 128_000, EffortSupport.STANDARD),
  GPT_5_4_NANO("gpt-5.4-nano", 400_000, 128_000, EffortSupport.STANDARD),
  GPT_4_1("gpt-4.1", 1_000_000, 32_000, EffortSupport.STANDARD),
  GPT_4_1_MINI("gpt-4.1-mini", 1_000_000, 32_000, EffortSupport.STANDARD),
  GPT_4_1_NANO("gpt-4.1-nano", 1_000_000, 16_000, EffortSupport.STANDARD),
  GPT_4O("gpt-4o", 128_000, 16_384, EffortSupport.STANDARD),
  GPT_4O_MINI("gpt-4o-mini", 128_000, 16_384, EffortSupport.STANDARD),
  O3("o3", 200_000, 100_000, EffortSupport.STANDARD),
  O4_MINI("o4-mini", 200_000, 100_000, EffortSupport.STANDARD);

  /**
   * The {@code reasoning.effort} value range a model accepts on the Responses API. Wider tiers are
   * supersets: {@link #FULL} accepts everything {@link #EXTENDED} does plus {@code none} and {@code
   * max}.
   */
  public enum EffortSupport {
    /** {@code low} / {@code medium} / {@code high} only; higher Helios tiers clamp to high. */
    STANDARD,

    /** Adds {@code xhigh}; {@code ThinkingLevel.MAX} clamps to xhigh. */
    EXTENDED,

    /**
     * Full range {@code none} / {@code low} / {@code medium} / {@code high} / {@code xhigh} /
     * {@code max} (gpt-5.6 family).
     */
    FULL
  }

  private final String id;
  private final int contextWindow;
  private final int maxOutputTokens;
  private final EffortSupport effortSupport;

  OpenAIModelId(String id, int contextWindow, int maxOutputTokens, EffortSupport effortSupport) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
    this.effortSupport = effortSupport;
  }

  /**
   * The {@code reasoning.effort} range this model accepts. Drives the {@code ThinkingLevel} → wire
   * mapping in {@code OpenAIModel}: tiers above the model's ceiling clamp down so requests stay
   * valid.
   *
   * @return the effort-support tier
   */
  public EffortSupport effortSupport() {
    return effortSupport;
  }

  /**
   * Whether this model accepts {@code reasoning.effort=xhigh}.
   *
   * @return true for {@link EffortSupport#EXTENDED} and {@link EffortSupport#FULL} models
   * @deprecated use {@link #effortSupport()}; the boolean cannot express the gpt-5.6 family's
   *     {@code none}/{@code max} support
   */
  @Deprecated(since = "2.8.0")
  public boolean supportsXhighEffort() {
    return effortSupport != EffortSupport.STANDARD;
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
