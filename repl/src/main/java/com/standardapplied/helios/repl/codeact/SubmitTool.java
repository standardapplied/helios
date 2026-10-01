/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import java.util.Map;
import java.util.Objects;

/**
 * Factory for the {@code Submit} tool: the model finalizes an RLM-style session by invoking this
 * tool with an {@code output} argument matching the session's {@link OutputSchema}. On accept, the
 * parsed typed value is stashed in a {@link SubmittedValueHolder} so the loop integration can carry
 * it through as the session's terminal value.
 *
 * <p>Validation pipeline is shared with {@link SubmitFunction} via {@link SubmitValidation} so the
 * agent-loop tool surface and the in-sandbox host-function surface stay behaviourally identical:
 *
 * <ol>
 *   <li>JSON Schema validation.
 *   <li>For provenanced schemas: reconstruct {@link ai.singlr.core.common.Provenanced} + verify
 *       structural correspondence + apply {@link ai.singlr.core.common.ProvenanceValidator}.
 *   <li>For schemas carrying a {@link ai.singlr.core.common.SubmitValidator}: convert to the typed
 *       value and run the validator.
 *   <li>{@link SubmittedValueHolder#submit(Object)} writes the parsed value; a {@code false} return
 *       (someone already submitted) is surfaced as a tool-result failure.
 * </ol>
 *
 * <p>Loop termination — refusing further turns once a submit has succeeded — is wired separately by
 * {@link OnSubmitStopHook}.
 */
public final class SubmitTool {

  /** Stable tool name; matched by hooks and audit. */
  public static final String NAME = "Submit";

  private SubmitTool() {}

  /**
   * Build a {@link Tool} that validates the {@code output} argument against {@code schema} and
   * stores the parsed value in {@code holder}.
   *
   * @param schema the session's typed output schema; non-null
   * @param holder the holder to write to on successful submit; non-null
   * @return the tool
   * @throws NullPointerException if either argument is null
   */
  public static Tool create(OutputSchema<?> schema, SubmittedValueHolder holder) {
    Objects.requireNonNull(schema, "schema must not be null");
    Objects.requireNonNull(holder, "holder must not be null");
    return Tool.newBuilder()
        .withName(NAME)
        .withDescription(describe(schema))
        .withParameter(
            ToolParameter.newBuilder()
                .withName("output")
                .withType(ParameterType.OBJECT)
                .withDescription("The structured final result to submit, matching the schema.")
                .withRequired(true)
                .build())
        .withExecutor((args, ctx) -> dispatch(args, schema, holder))
        .build();
  }

  /**
   * Build a {@link ToolBinding} that pairs the {@code Submit} tool with {@link
   * ToolCategory#CONTROL}.
   *
   * @param schema the typed output schema; non-null
   * @param holder the submission holder; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if either argument is null
   */
  public static ToolBinding binding(OutputSchema<?> schema, SubmittedValueHolder holder) {
    return ToolBinding.newBuilder(create(schema, holder))
        .withCategory(ToolCategory.CONTROL)
        .build();
  }

  private static String describe(OutputSchema<?> schema) {
    if (schema.provenanceValidator() != null) {
      return "Submit the final structured result with provenance. 'output' must be {output: ...,"
          + " provenance: [...]} where each provenance entry has field, sources, reasoning,"
          + " confidence (LOW|MEDIUM|HIGH). MEDIUM/HIGH require at least one source.";
    }
    return "Submit the final structured result. The 'output' value must match the configured"
        + " schema; mismatches return a correction inline so you can retry.";
  }

  private static ToolResult dispatch(
      Map<String, Object> args, OutputSchema<?> schema, SubmittedValueHolder holder) {
    var output = args.get("output");
    if (output == null) {
      return ToolResult.failure("Submit: parameter 'output' is required");
    }
    var result = SubmitValidation.validate(output, schema);
    return switch (result) {
      case SubmitValidation.Result.Rejected rej -> ToolResult.failure(rej.error());
      case SubmitValidation.Result.Accepted acc -> {
        if (!holder.submit(acc.value())) {
          yield ToolResult.failure(
              "Submit: a prior submit already accepted a result; further submissions are"
                  + " ignored.");
        }
        yield ToolResult.success("accepted");
      }
    };
  }
}
