/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import java.util.List;
import java.util.Objects;

/**
 * Factory for the {@code Predict} tool: the model invokes this tool to issue a fresh-context {@link
 * Model#chat} call against a sub-LM. The sub-LM never sees the main session's conversation history,
 * which is the load-bearing context-rot fix for long RLM-style trajectories — a long intermediate
 * prediction stays out of the orchestrator's window.
 *
 * <p>Cost and secret semantics:
 *
 * <ul>
 *   <li><b>Cost</b>: the sub-LM's usage accumulates on the session's {@link
 *       ai.singlr.core.common.CostCalculator} because the invocation flows through the agent loop's
 *       tool-dispatch path — the loop attributes the usage to the same session ledger.
 *   <li><b>Secrets</b>: redaction is provider-side. The sub-LM uses the same {@link
 *       ai.singlr.core.common.SecretRegistry} the main provider is configured with; nothing
 *       additional needs to be wired here.
 * </ul>
 *
 * <p>Errors: a sub-LM exception is caught and returned as {@link ToolResult#failure} so the model
 * can self-correct in the next turn (e.g. retry with shorter input). The exception's class name
 * leaks into the failure message; the full stack trace stays in the logging layer.
 */
public final class PredictTool {

  /** Stable tool name; matched by hooks and audit. */
  public static final String NAME = "Predict";

  private PredictTool() {}

  /**
   * Build a {@link Tool} that dispatches each call to {@code subModel.chat(...)} with a freshly
   * built two-message conversation: a system message carrying the supplied {@code instructions},
   * then a user message carrying the supplied {@code input}.
   *
   * @param subModel the sub-LM; non-null
   * @return the tool
   * @throws NullPointerException if {@code subModel} is null
   */
  public static Tool create(Model subModel) {
    Objects.requireNonNull(subModel, "subModel must not be null");
    return Tool.newBuilder()
        .withName(NAME)
        .withDescription(
            "Call a language model with fresh context. Use this when you need a focused"
                + " prediction without consuming the main loop's conversation window. Parameters:"
                + " instructions (system-level prompt), input (user-level prompt). Returns the"
                + " sub-LM's response content as text.")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("instructions")
                .withType(ParameterType.STRING)
                .withDescription("System instructions for the sub-LM call")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("input")
                .withType(ParameterType.STRING)
                .withDescription("User input sent to the sub-LM")
                .withRequired(true)
                .build())
        .withExecutor(
            (args, ctx) -> {
              if (!(args.get("instructions") instanceof String instructions)
                  || Strings.isBlank(instructions)) {
                return ToolResult.failure(
                    "Predict: parameter 'instructions' is required and must be a non-blank string");
              }
              if (!(args.get("input") instanceof String input) || Strings.isBlank(input)) {
                return ToolResult.failure(
                    "Predict: parameter 'input' is required and must be a non-blank string");
              }
              try {
                var response =
                    subModel.chat(List.of(Message.system(instructions), Message.user(input)));
                var content = response.content() == null ? "" : response.content();
                return ToolResult.success(content);
              } catch (RuntimeException e) {
                return ToolResult.failure(
                    "Predict: sub-LM call failed ("
                        + e.getClass().getSimpleName()
                        + "): "
                        + (e.getMessage() == null ? "<no message>" : e.getMessage()));
              }
            })
        .build();
  }

  /**
   * Build a {@link ToolBinding} that pairs the {@code Predict} tool with the {@link
   * ToolCategory#DELEGATION} category — the model is delegating to another model, and the
   * permission system treats DELEGATION as default-allow.
   *
   * @param subModel the sub-LM; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if {@code subModel} is null
   */
  public static ToolBinding binding(Model subModel) {
    return ToolBinding.newBuilder(create(subModel)).withCategory(ToolCategory.DELEGATION).build();
  }
}
