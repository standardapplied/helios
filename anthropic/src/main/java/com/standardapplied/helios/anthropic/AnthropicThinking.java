/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.AnthropicModelId.ThinkingShape;
import com.standardapplied.helios.anthropic.api.OutputConfig;
import com.standardapplied.helios.anthropic.api.ThinkingConfig;
import com.standardapplied.helios.core.model.ThinkingLevel;
import java.util.Map;

/**
 * The thinking fields of a request: {@code thinking} and its sibling {@code output_config}, looked
 * up from the {@link ThinkingLevel} and the model's {@link ThinkingShape}. {@code NONE} takes each
 * shape's lowest setting: an explicit {@code disabled} on adaptive-default-on models, {@code
 * between_tools} on Sonnet 5.5, and an omitted field everywhere else, which leaves always-on models
 * thinking at the API's default effort. Adaptive models send {@code adaptive} plus an effort;
 * {@code ADAPTIVE_WITHOUT_XHIGH} models reject {@code XHIGH}; {@code LEGACY_BUDGET} models send
 * {@code enabled} plus {@code budget_tokens} and reject {@code XHIGH} and {@code MAX}, which have
 * no budget equivalent.
 *
 * @param thinking the {@code thinking} field, or {@code null} to omit it
 * @param outputConfig the {@code output_config} field, or {@code null} to omit it
 */
record AnthropicThinking(ThinkingConfig thinking, OutputConfig outputConfig) {

  private static final Map<ThinkingShape, ThinkingConfig> OFF =
      Map.of(
          ThinkingShape.ADAPTIVE_DEFAULT_ON, ThinkingConfig.disabled(),
          ThinkingShape.ADAPTIVE_BETWEEN_TOOLS, ThinkingConfig.betweenTools());

  private static final Map<ThinkingLevel, OutputConfig> EFFORT =
      Map.of(
          ThinkingLevel.MINIMAL, OutputConfig.LOW,
          ThinkingLevel.LOW, OutputConfig.LOW,
          ThinkingLevel.MEDIUM, OutputConfig.MEDIUM,
          ThinkingLevel.HIGH, OutputConfig.HIGH,
          ThinkingLevel.XHIGH, OutputConfig.XHIGH,
          ThinkingLevel.MAX, OutputConfig.MAX);

  private static final Map<ThinkingLevel, Integer> BUDGET_TOKENS =
      Map.of(
          ThinkingLevel.MINIMAL, 1024,
          ThinkingLevel.LOW, 4096,
          ThinkingLevel.MEDIUM, 10000,
          ThinkingLevel.HIGH, 32000);

  /** Room left for the answer above a legacy thinking budget. */
  private static final int ANSWER_TOKENS_ABOVE_BUDGET = 1024;

  /**
   * The thinking fields for {@code level}, {@code null} meaning none, on a model of {@code shape}.
   */
  static AnthropicThinking of(ThinkingShape shape, ThinkingLevel level, String wireModelId) {
    if (level == null || level == ThinkingLevel.NONE) {
      return new AnthropicThinking(OFF.get(shape), null);
    }
    if (shape == ThinkingShape.LEGACY_BUDGET) {
      return new AnthropicThinking(ThinkingConfig.enabled(budgetTokens(level, wireModelId)), null);
    }
    if (level == ThinkingLevel.XHIGH && shape == ThinkingShape.ADAPTIVE_WITHOUT_XHIGH) {
      throw new IllegalArgumentException(
          "ThinkingLevel.XHIGH requires Opus 4.7 or later; model "
              + wireModelId
              + " accepts effort low/medium/high/max only.");
    }
    return new AnthropicThinking(ThinkingConfig.adaptive(), EFFORT.get(level));
  }

  /** Whether the model thinks: a thinking config other than {@code disabled}. */
  boolean thinks() {
    return thinking != null && !"disabled".equals(thinking.type());
  }

  /** {@code requested}, raised to leave room for an answer above a legacy thinking budget. */
  int maxTokens(int requested) {
    if (thinking == null || thinking.budgetTokens() == null) {
      return requested;
    }
    return Math.max(requested, thinking.budgetTokens() + ANSWER_TOKENS_ABOVE_BUDGET);
  }

  private static int budgetTokens(ThinkingLevel level, String wireModelId) {
    var budget = BUDGET_TOKENS.get(level);
    if (budget == null) {
      throw new IllegalArgumentException(
          "ThinkingLevel."
              + level
              + " has no enabled+budget_tokens equivalent; model "
              + wireModelId
              + " supports extended thinking only (MINIMAL..HIGH). Adaptive models from"
              + " Opus 4.7 on accept xhigh/max.");
    }
    return budget;
  }
}
