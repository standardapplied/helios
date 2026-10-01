/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.nio.charset.StandardCharsets;
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
}
