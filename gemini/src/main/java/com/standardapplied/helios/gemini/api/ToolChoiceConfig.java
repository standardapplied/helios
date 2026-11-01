/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini.api;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Tool-choice policy for the Interactions API, serialized to match the spec's {@code tool_choice}
 * oneOf:
 *
 * <ul>
 *   <li>Bare {@code ToolChoiceType} string ({@code auto}, {@code any}, {@code none}) when no
 *       allowed-tools restriction is given.
 *   <li>{@code ToolChoiceConfig} object {@code {"allowed_tools": {"mode": "any", "tools": [...]}}}
 *       when {@link #any(SequencedSet)} forces a call to one of a specific subset.
 * </ul>
 *
 * Goes inside {@link InteractionGenerationConfig#toolChoice()}, not at the root of the request.
 *
 * @param mode the {@code ToolChoiceType} discriminator
 * @param allowedTools the {@code tools} restriction; non-null only when {@link #any(SequencedSet)}
 *     is used
 */
public record ToolChoiceConfig(String mode, SequencedSet<String> allowedTools) {

  public static ToolChoiceConfig auto() {
    return new ToolChoiceConfig("auto", null);
  }

  public static ToolChoiceConfig any() {
    return new ToolChoiceConfig("any", null);
  }

  public static ToolChoiceConfig any(SequencedSet<String> allowedTools) {
    return new ToolChoiceConfig("any", allowedTools);
  }

  public static ToolChoiceConfig none() {
    return new ToolChoiceConfig("none", null);
  }

  @JsonValue
  Object jsonValue() {
    if (allowedTools == null) {
      return mode;
    }
    var restriction = new LinkedHashMap<String, Object>();
    restriction.put("mode", mode);
    restriction.put("tools", List.copyOf(allowedTools));
    return Map.of("allowed_tools", restriction);
  }
}
