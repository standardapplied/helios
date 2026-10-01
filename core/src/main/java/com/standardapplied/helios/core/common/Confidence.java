/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.util.Locale;

/**
 * Ordinal confidence level for a {@link FieldProvenance} entry.
 *
 * <p>Confidence is intentionally ordinal rather than numeric. LLMs cannot self-calibrate to
 * probabilities; they reliably distinguish three levels and bunch numeric scores around 0.7–0.95
 * regardless of evidence strength. The three levels here mirror parallel.ai's production Basis
 * framework, which arrived at the same ordinal contract empirically.
 *
 * <p>Calibration is enforced separately by {@link ProvenanceValidator}: by default {@link #MEDIUM}
 * and {@link #HIGH} require at least one {@link Source} citation, so the model cannot claim
 * confidence without producing supporting evidence.
 */
public enum Confidence {
  /** Weak signal; the value is a best guess and should be treated as such by the caller. */
  LOW,

  /** Moderately supported by at least one source. */
  MEDIUM,

  /** Strongly supported. Requires citations under the default validator. */
  HIGH;

  /**
   * Returns the JSON wire form of this confidence level — the upper-case enum name. Aligns with
   * Helios' {@link ai.singlr.core.schema.SchemaGenerator} default, which emits {@code enum: ["LOW",
   * "MEDIUM", "HIGH"]}, and with Jackson's default enum (de)serialization. Models receive the
   * upper-case schema and produce upper-case output, no custom serializer required.
   *
   * @return the enum name, e.g. {@code "HIGH"}
   */
  public String wireValue() {
    return name();
  }

  /**
   * Parses a {@link Confidence} from its wire form. Accepts either case so callers handling raw
   * model output that paraphrases the value (e.g. {@code "high"}) still parse correctly.
   *
   * @param value the wire-form value
   * @return the matching enum constant
   * @throws IllegalArgumentException if {@code value} is null, blank, or does not match a known
   *     level
   */
  public static Confidence fromWire(String value) {
    if (Strings.isBlank(value)) {
      throw new IllegalArgumentException("confidence value must not be blank");
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "low" -> LOW;
      case "medium" -> MEDIUM;
      case "high" -> HIGH;
      default -> throw new IllegalArgumentException("unknown confidence level: " + value);
    };
  }
}
