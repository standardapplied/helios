/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

/**
 * Per-entry calibration check applied to {@link FieldProvenance}. The default rejects {@link
 * Confidence#MEDIUM} or {@link Confidence#HIGH} entries that have no {@link Source} citations — the
 * only built-in mechanism preventing the model from rubber-stamping {@code HIGH} on every field.
 *
 * <p>Override per use case via {@link ai.singlr.core.schema.OutputSchema#provenancedOf(Class,
 * ProvenanceValidator)}:
 *
 * <ul>
 *   <li><b>SDTM mapping:</b> accept {@code cdisc-ct://} URIs as valid sources for HIGH without
 *       requiring excerpts, since the controlled-terminology lookup is itself authoritative.
 *   <li><b>Profile match-making:</b> tighten — require ≥3 excerpts at HIGH so the model has to cite
 *       three concrete signals before claiming a top match.
 *   <li><b>Per-source caps:</b> reject excerpts above a character threshold to prevent dumping
 *       whole pages into provenance.
 * </ul>
 *
 * <p>Validators run after structural correspondence checks (every output field has exactly one
 * entry) so they can assume a well-formed {@link FieldProvenance}.
 */
@FunctionalInterface
public interface ProvenanceValidator {

  /**
   * Validate a single provenance entry.
   *
   * @param entry the entry to validate; never {@code null}
   * @return success or a failure carrying a model-readable message
   */
  ValidationResult validate(FieldProvenance entry);

  /**
   * Default validator: {@link Confidence#MEDIUM} and {@link Confidence#HIGH} require at least one
   * source. {@link Confidence#LOW} always passes. This is the calibration mechanism baked into the
   * framework — override to relax or tighten.
   */
  ProvenanceValidator DEFAULT =
      entry ->
          switch (entry.confidence()) {
            case HIGH, MEDIUM ->
                entry.sources().isEmpty()
                    ? ValidationResult.failure(
                        "field '"
                            + entry.field()
                            + "': confidence="
                            + entry.confidence().wireValue()
                            + " requires at least one source")
                    : ValidationResult.success();
            case LOW -> ValidationResult.success();
          };

  /**
   * A validator that rejects every entry whose excerpts exceed {@code maxChars}. Compose with
   * {@link #andThen(ProvenanceValidator)} to attach this check on top of {@link #DEFAULT}.
   *
   * @param maxChars per-excerpt character cap; entries with any excerpt exceeding this are rejected
   * @return a validator enforcing the cap
   */
  static ProvenanceValidator excerptLengthCap(int maxChars) {
    if (maxChars <= 0) {
      throw new IllegalArgumentException("maxChars must be positive");
    }
    return entry -> {
      for (var source : entry.sources()) {
        for (var excerpt : source.excerpts()) {
          if (excerpt.length() > maxChars) {
            return ValidationResult.failure(
                "field '"
                    + entry.field()
                    + "': excerpt exceeds "
                    + maxChars
                    + " characters (got "
                    + excerpt.length()
                    + ")");
          }
        }
      }
      return ValidationResult.success();
    };
  }

  /**
   * Compose this validator with another. The returned validator runs {@code this} first; if it
   * fails, that failure is returned. Otherwise the {@code next} validator runs.
   *
   * @param next the validator to apply when {@code this} succeeds
   * @return a composed validator
   */
  default ProvenanceValidator andThen(ProvenanceValidator next) {
    if (next == null) {
      throw new IllegalArgumentException("next must not be null");
    }
    return entry -> {
      var first = validate(entry);
      return first.ok() ? next.validate(entry) : first;
    };
  }
}
