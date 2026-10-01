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
import java.util.OptionalInt;

/**
 * Builds the system prompt that drives a {@code CodeActPreset.withSubLm(...)} session — the RLM
 * (Recursive Language Model) shape.
 *
 * <p>The model writes Java in a stateful JShell sandbox; the in-sandbox {@code predict(...)} host
 * function lets it dispatch fresh-context calls to a sub-LM, and the in-sandbox {@code
 * submit(output)} host function finalizes the typed answer. The sandbox is the surface — the
 * model's final assistant message is unused for the result; only {@code submit(...)} stores the
 * deliverable.
 *
 * <p>Ports the load-bearing conventions from v1's {@code RlmSystemPrompt} which itself ported them
 * from Trampoline's {@code predict-rlm} accumulated production wisdom: variables persist / prints
 * get truncated, verify-then-submit-alone, don't loop predict over every line, submit's structured
 * output is enforced, validation errors come back inline.
 */
public final class RlmStrategy {

  private RlmStrategy() {}

  /**
   * Render the canonical RLM system prompt.
   *
   * @param inputSchema schema describing the input record's fields; non-null
   * @param outputSchema schema describing the structured output the model must {@code submit};
   *     non-null
   * @param maxOutputCharsToModel cap from {@code ReplConfig.maxOutputCharsToModel()} so the prompt
   *     teaches the truncation discipline accurately
   * @param maxLlmCalls cap on per-session {@code predict()} calls. {@link OptionalInt#empty()}
   *     omits the budget paragraph; a present value must be strictly positive
   * @param boundFieldNames names of input fields pre-bound as JShell variables; when non-empty the
   *     prompt tells the model the variables are ready, when empty it instructs reading the JSON
   *     user message
   * @param extraHostFunctions optional list of additional host functions to enumerate; may be empty
   * @param strategyText optional task-specific instructions; may be blank
   * @return the assembled system prompt text
   * @throws NullPointerException if {@code inputSchema}, {@code outputSchema}, or {@code
   *     maxLlmCalls} is null
   * @throws IllegalArgumentException if a present {@code maxLlmCalls} value is not strictly
   *     positive
   */
  public static String buildSystemPrompt(
      OutputSchema<?> inputSchema,
      OutputSchema<?> outputSchema,
      int maxOutputCharsToModel,
      OptionalInt maxLlmCalls,
      List<String> boundFieldNames,
      List<HostFunction> extraHostFunctions,
      String strategyText) {
    Objects.requireNonNull(inputSchema, "inputSchema must not be null");
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    Objects.requireNonNull(maxLlmCalls, "maxLlmCalls must not be null");
    if (maxLlmCalls.isPresent() && maxLlmCalls.getAsInt() <= 0) {
      throw new IllegalArgumentException(
          "maxLlmCalls must be strictly positive when present, got " + maxLlmCalls.getAsInt());
    }
    return build(
        inputSchema,
        outputSchema,
        maxOutputCharsToModel,
        maxLlmCalls,
        boundFieldNames,
        extraHostFunctions,
        strategyText);
  }

  private static String build(
      OutputSchema<?> inputSchema,
      OutputSchema<?> outputSchema,
      int maxOutputCharsToModel,
      OptionalInt maxLlmCalls,
      List<String> boundFieldNames,
      List<HostFunction> extraHostFunctions,
      String strategyText) {
    var sb = new StringBuilder();
    sb.append(
        """
        You solve a long-horizon task by writing Java code in a sandboxed JShell REPL. The REPL \
        runs across many turns; variables you bind on one turn are still there on the next. Your \
        goal is to produce a structured final answer by calling submit(...).

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
    sb.append("## Required output\n");
    sb.append(
        "You signal completion by calling submit(...) with a Map whose keys match this schema."
            + " The host validates against the schema; if validation fails you will see an error"
            + " in your next iteration's tool result and can fix and resubmit:\n");
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

        Inside the sandbox these host functions are reachable directly:
          - predict(instructions, input) — call a sub-LLM with fresh context. Returns a String. \
        Use this for analysis you can't compute deterministically (summarization, classification, \
        extraction). Each call is independent — no chat memory.
          - submit(output) — submit your final structured result. Output must be a Map matching \
        the output schema above. Calling submit ends the run after the current iteration.
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
        4. Use predict() for the parts the LM has to do. predict("instructions", input) returns a \
        String. Capture that string in a variable; do not print large predict() outputs at full \
        length.
        """);
    if (maxLlmCalls.isPresent()) {
      sb.append("5. Budget: you have at most ")
          .append(maxLlmCalls.getAsInt())
          .append(
              " predict() calls per session. If you trip the budget you will see"
                  + " SandboxBudgetExceededException — at that point stop calling predict and"
                  + " submit your best answer with the data you've already gathered.\n");
    }
    sb.append('\n');
    sb.append(
        """
        ## How the run ends

        Your work is captured ONLY when you call submit(...). Computing a value, printing it, or \
        leaving it in a variable does NOT return it. This is NOT a notebook — there is no \
        "implicit last expression" semantics. The loop keeps running until you submit; if you \
        stop calling tools without ever calling submit, the run terminates with no result and \
        your work is lost.

        Therefore, every run MUST end with a call to submit(...). When you have computed the \
        answer, your next execute_code call should be:

            submit(Map.of("field1", value1, "field2", value2, ...))

        and nothing else. Do NOT mix verification and submit in the same execute_code call — \
        verify in one call (print, check), then submit ALONE in the next call. If you only \
        printed the answer and didn't call submit, you are not done.

        If submit fails validation, you will see "Submit validation failed: ..." in the tool \
        result naming the missing or wrong-typed fields. Read the message, fix the Map, and call \
        submit again. Sandbox variables are not lost across validation failures.

        Be concise in your reasoning text. The work happens in the code blocks; the prose around \
        them should explain what you intend to do and why, not retell the code.
        """);
    return sb.toString().strip();
  }
}
