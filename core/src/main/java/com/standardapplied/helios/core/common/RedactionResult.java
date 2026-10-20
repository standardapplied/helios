/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

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
   * This result with its text cut to at most {@code maxChars} characters, never inside a surrogate
   * pair, then {@code note} formatted with how many were cut; this result itself when the text is
   * no longer. A secret the cut falls across was redacted before the cut, so no part of it is
   * shown. The counts are kept.
   *
   * @param maxChars the characters kept at most; positive
   * @param note the note's format, its one {@code %d} the number of characters cut; non-null
   * @return the bounded result
   * @throws IllegalArgumentException if {@code maxChars} is not positive
   * @throws NullPointerException if {@code note} is null
   */
  public RedactionResult truncated(int maxChars, String note) {
    if (maxChars <= 0) {
      throw new IllegalArgumentException("maxChars must be positive: " + maxChars);
    }
    Objects.requireNonNull(note, "note must not be null");
    var text = text();
    if (text.length() <= maxChars) {
      return this;
    }
    var end =
        Character.isSurrogatePair(text.charAt(maxChars - 1), text.charAt(maxChars))
            ? maxChars - 1
            : maxChars;
    var cut = text.substring(0, end) + note.formatted(text.length() - end);
    return new RedactionResult(cut.getBytes(StandardCharsets.UTF_8), counts);
  }
}
