/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.common.Provenanced;
import ai.singlr.core.common.SubmitValidator;
import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.SchemaValidator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared validation pipeline for the model's submitted output. Both {@link SubmitTool} (agent-loop
 * tool surface) and {@link SubmitFunction} (in-sandbox host-function surface) feed their raw output
 * through this validator so the rules — JSON Schema → provenance reconstruction → {@link
 * SubmitValidator} — stay identical regardless of which path the model used.
 *
 * <p>Package-private; the two factories above are the intended callers.
 */
final class SubmitValidation {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private SubmitValidation() {}

  /**
   * Outcome of running an output through the pipeline.
   *
   * @param value the parsed typed value to stash when validation passes; non-null
   * @param error correction message for the model when validation fails; non-null
   */
  sealed interface Result {

    /** Validation passed; carry the parsed value to stash. */
    record Accepted(Object value) implements Result {}

    /** Validation failed; carry the model-facing correction string. */
    record Rejected(String error) implements Result {}
  }

  /**
   * Run the model's raw {@code output} through the full pipeline. Returns the parsed typed value on
   * accept; an error string suitable for {@link ai.singlr.core.tool.ToolResult#failure(String)} (or
   * {@link IllegalArgumentException} payload) on reject.
   *
   * @param rawOutput the value the model supplied (typically a {@code Map<String, Object>} from a
   *     JSON parse); must be non-null
   * @param schema the typed output schema; must be non-null
   * @return the validation outcome
   */
  static Result validate(Object rawOutput, OutputSchema<?> schema) {
    if (rawOutput == null) {
      return new Result.Rejected("Submit: parameter 'output' is required");
    }
    var errors = SchemaValidator.validate(rawOutput, schema.schema());
    if (!errors.isEmpty()) {
      return new Result.Rejected(formatSchemaError(errors, schema.schema()));
    }
    Object toStore = rawOutput;
    if (schema.provenanceValidator() != null) {
      try {
        toStore = validateAndReconstruct(rawOutput, schema);
      } catch (IllegalArgumentException e) {
        return new Result.Rejected(e.getMessage());
      }
    }
    if (schema.submitValidator() != null) {
      try {
        toStore = applySubmitValidator(toStore, schema);
      } catch (IllegalArgumentException e) {
        return new Result.Rejected(e.getMessage());
      }
    }
    return new Result.Accepted(toStore);
  }

  /**
   * Serialize a parsed value to JSON. Used by the loop-termination path so {@code
   * ResultMessage.Success} can carry the answer through the existing string-result envelope, and
   * the typed {@code runBlocking(msg, schema)} re-parses it into the user's record type.
   *
   * @param value the parsed value
   * @return JSON serialization, or {@code "null"} when {@code value} is null
   */
  static String toJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Failed to serialize submitted value to JSON", e);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object applySubmitValidator(Object current, OutputSchema<?> schema) {
    Object parsed;
    if (current instanceof Provenanced<?>) {
      parsed = current;
    } else {
      try {
        parsed = MAPPER.convertValue(current, schema.type());
      } catch (RuntimeException convertEx) {
        throw new IllegalArgumentException(
            "Submit validation failed:\n  - could not parse output as "
                + schema.type().getSimpleName()
                + ": "
                + convertEx.getMessage()
                + "\nFix the output value and call submit again.",
            convertEx);
      }
    }
    SubmitValidator validator = schema.submitValidator();
    var result = validator.validateSafely(parsed);
    if (!result.ok()) {
      throw new IllegalArgumentException(
          "Submit validation failed:\n  - "
              + result.message()
              + "\nFix the output value and call submit again.");
    }
    return parsed;
  }

  @SuppressWarnings("unchecked")
  private static Provenanced<?> validateAndReconstruct(Object output, OutputSchema<?> schema) {
    if (!(output instanceof Map<?, ?> envelope)) {
      throw new IllegalArgumentException(
          "Submit: provenanced submit expects an object envelope {output, provenance}");
    }
    var raw = (Map<String, Object>) envelope;
    var rawOutputObj = raw.get("output");
    if (!(rawOutputObj instanceof Map<?, ?> outputMap)) {
      throw new IllegalArgumentException(
          "Submit: provenanced submit: 'output' must be an object describing the typed result");
    }
    var declaredFields = new LinkedHashSet<String>();
    for (var k : outputMap.keySet()) {
      declaredFields.add(String.valueOf(k));
    }
    var reconstructed =
        OutputSchema.reconstructProvenanced(
            raw, value -> MAPPER.convertValue(value, schema.innerOutputType()));
    var errors = new ArrayList<String>();
    var seen = new LinkedHashSet<String>();
    for (var entry : reconstructed.provenance()) {
      if (!declaredFields.contains(entry.field())) {
        errors.add(
            "provenance entry references unknown field '"
                + entry.field()
                + "' (output has: "
                + declaredFields
                + ")");
        continue;
      }
      if (!seen.add(entry.field())) {
        errors.add("duplicate provenance entry for field '" + entry.field() + "'");
        continue;
      }
      var result = schema.provenanceValidator().validate(entry);
      if (!result.ok()) {
        errors.add(result.message());
      }
    }
    var missing = new LinkedHashSet<>(declaredFields);
    missing.removeAll(seen);
    for (var m : missing) {
      errors.add("missing provenance entry for output field '" + m + "'");
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(formatProvenanceError(errors));
    }
    return reconstructed;
  }

  private static String formatSchemaError(List<String> errors, JsonSchema schema) {
    var sb = new StringBuilder("Submit validation failed:");
    for (var error : errors) {
      sb.append("\n  - ").append(error);
    }
    if (schema != null && schema.required() != null && !schema.required().isEmpty()) {
      sb.append("\nRequired fields: ").append(schema.required());
    }
    sb.append("\nFix the output value and call submit again.");
    return sb.toString();
  }

  private static String formatProvenanceError(List<String> errors) {
    var sb = new StringBuilder("Provenance validation failed:");
    for (var error : errors) {
      sb.append("\n  - ").append(error);
    }
    sb.append("\nFix the provenance and call submit again.");
    return sb.toString();
  }
}
