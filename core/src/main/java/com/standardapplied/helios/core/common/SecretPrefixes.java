/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import java.util.Arrays;
import java.util.List;

/**
 * Finds where output cut at a size cap must end so that it ends in no proper prefix of a registered
 * secret: the search behind {@link Redactor#trimPartialSecret(byte[])}.
 *
 * @param secrets the registered secret values, ASCII bytes
 */
record SecretPrefixes(List<byte[]> secrets) {

  /**
   * The length of {@code input} without its longest suffix that is a non-empty proper prefix of a
   * secret, repeated until what is left ends in none. A suffix within a complete secret at the end
   * is kept: the redaction pass replaces that secret whole.
   */
  int keptLength(byte[] input) {
    var end = input.length;
    for (var cut = longestAt(input, end); cut > 0; cut = longestAt(input, end)) {
      end -= cut;
    }
    return end;
  }

  private int longestAt(byte[] input, int end) {
    var longest = 0;
    var complete = 0;
    for (var secret : secrets) {
      if (secret.length <= end && endsWith(input, end, secret, secret.length)) {
        complete = Math.max(complete, secret.length);
      }
      for (var k = Math.min(secret.length - 1, end); k > longest; k--) {
        if (endsWith(input, end, secret, k)) {
          longest = k;
        }
      }
    }
    return longest > complete ? longest : 0;
  }

  private static boolean endsWith(byte[] input, int end, byte[] secret, int length) {
    return Arrays.equals(input, end - length, end, secret, 0, length);
  }
}
