/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import java.util.List;
import java.util.Objects;

/**
 * Thrown when a model's structured-output response fails to parse — either because the JSON is
 * syntactically invalid or because it does not conform to the configured {@link OutputSchema}.
 * Carries error messages (field-level diffs from {@link SchemaValidator} for schema mismatches, or
 * the JSON parse error message for syntax failures), so the agent loop can surface specific
 * corrections to the model.
 *
 * <p>Distinguished from generic {@link ai.singlr.core.model.ProviderException} subclasses so the
 * session loop can pattern-match on it: when this exception type bubbles out of a structured-output
 * {@code chat(...)} call, the loop injects {@link #correctionMessage()} as a USER turn and
 * continues iterating instead of failing terminally.
 *
 * <p>The {@link #rawContent()} field preserves the model's original output for log-side debugging
 * when {@link RawOutputCapturePolicy#ENABLED}. Privacy-sensitive callers select {@link
 * RawOutputCapturePolicy#DISABLED} through {@code ModelConfig}; correction details remain available
 * while {@code rawContent()} stays {@code null}. Raw content is never echoed in {@link
 * #correctionMessage()}.
 */
public class StructuredOutputParseException extends RuntimeException {

  private final List<String> errors;
  private final String rawContent;

  /**
   * @param errors per-field validation messages produced by {@link SchemaValidator}; never empty
   * @param rawContent the model's raw response text (preserved for log-side debugging); may be
   *     {@code null} when the caller doesn't have it available
   */
  public StructuredOutputParseException(List<String> errors, String rawContent) {
    this("Structured output schema validation failed", errors, rawContent);
  }

  /**
   * Creates a parse failure under an explicit raw-output capture policy.
   *
   * @param errors actionable validation messages; never empty
   * @param rawContent the model's raw response text
   * @param capturePolicy whether {@code rawContent} may be retained
   */
  public StructuredOutputParseException(
      List<String> errors, String rawContent, RawOutputCapturePolicy capturePolicy) {
    this(
        "Structured output schema validation failed",
        errors,
        Objects.requireNonNull(capturePolicy, "capturePolicy must not be null").retain(rawContent));
  }

  /**
   * Subclass constructor with a custom exception-message summary; {@code errors} and {@code
   * rawContent} carry the same contract as the public constructor.
   *
   * @param summary the first line of {@link #getMessage()}; the errors are itemised beneath it
   */
  protected StructuredOutputParseException(String summary, List<String> errors, String rawContent) {
    super(buildMessage(summary, errors));
    Objects.requireNonNull(errors, "errors must not be null");
    if (errors.isEmpty()) {
      throw new IllegalArgumentException("errors must not be empty");
    }
    this.errors = List.copyOf(errors);
    this.rawContent = rawContent;
  }

  /** Per-field validation messages, in the order {@link SchemaValidator} produced them. */
  public List<String> errors() {
    return errors;
  }

  /** The model's raw response text, preserved for log-side debugging. May be {@code null}. */
  public String rawContent() {
    return rawContent;
  }

  /**
   * The model-facing correction string the agent loop injects as the next USER message. Names every
   * failing field with its expected/actual mismatch and instructs the model to re-emit the
   * structured output. Does <em>not</em> include the raw content — see the class-level note for
   * why.
   */
  public String correctionMessage() {
    var sb = new StringBuilder(correctionPreamble()).append('\n');
    for (var error : errors) {
      sb.append("  - ").append(error).append('\n');
    }
    return sb.toString();
  }

  /**
   * The sentence that opens {@link #correctionMessage()}, before the itemised errors. Subclasses
   * override it to describe their failure class accurately to the model.
   */
  protected String correctionPreamble() {
    return "Your structured output did not match the schema. Fix the listed fields and re-emit "
        + "the structured output:";
  }

  private static String buildMessage(String summary, List<String> errors) {
    if (errors == null || errors.isEmpty()) {
      return summary;
    }
    var sb = new StringBuilder(summary).append(':');
    for (var error : errors) {
      sb.append("\n  - ").append(error);
    }
    return sb.toString();
  }
}
