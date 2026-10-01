/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.util.List;

/**
 * Provenance entry for a single top-level field of a {@code Provenanced} output. Carries the
 * sources that justify the field's value, free-text reasoning, and an ordinal {@link Confidence}.
 *
 * <p>The {@code field} value must match the JSON property name of the corresponding output field
 * exactly. {@code Provenanced<T>} validates structural correspondence (every output field has
 * exactly one entry) before any {@link ProvenanceValidator} runs.
 *
 * @param field name of the output field this entry describes
 * @param sources supporting sources; never {@code null} (use {@link List#of()} for none)
 * @param reasoning one or two sentence justification; never blank
 * @param confidence ordinal confidence level
 */
public record FieldProvenance(
    String field, List<Source> sources, String reasoning, Confidence confidence) {

  public FieldProvenance {
    if (Strings.isBlank(field)) {
      throw new IllegalArgumentException("field name must not be blank");
    }
    if (Strings.isBlank(reasoning)) {
      throw new IllegalArgumentException("reasoning must not be blank");
    }
    if (confidence == null) {
      throw new IllegalArgumentException("confidence must not be null");
    }
    if (sources != null) {
      for (var source : sources) {
        if (source == null) {
          throw new IllegalArgumentException("sources must not contain null");
        }
      }
    }
    sources = sources == null ? List.of() : List.copyOf(sources);
  }

  /**
   * Convenience factory for a {@link Confidence#LOW} entry with no sources. Useful for fields where
   * the model is expressing uncertainty and has nothing to cite.
   *
   * @param field name of the output field
   * @param reasoning justification
   * @return a {@code FieldProvenance} at {@code LOW} with empty sources
   */
  public static FieldProvenance lowConfidence(String field, String reasoning) {
    return new FieldProvenance(field, List.of(), reasoning, Confidence.LOW);
  }
}
