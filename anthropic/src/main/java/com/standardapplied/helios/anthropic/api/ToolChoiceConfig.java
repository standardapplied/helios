/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tool choice configuration for the Claude Messages API.
 *
 * @param type the tool choice type: "auto", "any", "tool", or "none"
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

  public static ToolChoiceConfig none() {
    return new ToolChoiceConfig("none", null);
  }
}
