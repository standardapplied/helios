/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output wrapper carrying per-field provenance. The {@code output} payload stays clean; provenance
 * lives in a parallel sidecar list keyed by field name.
 *
 * <p>Mirrors the {@code { output, basis }} shape of parallel.ai's Task API Basis framework, with
 * the family renamed to "provenance" throughout the Helios surface.
 *
 * <p>Use {@link ai.singlr.core.schema.OutputSchema#provenancedOf(Class)} to derive a schema that
 * tells the model how to populate this wrapper, and {@link ProvenanceValidator} to enforce
 * calibration rules (default: {@link Confidence#MEDIUM} or {@link Confidence#HIGH} requires
 * citations).
 *
 * @param <T> the underlying output payload type
 * @param output the structured output payload
 * @param provenance one entry per top-level field of {@code output}
 */
public record Provenanced<T>(T output, List<FieldProvenance> provenance) {

  public Provenanced {
    if (output == null) {
      throw new IllegalArgumentException("output must not be null");
    }
    if (provenance != null) {
      for (var entry : provenance) {
        if (entry == null) {
          throw new IllegalArgumentException("provenance must not contain null");
        }
      }
    }
    provenance = provenance == null ? List.of() : List.copyOf(provenance);
  }

  /**
   * Look up the provenance entry for a given output field name.
   *
   * @param field the output field name
   * @return the matching {@link FieldProvenance}, or {@code null} if none exists
   */
  public FieldProvenance forField(String field) {
    if (field == null) {
      return null;
    }
    for (var entry : provenance) {
      if (field.equals(entry.field())) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Returns provenance entries indexed by field name. When two entries share a field name (which
   * the schema-level validator would normally reject) the last one wins, matching {@code
   * Map.of}-style "last write" semantics.
   *
   * @return an immutable map keyed by field name
   */
  public Map<String, FieldProvenance> provenanceByField() {
    var map = new LinkedHashMap<String, FieldProvenance>();
    for (var entry : provenance) {
      map.put(entry.field(), entry);
    }
    return Map.copyOf(map);
  }
}
