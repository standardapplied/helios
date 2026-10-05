/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable byte-level redactor that replaces every contiguous occurrence of a registered secret
 * value with the marker {@code <redacted:NAME>}.
 *
 * <p>Backed by an Aho-Corasick automaton; matching cost is {@code O(input.length + matches)},
 * independent of the number of registered secrets. Operates on raw bytes <em>before</em> UTF-8
 * decoding so a registered secret cannot survive in the output regardless of how the surrounding
 * text is encoded.
 *
 * <p>Overlapping matches are resolved leftmost-longest: when two matches overlap, the one starting
 * earlier wins; ties are broken by preferring the longer match.
 *
 * <p>If two distinct names register the same byte value, redactions are attributed to the
 * first-registered name. This is deliberate — the underlying byte sequence is identical and a
 * single marker is the truthful representation.
 *
 * <p>Obtain instances via {@link SecretRegistry#redactor()} or {@link #of(Map)}.
 */
public final class Redactor {

  private static final byte[] MARKER_PREFIX = "<redacted:".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] MARKER_SUFFIX = ">".getBytes(StandardCharsets.US_ASCII);
  private static final Redactor EMPTY = new Redactor(List.of(), AhoCorasick.build(List.of()));

  private final List<String> patternNames;
  private final AhoCorasick automaton;

  private Redactor(List<String> patternNames, AhoCorasick automaton) {
    this.patternNames = patternNames;
    this.automaton = automaton;
  }

  /** Build a redactor from a name-to-bytes map. Bytes must be pure ASCII. */
  public static Redactor of(Map<String, byte[]> secrets) {
    if (secrets == null || secrets.isEmpty()) {
      return EMPTY;
    }
    var names = new ArrayList<String>(secrets.size());
    var patterns = new ArrayList<byte[]>(secrets.size());
    for (var entry : secrets.entrySet()) {
      requireAscii(entry.getKey(), entry.getValue());
      names.add(entry.getKey());
      patterns.add(entry.getValue().clone());
    }
    return new Redactor(List.copyOf(names), AhoCorasick.build(patterns));
  }

  private static void requireAscii(String name, byte[] bytes) {
    for (var b : bytes) {
      if ((b & 0xFF) > 0x7F) {
        throw new IllegalArgumentException(
            "Secret '%s' contains a non-ASCII byte; refuse at registration".formatted(name));
      }
    }
  }

  /**
   * Redact registered secrets from the supplied bytes. The input array is not mutated; the returned
   * array is freshly allocated.
   *
   * @param input bytes to scan; null treated as empty
   * @return result containing the redacted bytes plus per-secret-name match counts
   */
  public RedactionResult redact(byte[] input) {
    if (input == null || input.length == 0 || patternNames.isEmpty()) {
      return new RedactionResult(input == null ? new byte[0] : input.clone(), Map.of());
    }
    var matches = findMatches(input);
    if (matches.isEmpty()) {
      return new RedactionResult(input.clone(), Map.of());
    }
    return splice(input, matches);
  }

  /**
   * Convenience: encode {@code input} as UTF-8, redact, and return the result. Identical to {@code
   * redact(input.getBytes(UTF_8))}.
   */
  public RedactionResult redact(String input) {
    if (input == null) {
      return new RedactionResult(new byte[0], Map.of());
    }
    return redact(input.getBytes(StandardCharsets.UTF_8));
  }

  /** Number of registered patterns this redactor matches against. */
  public int patternCount() {
    return patternNames.size();
  }

  private List<Match> findMatches(byte[] input) {
    var matches = new ArrayList<Match>();
    var state = 0;
    for (var i = 0; i < input.length; i++) {
      var b = input[i] & 0xFF;
      if (b > 0x7F) {
        state = 0;
        continue;
      }
      state = automaton.next(state, b);
      var end = i + 1;
      automaton.forEachMatch(
          state, pid -> matches.add(new Match(end - automaton.patternLength(pid), end, pid)));
    }
    return matches;
  }

  private RedactionResult splice(byte[] input, List<Match> matches) {
    var out = new ByteArrayOutputStream(input.length);
    var counts = new LinkedHashMap<String, Integer>();
    var pos = 0;
    for (var m : leftmostLongest(matches)) {
      var name = patternNames.get(m.patternId);
      out.write(input, pos, m.start - pos);
      out.write(MARKER_PREFIX, 0, MARKER_PREFIX.length);
      var nameBytes = name.getBytes(StandardCharsets.US_ASCII);
      out.write(nameBytes, 0, nameBytes.length);
      out.write(MARKER_SUFFIX, 0, MARKER_SUFFIX.length);
      counts.merge(name, 1, Integer::sum);
      pos = m.end;
    }
    out.write(input, pos, input.length - pos);
    return new RedactionResult(out.toByteArray(), Collections.unmodifiableMap(counts));
  }

  private static List<Match> leftmostLongest(List<Match> matches) {
    matches.sort(
        Comparator.comparingInt(Match::start).thenComparing(Match::end, Comparator.reverseOrder()));
    var chosen = new ArrayList<Match>();
    var cursor = 0;
    for (var m : matches) {
      if (m.start >= cursor) {
        chosen.add(m);
        cursor = m.end;
      }
    }
    return chosen;
  }

  private record Match(int start, int end, int patternId) {}
}
