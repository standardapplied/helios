/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

/**
 * Whole-output semantic validator applied at submit time. Runs after JSON Schema validation has
 * confirmed the output is structurally well-formed, so the validator can assume a fully-typed value
 * rather than a raw map.
 *
 * <p>Distinct from {@link ProvenanceValidator}: that one validates each {@link FieldProvenance}
 * entry of a provenanced output (calibration: confidence levels match citation requirements).
 * {@code SubmitValidator} validates the parsed output as a whole, regardless of whether the schema
 * is provenanced. Use it for content checks structural validation cannot express — minimum word
 * counts, forbidden phrasing, every claim having a non-empty source, presence of a required
 * keyword, etc.
 *
 * <p>Every path that accepts a final structured output runs it: {@code
 * ai.singlr.core.schema.StructuredContentParser} (providers, the session loop, typed {@code
 * runBlocking}) and the CodeAct {@code Submit} tool. A {@link ValidationResult#failure(String)}
 * reaches the model as a correction message and the loop retries within its turn budget — the same
 * machinery structural validation failures use — so a model that cannot satisfy the validator
 * terminates at {@code maxTurns} rather than looping forever or handing back a rejected value.
 *
 * <p>Callers invoke {@link #validateSafely(Object)}: an operator exception or a {@code null}
 * verdict becomes a validation failure so a buggy predicate doesn't tombstone the agent run.
 *
 * <p>Composition via {@link #andThen(SubmitValidator)} runs validators left-to-right and
 * short-circuits on the first failure — same semantics as {@link
 * ProvenanceValidator#andThen(ProvenanceValidator)}.
 *
 * @param <O> the parsed output type the validator inspects
 */
@FunctionalInterface
public interface SubmitValidator<O> {

  /**
   * Validate the parsed output.
   *
   * @param output the typed output the model submitted; never {@code null}
   * @return success or a failure carrying a model-readable correction message
   */
  ValidationResult validate(O output);

  /**
   * {@link #validate(Object)} hardened for the accept path: an exception thrown by the validator
   * becomes {@code failure("submit validator threw: <message>")} and a {@code null} verdict becomes
   * {@code failure("submit validator returned null")}.
   *
   * @param output the typed output the model submitted; never {@code null}
   * @return a non-null verdict
   */
  default ValidationResult validateSafely(O output) {
    ValidationResult result;
    try {
      result = validate(output);
    } catch (RuntimeException e) {
      return ValidationResult.failure("submit validator threw: " + e.getMessage());
    }
    return result == null ? ValidationResult.failure("submit validator returned null") : result;
  }

  /**
   * Compose this validator with another. The returned validator runs {@code this} first; if it
   * fails, that failure is returned. Otherwise the {@code next} validator runs.
   *
   * @param next the validator to apply when {@code this} succeeds
   * @return a composed validator
   */
  default SubmitValidator<O> andThen(SubmitValidator<O> next) {
    if (next == null) {
      throw new IllegalArgumentException("next must not be null");
    }
    return output -> {
      var first = validate(output);
      return first.ok() ? next.validate(output) : first;
    };
  }
}
