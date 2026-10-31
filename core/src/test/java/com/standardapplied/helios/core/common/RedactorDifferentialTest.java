/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Where no two occurrences overlap, the plain scan gives the same text and counts as the byte-level
 * redaction it replaced, on random text mixing ASCII with multibyte code points.
 */
class RedactorDifferentialTest {

  private static final int KEPT_CASES = 5_000;
  private static final String SECRET_ALPHABET = spaceThenPrintableAsciiWithoutJsonSpecials();
  private static final List<String> FRAGMENTS =
      List.of(
          "a", "Z", "7", " ", "\n", "{", "}", "[", "]", "\"", "'", ":", ",", "é", "ü", "日本語", "😀");

  private static String spaceThenPrintableAsciiWithoutJsonSpecials() {
    var alphabet = new StringBuilder();
    for (var c = ' '; c <= '~'; c++) {
      if (c != '"' && c != '\\') {
        alphabet.append(c);
      }
    }
    return alphabet.toString();
  }

  private static String randomSecret(Random random) {
    var length = 8 + random.nextInt(33);
    var secret = new StringBuilder(length);
    for (var i = 0; i < length; i++) {
      var firstAllowed = i == 0 || i == length - 1 ? 1 : 0;
      secret.append(SECRET_ALPHABET.charAt(random.nextInt(firstAllowed, SECRET_ALPHABET.length())));
    }
    return secret.toString();
  }

  private static String randomText(Random random, List<String> secrets) {
    var text = new StringBuilder();
    var pieces = random.nextInt(200);
    for (var i = 0; i < pieces; i++) {
      var secret = random.nextInt(10) == 0;
      text.append(
          secret
              ? secrets.get(random.nextInt(secrets.size()))
              : FRAGMENTS.get(random.nextInt(FRAGMENTS.size())));
    }
    return text.toString();
  }

  @Test
  void withNoOverlapThePlainScanMatchesTheByteLevelRedaction() {
    var random = new Random(0x5EC2E7L);
    var kept = 0;
    var redactions = 0;
    while (kept < KEPT_CASES) {
      var secrets = new LinkedHashMap<String, String>();
      var registry = new SecretRegistry();
      var count = 1 + random.nextInt(10);
      for (var i = 0; i < count; i++) {
        var value = randomSecret(random);
        secrets.put("S" + i, value);
        registry.register("S" + i, value);
      }
      var input = randomText(random, new ArrayList<>(secrets.values()));
      if (LeftmostLongestOracle.anyOverlap(LeftmostLongestOracle.matches(secrets, input))) {
        continue;
      }
      var expected = LeftmostLongestOracle.redact(secrets, input);
      var actual = registry.redactor().redact(input);
      assertEquals(expected.text(), actual.text(), input);
      assertEquals(entries(expected), entries(actual), input);
      redactions += expected.counts().size();
      kept++;
    }
    assertTrue(redactions > KEPT_CASES, "the cases must hold secrets: " + redactions);
  }

  private static List<Map.Entry<String, Integer>> entries(RedactionResult result) {
    return List.copyOf(result.counts().entrySet());
  }
}
