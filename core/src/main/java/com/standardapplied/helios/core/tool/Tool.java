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
import java.util.function.Function;

/**
 * Definition of a tool that can be called by the model.
 *
 * @param name the unique name of the tool
 * @param description description of what the tool does (for the model)
 * @param parameters the parameters the tool accepts
 * @param executor the function to execute when the tool is called
 * @param idempotent whether invoking this tool more than once with the same arguments is safe.
 *     Drives two behaviors: in-process retry (a non-idempotent tool with retry configured will
 *     execute at most once instead of replaying through {@link
 *     com.standardapplied.helios.core.fault.FaultTolerance}'s retry policy) and durable resume (a
 *     non-idempotent tool that was in-flight at JVM crash blocks {@code Agent.resume(...)} under
 *     {@link com.standardapplied.helios.core.runtime.UnsafeResumePolicy#FAIL_LOUD}). Defaults to
 *     {@code false} via the builder so unannotated tools are conservatively treated as having side
 *     effects
 * @param resultCompactor compacts an old tool result down to a token-cheap form when context
 *     compaction kicks in. Receives the original tool-result content and returns a replacement
 *     string for older turns. Defaults to a constant {@code [result omitted]} — sufficient for
 *     stateless tools where the model does not need to remember what the older call produced. Tools
 *     whose results carry trajectory-relevant metadata (e.g. {@code execute_code}, where the model
 *     self-references prior outputs) should set a richer form preserving length and a prefix. Never
 *     {@code null} — the compact constructor coerces a null argument to the default
 */
public record Tool(
    String name,
    String description,
    List<ToolParameter> parameters,
    ToolExecutor executor,
    boolean idempotent,
    Function<String, String> resultCompactor) {

  /**
   * Default compactor used when callers don't supply one: every old result is replaced with a
   * constant placeholder.
   */
  public static final Function<String, String> DEFAULT_RESULT_COMPACTOR =
      content -> "[result omitted]";

  public Tool {
    if (resultCompactor == null) {
      resultCompactor = DEFAULT_RESULT_COMPACTOR;
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Execute this tool with the given arguments and a per-invocation {@link ToolContext}.
   *
   * @param arguments the arguments from the model; non-null
   * @param context per-call cancellation + deadline; non-null
   * @return the result of the execution
   */
  public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
    try {
      return executor.execute(arguments, context);
    } catch (Exception e) {
      if (hasInterruptedCause(e)) {
        Thread.currentThread().interrupt();
      }
      return ToolResult.failure("Tool execution failed", e);
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
    private boolean idempotent = false;
    private Function<String, String> resultCompactor = DEFAULT_RESULT_COMPACTOR;

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

    /**
     * Mark the tool as idempotent — safe to invoke more than once with the same arguments. Off by
     * default so tools with side effects are correctly treated as non-replayable in both retry and
     * resume paths.
     */
    public Builder withIdempotent(boolean idempotent) {
      this.idempotent = idempotent;
      return this;
    }

    /**
     * Override the result compactor used when older turns are dropped during context compaction.
     * Defaults to {@link #DEFAULT_RESULT_COMPACTOR} ({@code [result omitted]}). Passing {@code
     * null} resets to the default. Use this for tools whose old results carry useful metadata the
     * model may want to recall (e.g. {@code execute_code} preserves length and a prefix so the
     * model can self-reference what it ran earlier).
     */
    public Builder withResultCompactor(Function<String, String> resultCompactor) {
      this.resultCompactor = resultCompactor == null ? DEFAULT_RESULT_COMPACTOR : resultCompactor;
      return this;
    }

    public Tool build() {
      if (Strings.isBlank(name)) {
        throw new IllegalStateException("Tool name is required");
      }
      if (executor == null) {
        throw new IllegalStateException("Tool executor is required");
      }
      return new Tool(
          name, description, List.copyOf(parameters), executor, idempotent, resultCompactor);
    }
  }
}
