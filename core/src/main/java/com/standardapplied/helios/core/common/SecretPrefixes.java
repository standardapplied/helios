/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import java.util.Arrays;
import java.util.List;

/**
 * Finds where output cut at a size cap must end so that it keeps no part of a secret the cut went
 * through: the search behind {@link Redactor#trimPartialSecret(byte[])}.
 *
 * @param secrets the registered secret values, ASCII bytes
 */
record SecretPrefixes(List<byte[]> secrets) {

  /**
   * The length of {@code input} without its longest suffix that is a non-empty proper prefix of a
   * secret, then without every complete secret that this shorter end cuts through. A suffix within
   * a complete secret at the end is kept when no other secret overlaps that secret's start: the
   * redaction pass then replaces it whole.
   */
  int keptLength(byte[] input) {
    var kept = input.length - partialLength(input);
    for (var start = crossingStart(input, kept); start < kept; start = crossingStart(input, kept)) {
      kept = start;
    }
    return kept;
  }

  private int partialLength(byte[] input) {
    var end = input.length;
    var longest = 0;
    var complete = 0;
    for (var secret : secrets) {
      if (secret.length <= end && matchesAt(input, end - secret.length, secret, secret.length)) {
        complete = Math.max(complete, secret.length);
      }
      for (var k = Math.min(secret.length - 1, end); k > longest; k--) {
        if (matchesAt(input, end - k, secret, k)) {
          longest = k;
        }
      }
    }
    var redactedWhole =
        complete >= longest && crossingStart(input, end - complete) == end - complete;
    return redactedWhole ? 0 : longest;
  }

  private int crossingStart(byte[] input, int cut) {
    var earliest = cut;
    for (var secret : secrets) {
      var last = input.length - secret.length;
      for (var p = Math.max(0, cut - secret.length + 1); p < earliest && p <= last; p++) {
        if (matchesAt(input, p, secret, secret.length)) {
          earliest = p;
        }
      }
    }
    return earliest;
  }

  private static boolean matchesAt(byte[] input, int from, byte[] secret, int length) {
    return Arrays.equals(input, from, from + length, secret, 0, length);
  }
}
