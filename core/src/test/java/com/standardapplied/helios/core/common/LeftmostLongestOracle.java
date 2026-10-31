/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The byte-level redaction {@link Redactor} replaced: UTF-8 encode, every match by naive comparison
 * at each position, keep the earliest and longest and drop what overlaps it, splice, decode. On
 * input with no overlapping occurrence its output is what {@link Redactor} must still produce.
 */
final class LeftmostLongestOracle {

  private LeftmostLongestOracle() {}

  record Match(int start, int end, String name) {}

  static RedactionResult redact(Map<String, String> secrets, String input) {
    var bytes = input.getBytes(StandardCharsets.UTF_8);
    var out = new ByteArrayOutputStream(bytes.length);
    var counts = new LinkedHashMap<String, Integer>();
    var pos = 0;
    for (var match : leftmostLongest(matches(secrets, input))) {
      out.write(bytes, pos, match.start() - pos);
      out.writeBytes(("<redacted:" + match.name() + ">").getBytes(StandardCharsets.US_ASCII));
      counts.merge(match.name(), 1, Integer::sum);
      pos = match.end();
    }
    out.write(bytes, pos, bytes.length - pos);
    return new RedactionResult(out.toString(StandardCharsets.UTF_8), counts);
  }

  /** Every occurrence of every value, in the order it was registered and then by position. */
  static List<Match> matches(Map<String, String> secrets, String input) {
    var bytes = input.getBytes(StandardCharsets.UTF_8);
    var matches = new ArrayList<Match>();
    secrets.forEach(
        (name, value) -> {
          var secret = value.getBytes(StandardCharsets.US_ASCII);
          for (var at = 0; at <= bytes.length - secret.length; at++) {
            if (Arrays.equals(bytes, at, at + secret.length, secret, 0, secret.length)) {
              matches.add(new Match(at, at + secret.length, name));
            }
          }
        });
    return matches;
  }

  /** True when any two occurrences share a position; adjacent ones do not. */
  static boolean anyOverlap(List<Match> matches) {
    var sorted = matches.stream().sorted(Comparator.comparingInt(Match::start)).toList();
    for (var i = 1; i < sorted.size(); i++) {
      if (sorted.get(i).start() < sorted.get(i - 1).end()) {
        return true;
      }
    }
    return false;
  }

  private static List<Match> leftmostLongest(List<Match> matches) {
    var sorted = new ArrayList<>(matches);
    sorted.sort(
        Comparator.comparingInt(Match::start).thenComparing(Match::end, Comparator.reverseOrder()));
    var chosen = new ArrayList<Match>();
    var cursor = 0;
    for (var match : sorted) {
      if (match.start() >= cursor) {
        chosen.add(match);
        cursor = match.end();
      }
    }
    return chosen;
  }
}
