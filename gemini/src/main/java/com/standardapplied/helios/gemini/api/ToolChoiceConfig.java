/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tool-choice policy for the Interactions API, serialized to match the spec's {@code tool_choice}
 * oneOf:
 *
 * <ul>
 *   <li>Bare {@code ToolChoiceType} string ({@code auto}, {@code any}, {@code none}) when no
 *       allowed-tools restriction is given.
 *   <li>{@code ToolChoiceConfig} object {@code {"allowed_tools": {"mode": "validated", "tools":
 *       [...]}}} when {@link #validated(Set)} is used to lock the model down to a specific subset.
 * </ul>
 *
 * Goes inside {@link InteractionGenerationConfig#toolChoice()}, not at the root of the request.
 *
 * @param mode the {@code ToolChoiceType} discriminator
 * @param allowedTools the {@code tools} restriction; non-null only when {@link #validated(Set)} is
 *     used
 */
public record ToolChoiceConfig(String mode, Set<String> allowedTools) {

  public static ToolChoiceConfig auto() {
    return new ToolChoiceConfig("auto", null);
  }

  public static ToolChoiceConfig any() {
    return new ToolChoiceConfig("any", null);
  }

  public static ToolChoiceConfig none() {
    return new ToolChoiceConfig("none", null);
  }

  public static ToolChoiceConfig validated(Set<String> allowedTools) {
    return new ToolChoiceConfig("validated", allowedTools);
  }

  @JsonValue
  Object jsonValue() {
    if (allowedTools == null) {
      return mode;
    }
    return Map.of("allowed_tools", Map.of("mode", mode, "tools", List.copyOf(allowedTools)));
  }
}
