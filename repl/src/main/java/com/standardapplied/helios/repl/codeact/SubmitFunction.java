/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostParameter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-sandbox {@code submit} host function: callable from JShell code via {@code submit(output)}
 * (routed through {@link ai.singlr.repl.sandbox.HostBridge#submit(Object)}).
 *
 * <p>Shares the validation pipeline with {@link SubmitTool} via {@link SubmitValidation} so the
 * behavior is identical regardless of whether the model finalized via the agent-loop {@code Submit}
 * tool or the in-sandbox {@code submit(...)} host function.
 *
 * <p>Validation failures throw back through the JSON-RPC bridge so the model sees the correction
 * inline in its next {@code execute_code} iteration. Successful submits store the parsed value in
 * the {@link SubmittedValueHolder}; the {@link OnSubmitStopHook} watching the holder turns the next
 * post-tool dispatch into a {@code HookOutcome.Stop} with the JSON-serialized value.
 */
public final class SubmitFunction {

  /** Reserved host-function name. */
  public static final String NAME = "submit";

  private SubmitFunction() {}

  /**
   * Build the {@code submit} host function.
   *
   * @param schema the typed output schema the submitted value is validated against; non-null
   * @param holder the per-session holder that captures the parsed value; non-null
   * @return a host function suitable for registration in {@link
   *     ai.singlr.repl.ReplConfig.Builder#withHostFunction(HostFunction)}
   * @throws NullPointerException if either argument is null
   */
  public static HostFunction create(OutputSchema<?> schema, SubmittedValueHolder holder) {
    Objects.requireNonNull(schema, "schema must not be null");
    Objects.requireNonNull(holder, "holder must not be null");
    return new HostFunction(
        NAME,
        describe(schema),
        List.of(
            HostParameter.required(
                "output", ParameterType.OBJECT, "The structured final result to submit.")),
        params -> handle(params, schema, holder));
  }

  private static String describe(OutputSchema<?> schema) {
    if (schema.provenanceValidator() != null) {
      return "Submit the final structured result with provenance. 'output' must be {output: ...,"
          + " provenance: [...]} matching the schema.";
    }
    return "Submit the final structured result. Validates against the configured schema; failures"
        + " throw with a correction message you will see in your next iteration.";
  }

  private static Map<String, Object> handle(
      Map<String, Object> params, OutputSchema<?> schema, SubmittedValueHolder holder) {
    var output = params.get("output");
    if (output == null) {
      throw new IllegalArgumentException("Submit: parameter 'output' is required");
    }
    var result = SubmitValidation.validate(output, schema);
    return switch (result) {
      case SubmitValidation.Result.Rejected rej -> throw new IllegalArgumentException(rej.error());
      case SubmitValidation.Result.Accepted acc -> {
        if (!holder.submit(acc.value())) {
          throw new IllegalStateException(
              "submit() has already been called; further submits are ignored");
        }
        yield Map.of("status", "accepted");
      }
    };
  }
}
