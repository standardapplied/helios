/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.ThinkingLevel;
import java.util.Map;

/**
 * The {@code thinking_level} of a request, looked up from the {@link ThinkingLevel}. Gemini 3.x
 * cannot turn thinking off and an omitted level runs the model's default tier (medium on 3.5, 3.6
 * and 3.7 Flash, high on 3.1 Pro), so {@code NONE} and {@code MINIMAL} pin the model's documented
 * floor; {@code XHIGH} and {@code MAX}, tiers only Anthropic and OpenAI have, clamp to {@code
 * high}.
 */
final class GeminiThinking {

  private static final Map<ThinkingLevel, String> LEVEL =
      Map.of(
          ThinkingLevel.LOW, "low",
          ThinkingLevel.MEDIUM, "medium",
          ThinkingLevel.HIGH, "high",
          ThinkingLevel.XHIGH, "high",
          ThinkingLevel.MAX, "high");

  private GeminiThinking() {}

  /** The {@code thinking_level} of {@code level}, {@code null} meaning none, on {@code model}. */
  static String level(GeminiModelId model, ThinkingLevel level) {
    var value = level == null ? null : LEVEL.get(level);
    return value == null ? model.lowestThinkingLevel() : value;
  }
}
