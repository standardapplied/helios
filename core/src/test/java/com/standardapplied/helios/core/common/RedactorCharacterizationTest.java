/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the exact output and per-name counts, in encounter order, that {@link Redactor} produces for
 * overlapping and adjacent secrets, so a restructuring of the automaton cannot change either.
 */
class RedactorCharacterizationTest {

  private static Redactor redactor(List<String> namesAndValues) {
    var secrets = new LinkedHashMap<String, byte[]>();
    for (var i = 0; i < namesAndValues.size(); i += 2) {
      secrets.put(
          namesAndValues.get(i), namesAndValues.get(i + 1).getBytes(StandardCharsets.US_ASCII));
    }
    return Redactor.of(secrets);
  }

  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of(
            List.of("A", "aaaaaaaa", "B", "bbbbbbbb"),
            "aaaaaaaabbbbbbbb",
            "<redacted:A><redacted:B>",
            List.of(Map.entry("A", 1), Map.entry("B", 1))),
        Arguments.of(
            List.of("A", "aaaaaaaa", "B", "bbbbbbbb"),
            "bbbbbbbbaaaaaaaaaaaaaaaa",
            "<redacted:B><redacted:A><redacted:A>",
            List.of(Map.entry("B", 1), Map.entry("A", 2))),
        Arguments.of(
            List.of("A", "aaaaaaaa"),
            "aaaaaaaaaaaaaaaaaaa",
            "<redacted:A><redacted:A>aaa",
            List.of(Map.entry("A", 2))),
        Arguments.of(
            List.of("X", "abcdefgh", "Y", "efghijkl", "Z", "ijklmnop"),
            "abcdefghijklmnop",
            "<redacted:X><redacted:Z>",
            List.of(Map.entry("X", 1), Map.entry("Z", 1))),
        Arguments.of(
            List.of("Y", "efghijkl", "X", "abcdefgh", "Z", "ijklmnop"),
            "-abcdefghijklmnop-",
            "-<redacted:X><redacted:Z>-",
            List.of(Map.entry("X", 1), Map.entry("Z", 1))),
        Arguments.of(
            List.of("S", "abcdefgh", "L", "abcdefghijkl", "M", "defghijk"),
            "abcdefghijkl abcdefgh defghijk",
            "<redacted:L> <redacted:S> <redacted:M>",
            List.of(Map.entry("L", 1), Map.entry("S", 1), Map.entry("M", 1))),
        Arguments.of(
            List.of("IN", "cdefghij", "OUT", "abcdefghijkl"),
            "abcdefghijkl cdefghij",
            "<redacted:OUT> <redacted:IN>",
            List.of(Map.entry("OUT", 1), Map.entry("IN", 1))),
        Arguments.of(
            List.of("P", "abababab"),
            "ababababababababab",
            "<redacted:P><redacted:P>ab",
            List.of(Map.entry("P", 2))),
        Arguments.of(
            List.of("Q", "secret01", "R", "secret01"),
            "secret01secret01",
            "<redacted:Q><redacted:Q>",
            List.of(Map.entry("Q", 2))),
        Arguments.of(
            List.of("T", "tokentok", "U", "kentoken"),
            "tokentokentoken",
            "<redacted:T>entoken",
            List.of(Map.entry("T", 1))));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void redactsOverlappingAndAdjacentSecretsExactly(
      List<String> secrets, String input, String text, List<Map.Entry<String, Integer>> counts) {
    var result = redactor(secrets).redact(input);
    assertEquals(text, result.text());
    assertEquals(counts, List.copyOf(result.counts().entrySet()));
  }
}
