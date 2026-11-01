/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.provider.ReasoningSupport;
import java.util.EnumSet;

/**
 * Supported Gemini model identifiers.
 *
 * <p>Each enum constant maps to a specific Gemini model available through the Interactions API.
 */
public enum GeminiModelId {
  // maxOutputTokens reflects the documented per-model output ceiling at time of writing —
  // operators can override per-call via ModelConfig.Builder.withMaxOutputTokens.
  // reasoning lists each model's documented thinking_level set (Gemini thinking guide, Gemini 3
  // guide and Gemini 4 Argon page, 2026-10-20): 3.7 Flash, 3.8 Flash and 4 Argon accept
  // low/medium/high; 3.6 Flash also accepts minimal. None can turn thinking off; thought summaries
  // are on or off through thinking_summaries.
  GEMINI_3_6_FLASH("gemini-3.6-flash", 1_048_576, 65_536, Level.MINIMAL),
  GEMINI_3_7_FLASH("gemini-3.7-flash", 1_048_576, 65_536, Level.LOW),
  GEMINI_3_8_FLASH("gemini-3.8-flash", 1_048_576, 65_536, Level.LOW),
  GEMINI_4_ARGON("gemini-4-argon", 1_048_576, 65_536, Level.LOW);

  private final String id;
  private final int contextWindow;
  private final int maxOutputTokens;
  private final ReasoningSupport reasoning;

  GeminiModelId(String id, int contextWindow, int maxOutputTokens, Level lowestThinkingLevel) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
    this.reasoning =
        new ReasoningSupport(
            false,
            EnumSet.range(lowestThinkingLevel, Level.HIGH),
            EnumSet.of(Display.HIDDEN, Display.SUMMARY));
  }

  /**
   * The reasoning values this model accepts: its documented thinking levels, with or without
   * thought summaries, and never off. Read only by the request builder, the one place that turns it
   * into request fields.
   *
   * @return the support; non-null
   */
  public ReasoningSupport reasoning() {
    return reasoning;
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
