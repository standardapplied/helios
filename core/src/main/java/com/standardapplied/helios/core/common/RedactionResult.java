/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

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

  /**
   * This result with its text cut after {@code maxChars} characters, never inside a surrogate pair,
   * and a note of how many were cut; this result itself when nothing would be cut. A secret the cut
   * falls across was redacted before the cut, so no part of it is shown. The counts are kept.
   *
   * @param maxChars the characters kept; positive
   * @return the bounded result
   * @throws IllegalArgumentException if {@code maxChars} is not positive
   */
  public RedactionResult truncated(int maxChars) {
    if (maxChars <= 0) {
      throw new IllegalArgumentException("maxChars must be positive: " + maxChars);
    }
    var text = text();
    var end =
        text.length() <= maxChars
            ? text.length()
            : text.offsetByCodePoints(0, text.codePointCount(0, maxChars));
    if (end == text.length()) {
      return this;
    }
    var cut = text.substring(0, end) + "… [" + (text.length() - end) + " more chars]";
    return new RedactionResult(cut.getBytes(StandardCharsets.UTF_8), counts);
  }
}
