/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.Validate;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a tool call requested by the model. Its arguments are a deep, unmodifiable snapshot
 * taken at construction, so the call a hook authorises is the call the tool executes, the events
 * record and a store persists. Constructing one with a {@code null} component throws {@link
 * NullPointerException}; with a blank id or name, or arguments {@link JsonSnapshot} rejects, {@link
 * IllegalArgumentException}.
 *
 * @param id unique identifier for this call (used to match results); non-null and non-blank
 * @param name the name of the tool to call; non-null and non-blank
 * @param arguments the arguments to pass to the tool: a JSON object as {@link JsonSnapshot} defines
 *     it, empty for a tool without parameters; a JSON {@code null} argument is a key present with a
 *     {@code null} value, an absent argument an absent key
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

  /**
   * Canonical constructor; replaces {@code arguments} with its deep, unmodifiable snapshot.
   *
   * @throws NullPointerException if a component is null
   * @throws IllegalArgumentException if {@code id} or {@code name} is blank, or {@link
   *     JsonSnapshot} rejects {@code arguments}
   */
  public ToolCall {
    Validate.notBlank("id", id);
    Validate.notBlank("name", name);
    Objects.requireNonNull(arguments, "arguments must not be null; a call without any is empty");
    arguments = JsonSnapshot.ofObject("arguments", arguments);
  }

  /**
   * Starts a builder with no id, no name and empty arguments.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builds a {@link ToolCall}, validated exactly as its canonical constructor validates it. */
  public static class Builder {
    private String id;
    private String name;
    private Map<String, Object> arguments = Map.of();

    private Builder() {}

    /**
     * Sets the provider-assigned id that pairs the call with its result message. Required; unset by
     * default.
     *
     * @param id the call id; must be non-blank when the call is built
     * @return this builder
     */
    public Builder withId(String id) {
      this.id = id;
      return this;
    }

    /**
     * Sets the name of the tool to call. Required; unset by default.
     *
     * @param name the tool name; must be non-blank when the call is built
     * @return this builder
     */
    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    /**
     * The arguments, snapshotted when the call is built; empty when never set.
     *
     * @param arguments the arguments as a JSON object; {@code null} fails {@link #build()}
     * @return this builder
     */
    public Builder withArguments(Map<String, Object> arguments) {
      this.arguments = arguments;
      return this;
    }

    /**
     * Builds the call.
     *
     * @return the call, its arguments snapshotted
     * @throws NullPointerException if the id, the name or the arguments is null
     * @throws IllegalArgumentException if the id or the name is blank, or {@link JsonSnapshot}
     *     rejects the arguments
     */
    public ToolCall build() {
      return new ToolCall(id, name, arguments);
    }
  }
}
