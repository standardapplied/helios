/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.common.Strings;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.SandboxPrelude;
import java.util.List;
import java.util.Objects;

/**
 * Builds the system prompt that drives a {@code CodeActPreset.typed(I, O, input)} session.
 *
 * <p>The CodeAct flavor is the constrained shape: the model writes Java in a stateful JShell
 * sandbox across multiple turns, and the <b>final assistant message itself is the answer</b> — a
 * JSON object matching the configured {@link OutputSchema}. The sandbox is scratch space; the
 * model's last assistant turn is the deliverable.
 *
 * <p>Deliberately narrower than {@link RlmStrategy}: no {@code submit()} or {@code predict()}
 * surface (so we don't prime the model to reach for tools it doesn't have), no sub-LM budget
 * paragraph. The shorter prompt is itself a feature — less context overhead per turn matters on
 * heavy trajectories.
 */
public final class CodeActStrategy {

  private CodeActStrategy() {}

  /**
   * Render the canonical CodeAct system prompt for the given input / output schemas.
   *
   * @param inputSchema schema describing the input record's fields; non-null
   * @param outputSchema schema describing the structured final assistant response; non-null
   * @param maxOutputCharsToModel cap from {@code ReplConfig.maxOutputCharsToModel()} so the prompt
   *     teaches the truncation discipline accurately
   * @param boundFieldNames names of input fields that are pre-bound as JShell variables; when
   *     non-empty, the prompt tells the model the variables are ready; when empty, instructs the
   *     model to read values from the JSON user message
   * @param extraHostFunctions optional list of custom host functions registered for this run, so
   *     the prompt enumerates the full sandbox API; may be empty
   * @param strategyText optional task-specific instructions; may be blank
   * @return the assembled system prompt text
   * @throws NullPointerException if either schema is null
   */
  public static String buildSystemPrompt(
      OutputSchema<?> inputSchema,
      OutputSchema<?> outputSchema,
      int maxOutputCharsToModel,
      List<String> boundFieldNames,
      List<HostFunction> extraHostFunctions,
      String strategyText) {
    Objects.requireNonNull(inputSchema, "inputSchema must not be null");
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    return build(
        inputSchema,
        outputSchema,
        maxOutputCharsToModel,
        boundFieldNames,
        extraHostFunctions,
        strategyText);
  }

  private static String build(
      OutputSchema<?> inputSchema,
      OutputSchema<?> outputSchema,
      int maxOutputCharsToModel,
      List<String> boundFieldNames,
      List<HostFunction> extraHostFunctions,
      String strategyText) {
    var sb = new StringBuilder();
    sb.append(
        """
        You solve the task by writing Java code in a sandboxed JShell REPL running on JDK 25. The \
        REPL is stateful across turns — variables and methods you declare on one turn are visible \
        on the next.

        The sandbox is restricted to:
        - The JDK 25 standard library (java.util, java.util.stream, java.io, java.math, \
        java.time, etc.)
        - The sandbox conveniences listed below (free print/println/printf plus a few script-style \
        helpers)
        - The host functions listed below under "## Your tools"

        No third-party libraries are available. No filesystem, network, or subprocess access \
        except through the host functions.

        Your final assistant message itself IS the answer — return a JSON object matching the \
        "Required output schema" below. The sandbox is scratch space; the assistant message is \
        the deliverable.

        """);
    if (!Strings.isBlank(strategyText)) {
      sb.append("## Task strategy\n").append(strategyText.strip()).append("\n\n");
    }
    sb.append("## Input\n");
    if (boundFieldNames != null && !boundFieldNames.isEmpty()) {
      sb.append(
          "These input fields are already bound as JShell variables in your sandbox. Use them"
              + " directly — no need to parse JSON or copy values. The variables are:\n");
      PromptRendering.appendFields(sb, inputSchema);
      sb.append('\n');
      sb.append(
          "(The same JSON is also delivered as the user message for your reference, but the"
              + " variables above are the canonical source — read them.)\n");
    } else {
      sb.append(
          "The user message in the next turn is a JSON document with these fields. The values are"
              + " not pre-bound as JShell variables — read each one as a literal from the user"
              + " message in your first execute_code call.\n");
      PromptRendering.appendFields(sb, inputSchema);
    }
    sb.append('\n');
    sb.append("## Required output schema\n");
    sb.append(
        "Your final assistant message must be a JSON object with these fields. The session parses"
            + " it against the schema; if parsing fails you will be re-prompted and can correct"
            + " it:\n");
    PromptRendering.appendFields(sb, outputSchema);
    sb.append('\n');
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
    PromptRendering.appendCustomHostFunctions(sb, extraHostFunctions);
    sb.append('\n');
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
    sb.append(
        """
        4. When you have the answer, stop calling Execute and return it. Your final assistant \
        message must be a JSON object matching the output schema above. Printing the answer in \
        the sandbox is NOT the answer — your assistant message IS the answer; the session reads \
        it directly.
        """);
    return sb.toString().strip();
  }
}
