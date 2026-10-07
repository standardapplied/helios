/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.common.OrderedMaps;
import java.util.Map;

/**
 * Represents a tool call requested by the model.
 *
 * @param id unique identifier for this call (used to match results)
 * @param name the name of the tool to call
 * @param arguments the arguments to pass to the tool
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String id;
    private String name;
    private Map<String, Object> arguments = Map.of();

    private Builder() {}

    public Builder withId(String id) {
      this.id = id;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withArguments(Map<String, Object> arguments) {
      this.arguments = arguments != null ? OrderedMaps.copyOf(arguments) : Map.of();
      return this;
    }

    public ToolCall build() {
      return new ToolCall(id, name, arguments);
    }
  }
}
