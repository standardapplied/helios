/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a tool call requested by the model.
 *
 * @param id unique identifier for this call (used to match results)
 * @param name the name of the tool to call
 * @param arguments the arguments to pass to the tool
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

  /**
   * An unmodifiable copy of tool-call {@code arguments} that keeps their order, so a replayed call
   * reaches the model exactly as it was made.
   *
   * @param arguments the arguments; non-null, with no null name or value
   * @return the ordered copy
   * @throws NullPointerException if {@code arguments}, a name or a value is null
   */
  public static Map<String, Object> copyOfArguments(Map<String, Object> arguments) {
    var copy = new LinkedHashMap<String, Object>();
    arguments.forEach(
        (name, value) ->
            copy.put(
                Objects.requireNonNull(name, "argument name must not be null"),
                Objects.requireNonNull(value, () -> "argument " + name + " must not be null")));
    return Collections.unmodifiableMap(copy);
  }

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
      this.arguments = arguments != null ? copyOfArguments(arguments) : Map.of();
      return this;
    }

    public ToolCall build() {
      return new ToolCall(id, name, arguments);
    }
  }
}
