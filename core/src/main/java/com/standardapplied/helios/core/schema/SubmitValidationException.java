/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.schema;

import java.util.List;

/**
 * Thrown when a model's structured output is structurally valid but fails the schema's {@link
 * com.standardapplied.helios.core.common.SubmitValidator}. A {@link StructuredOutputParseException}
 * subtype so the session loop's self-correction treats a semantic rejection exactly like a schema
 * mismatch — append the rejected attempt, inject {@link #correctionMessage()}, retry within the
 * turn budget — while callers that care can still tell the two apart by type.
 */
public final class SubmitValidationException extends StructuredOutputParseException {

  /**
   * Creates a submit-validation failure that retains {@code rawContent} as given.
   *
   * @param correction the validator's model-facing correction message; never blank
   * @param rawContent the model's raw response text, preserved for log-side debugging; may be null
   * @throws NullPointerException if {@code correction} is {@code null}
   */
  public SubmitValidationException(String correction, String rawContent) {
    super("Structured output failed submit validation", List.of(correction), rawContent);
  }

  /**
   * Creates a submit-validation failure under an explicit raw-output capture policy.
   *
   * @param correction the validator's model-facing correction message; never blank
   * @param rawContent the model's raw response text
   * @param capturePolicy whether {@code rawContent} may be retained; {@link
   *     RawOutputCapturePolicy#DISABLED} makes {@link #rawContent()} {@code null}
   * @throws NullPointerException if {@code correction} or {@code capturePolicy} is {@code null}
   */
  public SubmitValidationException(
      String correction, String rawContent, RawOutputCapturePolicy capturePolicy) {
    super(
        "Structured output failed submit validation",
        List.of(correction),
        java.util.Objects.requireNonNull(capturePolicy, "capturePolicy must not be null")
            .retain(rawContent));
  }

  @Override
  protected String correctionPreamble() {
    return "Your structured output matched the schema but failed a semantic check. Fix the listed"
        + " problems and re-emit the structured output:";
  }
}
