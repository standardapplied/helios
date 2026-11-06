/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.tool;

import com.standardapplied.helios.core.common.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Definition of a tool that can be called by the model.
 *
 * @param name the unique name of the tool
 * @param description description of what the tool does (for the model)
 * @param parameters the parameters the tool accepts
 * @param executor the function to execute when the tool is called
 */
public record Tool(
    String name, String description, List<ToolParameter> parameters, ToolExecutor executor) {

  /**
   * Returns an empty builder: no name, description, parameter or executor.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Execute this tool with the given arguments and a per-invocation {@link ToolContext}.
   *
   * @param arguments the arguments from the model; non-null
   * @param context per-call cancellation + deadline; non-null
   * @return the executor's result, or a {@link ToolOutcome.Threw} result when it throws or returns
   *     {@code null}
   */
  public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
    try {
      return Objects.requireNonNull(executor.execute(arguments, context), "executor returned null");
    } catch (Exception e) {
      if (hasInterruptedCause(e)) {
        Thread.currentThread().interrupt();
      }
      return ToolResult.threw(name, e);
    }
  }

  private static boolean hasInterruptedCause(Throwable e) {
    for (var cur = e; cur != null; cur = cur.getCause()) {
      if (cur instanceof InterruptedException) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the names of the parameters marked required, in declared order.
   *
   * @return an unmodifiable list, empty when no parameter is required
   */
  public List<String> requiredParameters() {
    return parameters.stream().filter(ToolParameter::required).map(ToolParameter::name).toList();
  }

  /**
   * Convert parameters to JSON Schema format (for model APIs): keys {@code type}, {@code
   * properties} and {@code required}, with properties in declared parameter order.
   *
   * @return an unmodifiable schema map; {@code required} is omitted when no parameter is required
   */
  public Map<String, Object> parametersAsJsonSchema() {
    var properties = new LinkedHashMap<String, Object>();
    for (var param : parameters) {
      properties.put(param.name(), param.jsonSchema());
    }
    var required = requiredParameters();
    var schema = new LinkedHashMap<String, Object>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    return Collections.unmodifiableMap(schema);
  }

  /** Fluent builder to prepare a Tool. */
  public static class Builder {
    private String name;
    private String description;
    private final List<ToolParameter> parameters = new ArrayList<>();
    private ToolExecutor executor;

    private Builder() {}

    /**
     * Sets the name the model calls the tool by, unique among a session's tools. Required.
     *
     * @param name the tool name; {@link #build()} rejects {@code null} or blank
     * @return this builder
     */
    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets what the tool does, as the model reads it when choosing a tool. Unset by default.
     *
     * @param description the description; may be {@code null}
     * @return this builder
     */
    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    /**
     * Appends one parameter after those already added; the order is the order the schema lists them
     * in.
     *
     * @param parameter the parameter; {@link #build()} rejects {@code null}
     * @return this builder
     */
    public Builder withParameter(ToolParameter parameter) {
      this.parameters.add(parameter);
      return this;
    }

    /**
     * Appends every parameter of {@code parameters}, in order, after those already added.
     *
     * @param parameters the parameters; must not be {@code null}, and {@link #build()} rejects a
     *     {@code null} element
     * @return this builder
     * @throws NullPointerException if {@code parameters} is {@code null}
     */
    public Builder withParameters(List<ToolParameter> parameters) {
      this.parameters.addAll(parameters);
      return this;
    }

    /**
     * Sets the function {@link Tool#execute} runs for each call. Required.
     *
     * @param executor the executor; {@link #build()} rejects {@code null}
     * @return this builder
     */
    public Builder withExecutor(ToolExecutor executor) {
      this.executor = executor;
      return this;
    }

    /**
     * Builds the tool with an unmodifiable copy of the parameters added so far.
     *
     * @return a new tool
     * @throws IllegalStateException if the name is {@code null} or blank, or no executor is set
     * @throws NullPointerException if a parameter added is {@code null}
     */
    public Tool build() {
      if (Strings.isBlank(name)) {
        throw new IllegalStateException("Tool name is required");
      }
      if (executor == null) {
        throw new IllegalStateException("Tool executor is required");
      }
      return new Tool(name, description, List.copyOf(parameters), executor);
    }
  }
}
