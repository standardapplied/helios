/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostParameter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-sandbox {@code predict} host function: callable from JShell code via {@code
 * predict(instructions, input)} (routed through {@link
 * ai.singlr.repl.sandbox.HostBridge#predict(String, String)}).
 *
 * <p>Issues a fresh-context {@link Model#chat} call against a sub-LM and returns the response
 * content as a {@code String}. The sub-LM never sees the main session's conversation history —
 * that's the load-bearing context-rot fix for long RLM trajectories. Sandbox code typically wraps
 * this in stream-style fan-out:
 *
 * <pre>{@code
 * var summaries = candidates.stream().parallel()
 *     .map(c -> predict("Summarize the candidate", c.text()))
 *     .toList();
 * }</pre>
 *
 * <p>Parallels {@link PredictTool} for the agent-loop surface; the host-function variant is the
 * canonical RLM shape because the model can fan out within a single {@code execute_code} call.
 */
public final class PredictFunction {

  /** Reserved host-function name. */
  public static final String NAME = "predict";

  private PredictFunction() {}

  /**
   * Build the {@code predict} host function backed by the given sub-LM.
   *
   * @param subModel the sub-LM; non-null
   * @return a host function suitable for registration in {@link
   *     ai.singlr.repl.ReplConfig.Builder#withHostFunction(HostFunction)}
   * @throws NullPointerException if {@code subModel} is null
   */
  public static HostFunction create(Model subModel) {
    Objects.requireNonNull(subModel, "subModel must not be null");
    return new HostFunction(
        NAME,
        "Call a sub-LM with fresh context. Returns the response content as a String. Use this for"
            + " analysis that needs LM judgment (summarization, classification, extraction)."
            + " Parameters: instructions (system prompt), input (user content). Each call is"
            + " independent — no chat memory.",
        List.of(
            HostParameter.required(
                "instructions", ParameterType.STRING, "System instructions for the sub-LM call"),
            HostParameter.required("input", ParameterType.STRING, "User input sent to the sub-LM")),
        params -> {
          var instructions = params.get("instructions");
          if (!(instructions instanceof String inst) || Strings.isBlank(inst)) {
            throw new IllegalArgumentException(
                "predict: parameter 'instructions' is required and must be a non-blank string");
          }
          var input = params.get("input");
          if (!(input instanceof String inp) || Strings.isBlank(inp)) {
            throw new IllegalArgumentException(
                "predict: parameter 'input' is required and must be a non-blank string");
          }
          var response = subModel.chat(List.of(Message.system(inst), Message.user(inp)));
          var content = response.content() == null ? "" : response.content();
          return Map.of("output", content);
        });
  }
}
