/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.repl.codeact;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.schema.JsonSchema;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.sandbox.SandboxPrelude;
import java.util.List;
import java.util.Set;

/**
 * Shared rendering primitives for the CodeAct / RLM system-prompt builders. Both {@link
 * CodeActStrategy} and {@link RlmStrategy} render the task strategy, the input, the sandbox
 * conveniences, the Execute tool, the first steps of how to work, input / output fields and custom
 * host-function signatures the same way; the differences between the two prompts live in their
 * preamble, output and protocol sections.
 *
 * <p>Package-private — prompt builders are the intended callers, not user code.
 */
final class PromptRendering {

  private static final Set<String> RESERVED_HOST_FUNCTIONS = HostFunctionRegistry.RESERVED_NAMES;

  private PromptRendering() {}

  /**
   * Append a bulleted list of an {@link OutputSchema}'s top-level fields to the buffer. Each line
   * carries the field name, a rendered type, an {@code [optional]} marker when not required, and
   * the field description when set. No-op when the schema or its properties are {@code null}.
   */
  static void appendFields(StringBuilder sb, OutputSchema<?> schema) {
    if (schema == null) {
      return;
    }
    var root = schema.schema();
    if (root == null || root.properties() == null) {
      return;
    }
    var required = root.required() != null ? root.required() : List.<String>of();
    for (var entry : root.properties().entrySet()) {
      var name = entry.getKey();
      var prop = entry.getValue();
      sb.append("  - ").append(name).append(" (").append(describe(prop)).append(")");
      if (!required.contains(name)) {
        sb.append(" [optional]");
      }
      if (!Strings.isBlank(prop.description())) {
        sb.append(" — ").append(prop.description());
      }
      sb.append('\n');
    }
  }

  /**
   * Append a "Custom host functions registered for this run" block when at least one function is
   * non-reserved. Skips reserved host-function names (e.g. {@code predict}, {@code submit}) since
   * the framework already documents those in the prompt preamble.
   */
  static void appendCustomHostFunctions(StringBuilder sb, List<HostFunction> functions) {
    if (functions == null || functions.isEmpty()) {
      return;
    }
    var rendered = false;
    for (var fn : functions) {
      if (RESERVED_HOST_FUNCTIONS.contains(fn.name())) {
        continue;
      }
      if (!rendered) {
        sb.append('\n').append("Custom host functions registered for this run:\n");
        rendered = true;
      }
      sb.append("  - ").append(SandboxPrelude.formatSignature(fn));
      if (!Strings.isBlank(fn.description())) {
        sb.append(" — ").append(fn.description());
      }
      sb.append('\n');
      for (var p : fn.parameters()) {
        sb.append("      ").append(p.required() ? "" : "[optional] ");
        sb.append(p.name()).append(" (").append(p.type().jsonType()).append(") — ");
        sb.append(p.description()).append('\n');
      }
    }
  }

  /** Append the "Task strategy" section when {@code strategyText} is not blank. */
  static void appendTaskStrategy(StringBuilder sb, String strategyText) {
    if (!Strings.isBlank(strategyText)) {
      sb.append("## Task strategy\n").append(strategyText.strip()).append("\n\n");
    }
  }

  /**
   * Append the "Input" section: the input fields, described as bound JShell variables when {@code
   * boundFieldNames} is non-empty and as fields of the user message otherwise.
   */
  static void appendInput(
      StringBuilder sb, OutputSchema<?> inputSchema, List<String> boundFieldNames) {
    sb.append("## Input\n");
    if (boundFieldNames != null && !boundFieldNames.isEmpty()) {
      sb.append(
          "These input fields are already bound as JShell variables in your sandbox. Use them"
              + " directly — no need to parse JSON or copy values. The variables are:\n");
      appendFields(sb, inputSchema);
      sb.append('\n');
      sb.append(
          "(The same JSON is also delivered as the user message for your reference, but the"
              + " variables above are the canonical source — read them.)\n");
    } else {
      sb.append(
          "The user message in the next turn is a JSON document with these fields. The values are"
              + " not pre-bound as JShell variables — read each one as a literal from the user"
              + " message in your first execute_code call.\n");
      appendFields(sb, inputSchema);
    }
    sb.append('\n');
  }

  /**
   * Append the "Sandbox conveniences" section and open the "Your tools" section with the Execute
   * tool; the caller appends the tools its prompt adds.
   */
  static void appendConveniencesAndExecuteTool(StringBuilder sb) {
    sb.append("## Sandbox conveniences\n")
        .append(SandboxPrelude.modelFacingSummary())
        .append("\n\n");
    sb.append("## Your tools\n");
    sb.append(
        """
        - Execute(runtime, script) — run code via the session's execution provider. For this \
        session, runtime must be "JSHELL" and script is the Java/JShell snippet to evaluate. \
        Sandbox state persists across calls.
        """);
  }

  /**
   * Open the "How to work" section with its first three steps: explore, persist work in variables,
   * and mind the {@code maxOutputCharsToModel} truncation; the caller appends its own steps.
   */
  static void appendHowToWork(StringBuilder sb, int maxOutputCharsToModel) {
    sb.append(
        """
        ## How to work
        1. Explore first. On your first iteration, print samples of the inputs to confirm types \
        and shapes. Don't extract before you've looked.
        2. Persist intermediate work in JShell variables. Variables live across iterations; \
        printed output does not. If you need a value later, save it to a named variable.
        """);
    sb.append("3. Printed output you see in tool results is truncated to ~")
        .append(maxOutputCharsToModel)
        .append(
            " characters. The variables themselves retain their full values. If you want to \"see\""
                + " a long value, slice it (e.g. var.substring(0, 500)) — don't rely on seeing the"
                + " full print output.\n");
  }

  /** Render a {@link JsonSchema} property's type as a short human-readable label. */
  static String describe(JsonSchema schema) {
    if (schema == null || schema.type() == null) {
      return "any";
    }
    return switch (schema.type()) {
      case "array" -> "List<" + (schema.items() != null ? describe(schema.items()) : "any") + ">";
      case "object" -> "object";
      case "integer" -> "int";
      case "number" -> "number";
      case "boolean" -> "boolean";
      case "string" ->
          schema.enumValues() != null && !schema.enumValues().isEmpty()
              ? "enum " + schema.enumValues()
              : "String";
      default -> schema.type();
    };
  }
}
