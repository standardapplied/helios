/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.testing;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A deterministic {@link Tool} that answers a fixed script of outcomes, the tool counterpart of
 * {@link ScriptedModel}. Each call consumes the next scripted outcome in order, a result returned
 * as given or an exception its executor throws; when the script is exhausted the executor fails
 * fast so a test never silently passes a call it did not script. Outcomes are positional: a call's
 * arguments never choose its outcome. Thread-safe; every call's arguments are captured for
 * assertions via {@link #calls()}. Register {@link #tool()} with a session or pass it to a model.
 */
public final class ScriptedTool {

  private record Outcome(ToolResult result, RuntimeException thrown) {}

  private final Script script;
  private final Tool tool;

  private ScriptedTool(String name, Tool.Builder tool, List<Outcome> outcomes) {
    this.script = new Script(name, outcomes);
    this.tool = tool.withExecutor(script::execute).build();
  }

  /**
   * Creates a builder for a tool named {@code name}, with a description stating it is scripted, no
   * parameters and no outcomes scripted.
   *
   * @param name the name the model calls the tool by
   * @return a new builder
   * @throws IllegalStateException if {@code name} is {@code null} or blank, as {@link
   *     Tool.Builder#build()} reports it
   */
  public static Builder newBuilder(String name) {
    if (Strings.isBlank(name)) {
      throw new IllegalStateException("Tool name is required");
    }
    return new Builder(name);
  }

  /**
   * The tool to register with a session or pass to a model. Every call returns the same tool,
   * backed by this script and this record of calls.
   *
   * @return the tool
   */
  public Tool tool() {
    return tool;
  }

  /**
   * Every call's arguments, in the order the calls arrived. Each entry is a copy taken when its
   * call arrived: it keeps the arguments' key order and null values, and a later change to the
   * caller's map does not reach it.
   *
   * @return an unmodifiable list of unmodifiable maps, one per call received so far, including a
   *     call that found the script exhausted
   */
  public List<Map<String, Object>> calls() {
    return script.calls();
  }

  /** The scripted outcomes and the calls that have consumed them, guarded by its own monitor. */
  private static final class Script {

    private final String name;
    private final List<Outcome> outcomes;
    private final List<Map<String, Object>> calls = new ArrayList<>();

    Script(String name, List<Outcome> outcomes) {
      this.name = name;
      this.outcomes = List.copyOf(outcomes);
    }

    ToolResult execute(Map<String, Object> arguments, ToolContext context) {
      var outcome = next(arguments);
      if (outcome.thrown() != null) {
        throw outcome.thrown();
      }
      return outcome.result();
    }

    synchronized List<Map<String, Object>> calls() {
      return List.copyOf(calls);
    }

    private synchronized Outcome next(Map<String, Object> arguments) {
      var index = calls.size();
      calls.add(Collections.unmodifiableMap(new LinkedHashMap<>(arguments)));
      if (index >= outcomes.size()) {
        throw new IllegalStateException(
            "Scripted tool '%s' exhausted: %d outcome(s) scripted, call %d requested"
                .formatted(name, outcomes.size(), index + 1));
      }
      return outcomes.get(index);
    }
  }

  /** Builder for ScriptedTool. Outcomes answer calls in the order they are scripted. */
  public static final class Builder {

    private static final String DESCRIPTION =
        "A scripted test tool: each call returns the next outcome its test scripted.";

    private final String name;
    private final Tool.Builder tool;
    private final List<Outcome> outcomes = new ArrayList<>();

    private Builder(String name) {
      this.name = name;
      this.tool = Tool.newBuilder().withName(name).withDescription(DESCRIPTION);
    }

    /**
     * Sets what the tool does, as the model reads it, so the scripted tool can stand in for a real
     * one. Defaults to a sentence stating the tool is scripted.
     *
     * @param description the description; non-null
     * @return this builder for chaining
     * @throws NullPointerException if {@code description} is null
     */
    public Builder withDescription(String description) {
      tool.withDescription(Objects.requireNonNull(description, "description must not be null"));
      return this;
    }

    /**
     * Appends one parameter after those already added. The tool has none by default.
     *
     * @param parameter the parameter; non-null
     * @return this builder for chaining
     * @throws NullPointerException if {@code parameter} is null
     */
    public Builder withParameter(ToolParameter parameter) {
      tool.withParameter(Objects.requireNonNull(parameter, "parameter must not be null"));
      return this;
    }

    /**
     * Appends every parameter of {@code parameters}, in order, after those already added.
     *
     * @param parameters the parameters; non-null, without a null element
     * @return this builder for chaining
     * @throws NullPointerException if {@code parameters} or one of its elements is null
     */
    public Builder withParameters(List<ToolParameter> parameters) {
      Objects.requireNonNull(parameters, "parameters must not be null")
          .forEach(this::withParameter);
      return this;
    }

    /**
     * Scripts the next call to return {@code result} unchanged.
     *
     * @param result the result the call returns
     * @return this builder for chaining
     * @throws NullPointerException if {@code result} is null
     */
    public Builder withResult(ToolResult result) {
      Objects.requireNonNull(result, "result must not be null");
      outcomes.add(new Outcome(result, null));
      return this;
    }

    /**
     * Scripts the next call to succeed with {@code output}, as {@link ToolResult#success(String)}.
     *
     * @param output the result text; may be empty
     * @return this builder for chaining
     * @throws NullPointerException if {@code output} is null
     */
    public Builder withSuccess(String output) {
      Objects.requireNonNull(output, "output must not be null");
      return withResult(ToolResult.success(output));
    }

    /**
     * Scripts the next call to report failure with {@code error}, as {@link
     * ToolResult#failure(String)}.
     *
     * @param error the model-facing failure message
     * @return this builder for chaining
     * @throws NullPointerException if {@code error} is null
     */
    public Builder withFailure(String error) {
      Objects.requireNonNull(error, "error must not be null");
      return withResult(ToolResult.failure(error));
    }

    /**
     * Scripts the next call's executor to throw {@code exception}, so {@link Tool#execute} answers
     * it as a {@link ToolOutcome.Threw} result, as it answers a real tool that throws.
     *
     * @param exception the exception the executor throws
     * @return this builder for chaining
     * @throws NullPointerException if {@code exception} is null
     */
    public Builder withThrow(RuntimeException exception) {
      Objects.requireNonNull(exception, "exception must not be null");
      outcomes.add(new Outcome(null, exception));
      return this;
    }

    /**
     * Builds a tool that answers the outcomes scripted so far, in order. An empty script is
     * allowed: its first call fails with {@link IllegalStateException}. Outcomes and parameters
     * added after this call do not reach the built tool.
     *
     * @return the scripted tool
     */
    public ScriptedTool build() {
      return new ScriptedTool(name, tool, outcomes);
    }
  }
}
