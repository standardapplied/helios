/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import java.util.List;

/**
 * Thrown when a model's structured output is structurally valid but fails the schema's {@link
 * ai.singlr.core.common.SubmitValidator}. A {@link StructuredOutputParseException} subtype so the
 * session loop's self-correction treats a semantic rejection exactly like a schema mismatch —
 * append the rejected attempt, inject {@link #correctionMessage()}, retry within the turn budget —
 * while callers that care can still tell the two apart by type.
 */
public final class SubmitValidationException extends StructuredOutputParseException {

  /**
   * @param correction the validator's model-facing correction message; never blank
   * @param rawContent the model's raw response text, preserved for log-side debugging; may be null
   */
  public SubmitValidationException(String correction, String rawContent) {
    super("Structured output failed submit validation", List.of(correction), rawContent);
  }

  /** Creates a submit-validation failure under an explicit raw-output capture policy. */
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
