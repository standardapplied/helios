/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tool choice configuration for the Claude Messages API.
 *
 * @param type the tool choice type: "auto", "any", or "tool"
 * @param name required tool name when type is "tool"
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolChoiceConfig(String type, String name) {

  public static ToolChoiceConfig auto() {
    return new ToolChoiceConfig("auto", null);
  }

  public static ToolChoiceConfig any() {
    return new ToolChoiceConfig("any", null);
  }

  public static ToolChoiceConfig tool(String name) {
    return new ToolChoiceConfig("tool", name);
  }
}
