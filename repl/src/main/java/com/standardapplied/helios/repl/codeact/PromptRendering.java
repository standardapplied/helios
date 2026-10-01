/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.common.Strings;
import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.sandbox.SandboxPrelude;
import java.util.List;
import java.util.Set;

/**
 * Shared rendering primitives for the CodeAct / RLM system-prompt builders. Both {@link
 * CodeActStrategy} and {@link RlmStrategy} render input / output fields and custom host-function
 * signatures the same way; the differences between the two prompts live in their preamble and
 * protocol sections, not in the schema rendering.
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
