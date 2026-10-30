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

  /** Get required parameter names. */
  public List<String> requiredParameters() {
    return parameters.stream().filter(ToolParameter::required).map(ToolParameter::name).toList();
  }

  /**
   * Convert parameters to JSON Schema format (for model APIs): keys {@code type}, {@code
   * properties} and {@code required}, with properties in declared parameter order.
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

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withParameter(ToolParameter parameter) {
      this.parameters.add(parameter);
      return this;
    }

    public Builder withParameters(List<ToolParameter> parameters) {
      this.parameters.addAll(parameters);
      return this;
    }

    public Builder withExecutor(ToolExecutor executor) {
      this.executor = executor;
      return this;
    }

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
