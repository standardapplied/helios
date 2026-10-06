/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.ThinkingLevel;
import com.standardapplied.helios.openai.OpenAIModelId.EffortSupport;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import java.util.List;
import java.util.Map;

/**
 * The {@code reasoning} config of a request, looked up from the {@link ThinkingLevel} and the
 * model's {@link EffortSupport}. {@code NONE} sends {@code "none"} where the model documents it —
 * omitting the config would run the model's default effort — {@code "low"}, the lowest effort,
 * where the model rejects {@code "none"}, and no config on a {@code STANDARD} model. Every other
 * level maps to its effort, clamped to the highest the model supports.
 */
final class OpenAIReasoning {

  private static final Map<EffortSupport, String> EFFORT_FOR_NONE =
      Map.of(
          EffortSupport.EXTENDED, "none",
          EffortSupport.FULL, "none",
          EffortSupport.FULL_WITHOUT_NONE, "low");

  private static final Map<ThinkingLevel, String> EFFORT =
      Map.of(
          ThinkingLevel.MINIMAL, "low",
          ThinkingLevel.LOW, "low",
          ThinkingLevel.MEDIUM, "medium",
          ThinkingLevel.HIGH, "high",
          ThinkingLevel.XHIGH, "xhigh",
          ThinkingLevel.MAX, "max");

  private static final Map<EffortSupport, String> HIGHEST_EFFORT =
      Map.of(
          EffortSupport.STANDARD, "high",
          EffortSupport.EXTENDED, "xhigh",
          EffortSupport.FULL, "max",
          EffortSupport.FULL_WITHOUT_NONE, "max");

  private static final List<String> EFFORTS_ASCENDING =
      List.of("low", "medium", "high", "xhigh", "max");

  private OpenAIReasoning() {}

  /** The reasoning config for {@code level}, a null level being {@code NONE}; null to omit it. */
  static ResponsesRequest.ReasoningConfig of(EffortSupport support, ThinkingLevel level) {
    if (level == null || level == ThinkingLevel.NONE) {
      var effort = EFFORT_FOR_NONE.get(support);
      return effort == null ? null : ResponsesRequest.ReasoningConfig.of(effort);
    }
    var requested = EFFORTS_ASCENDING.indexOf(EFFORT.get(level));
    var highest = EFFORTS_ASCENDING.indexOf(HIGHEST_EFFORT.get(support));
    return ResponsesRequest.ReasoningConfig.of(EFFORTS_ASCENDING.get(Math.min(requested, highest)));
  }
}
