/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
  private static final int ALPHABET = 128;
  private static final Redactor EMPTY =
      new Redactor(List.of(), new int[0], new int[0], new int[0], new int[0], new int[0]);

  private final List<String> patternNames;
  private final int[] patternLengths;
  private final int[] gotoChildren;
  private final int[] failure;
  private final int[] outputPatternId;
  private final int[] outputNext;

  private Redactor(
      List<String> patternNames,
      int[] patternLengths,
      int[] gotoChildren,
      int[] failure,
      int[] outputPatternId,
      int[] outputNext) {
    this.patternNames = patternNames;
    this.patternLengths = patternLengths;
    this.gotoChildren = gotoChildren;
    this.failure = failure;
    this.outputPatternId = outputPatternId;
    this.outputNext = outputNext;
  }

  /** Build a redactor from a name-to-bytes map. Bytes must be pure ASCII. */
  public static Redactor of(Map<String, byte[]> secrets) {
    if (secrets == null || secrets.isEmpty()) {
      return EMPTY;
    }
    var names = new ArrayList<String>(secrets.size());
    var patterns = new ArrayList<byte[]>(secrets.size());
    for (var entry : secrets.entrySet()) {
      var bytes = entry.getValue();
      for (var b : bytes) {
        if ((b & 0xFF) > 0x7F) {
          throw new IllegalArgumentException(
              "Secret '%s' contains a non-ASCII byte; refuse at registration"
                  .formatted(entry.getKey()));
        }
      }
      names.add(entry.getKey());
      patterns.add(bytes.clone());
    }
    return build(names, patterns);
  }

  private static Redactor build(List<String> names, List<byte[]> patterns) {
    var lengths = new int[patterns.size()];
    for (var i = 0; i < patterns.size(); i++) {
      lengths[i] = patterns.get(i).length;
    }
    var gotoTable = new ArrayList<int[]>();
    var output = new ArrayList<Integer>();
    gotoTable.add(newRow());
    output.add(-1);
    for (var pid = 0; pid < patterns.size(); pid++) {
      var pat = patterns.get(pid);
      var cur = 0;
      for (var b : pat) {
        var idx = b & 0x7F;
        var row = gotoTable.get(cur);
        var next = row[idx];
        if (next == -1) {
          next = gotoTable.size();
          gotoTable.add(newRow());
          output.add(-1);
          row[idx] = next;
        }
        cur = next;
      }
      if (output.get(cur) == -1) {
        output.set(cur, pid);
      }
    }
    var n = gotoTable.size();
    var fail = new int[n];
    var nextOut = new int[n];
    Arrays.fill(nextOut, -1);
    var bfs = new ArrayDeque<Integer>();
    var rootRow = gotoTable.get(0);
    for (var c = 0; c < ALPHABET; c++) {
      var child = rootRow[c];
      if (child != -1) {
        fail[child] = 0;
        bfs.add(child);
      }
    }
    while (!bfs.isEmpty()) {
      var u = bfs.poll();
      var uRow = gotoTable.get(u);
      for (var c = 0; c < ALPHABET; c++) {
        var v = uRow[c];
        if (v == -1) {
          continue;
        }
        var f = fail[u];
        while (f != 0 && gotoTable.get(f)[c] == -1) {
          f = fail[f];
        }
        var g = gotoTable.get(f)[c];
        fail[v] = g != -1 ? g : 0;
        var fo = fail[v];
        nextOut[v] = (output.get(fo) != -1) ? fo : nextOut[fo];
        bfs.add(v);
      }
    }
    var gotoFlat = new int[n * ALPHABET];
    for (var i = 0; i < n; i++) {
      System.arraycopy(gotoTable.get(i), 0, gotoFlat, i * ALPHABET, ALPHABET);
    }
    var outArr = new int[n];
    for (var i = 0; i < n; i++) {
      outArr[i] = output.get(i);
    }
    return new Redactor(List.copyOf(names), lengths, gotoFlat, fail, outArr, nextOut);
  }

  private static int[] newRow() {
    var row = new int[ALPHABET];
    Arrays.fill(row, -1);
    return row;
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
      while (state != 0 && gotoChildren[state * ALPHABET + b] == -1) {
        state = failure[state];
      }
      var g = gotoChildren[state * ALPHABET + b];
      if (g != -1) {
        state = g;
      }
      var pid = outputPatternId[state];
      if (pid != -1) {
        matches.add(new Match(i - patternLengths[pid] + 1, i + 1, pid));
      }
      var t = outputNext[state];
      while (t != -1) {
        var p = outputPatternId[t];
        matches.add(new Match(i - patternLengths[p] + 1, i + 1, p));
        t = outputNext[t];
      }
    }
    return matches;
  }

  private RedactionResult splice(byte[] input, List<Match> matches) {
    matches.sort(
        (a, b) -> {
          var c = Integer.compare(a.start, b.start);
          return c != 0 ? c : Integer.compare(b.end, a.end);
        });
    var chosen = new ArrayList<Match>();
    var cursor = 0;
    for (var m : matches) {
      if (m.start >= cursor) {
        chosen.add(m);
        cursor = m.end;
      }
    }
    var out = new ByteArrayOutputStream(input.length);
    var counts = new LinkedHashMap<String, Integer>();
    var pos = 0;
    for (var m : chosen) {
      out.write(input, pos, m.start - pos);
      out.write(MARKER_PREFIX, 0, MARKER_PREFIX.length);
      var nameBytes = patternNames.get(m.patternId).getBytes(StandardCharsets.US_ASCII);
      out.write(nameBytes, 0, nameBytes.length);
      out.write(MARKER_SUFFIX, 0, MARKER_SUFFIX.length);
      counts.merge(patternNames.get(m.patternId), 1, Integer::sum);
      pos = m.end;
    }
    out.write(input, pos, input.length - pos);
    return new RedactionResult(out.toByteArray(), Collections.unmodifiableMap(counts));
  }

  private record Match(int start, int end, int patternId) {}
}
