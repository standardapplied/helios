/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of running text through a {@link Redactor}.
 *
 * @param bytes the redacted output bytes; never null. Caller owns the array.
 * @param counts per-secret-name redaction counts in encounter order; empty if no secrets matched
 */
public record RedactionResult(byte[] bytes, Map<String, Integer> counts) {

  /** Decode the redacted bytes as UTF-8. */
  public String text() {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  /** Total number of secrets redacted across all names. */
  public int totalRedactions() {
    var total = 0;
    for (var c : counts.values()) {
      total += c;
    }
    return total;
  }

  /**
   * Sum this result's per-name counts with {@code other}'s, for output redacted as two streams.
   *
   * @param other the result of redacting the other stream
   * @return unmodifiable counts in encounter order, this result's names first; empty if neither
   *     result redacted anything
   */
  public Map<String, Integer> mergeCounts(RedactionResult other) {
    if (counts.isEmpty() && other.counts.isEmpty()) {
      return Map.of();
    }
    var merged = new LinkedHashMap<String, Integer>(counts);
    other.counts.forEach((name, count) -> merged.merge(name, count, Integer::sum));
    return Collections.unmodifiableMap(merged);
  }
}
