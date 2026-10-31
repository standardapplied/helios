/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable redactor that replaces every occurrence of a registered secret value with the marker
 * {@code <redacted:NAME>}.
 *
 * <p>A plain scan of the text for each value. Registered values are ASCII, so a value matches the
 * same places in a {@link String} as in its UTF-8 bytes. Text with no occurrence is returned as the
 * same {@link String}.
 *
 * <p>Overlapping occurrences, of one secret or of several, become one marker, so no character of
 * any secret survives. The marker names the first occurrence: the earliest start, the longest on an
 * equal start. Adjacent occurrences stay separate markers.
 *
 * <p>If two distinct names register the same value, redactions are attributed to the
 * first-registered name.
 *
 * <p>Obtain instances via {@link SecretRegistry#redactor()} or {@link #of(Map)}.
 */
public final class Redactor {

  private static final Comparator<Occurrence> FIRST_THEN_LONGEST =
      Comparator.comparingInt(Occurrence::start)
          .thenComparing(Occurrence::end, Comparator.reverseOrder());
  private static final Redactor EMPTY = new Redactor(Map.of());

  private final Map<String, String> namesByValue;
  private final SecretPrefixes prefixes;

  private Redactor(Map<String, String> namesByValue) {
    this.namesByValue = namesByValue;
    this.prefixes =
        new SecretPrefixes(
            namesByValue.keySet().stream()
                .map(value -> value.getBytes(StandardCharsets.US_ASCII))
                .toList());
  }

  /**
   * Build a redactor from a name-to-value map.
   *
   * @param secrets the secrets by name, in registration order; null or empty gives a redactor that
   *     changes nothing
   * @throws IllegalArgumentException if a value is null, empty or not pure ASCII
   */
  public static Redactor of(Map<String, String> secrets) {
    if (secrets == null || secrets.isEmpty()) {
      return EMPTY;
    }
    var namesByValue = new LinkedHashMap<String, String>();
    secrets.forEach(
        (name, value) -> {
          requireNonEmptyAscii(name, value);
          namesByValue.putIfAbsent(value, name);
        });
    return new Redactor(namesByValue);
  }

  private static void requireNonEmptyAscii(String name, String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Secret '%s' must not be empty".formatted(name));
    }
    if (value.chars().anyMatch(c -> c > 0x7F)) {
      throw new IllegalArgumentException(
          "Secret '%s' contains a non-ASCII character; refuse at registration".formatted(name));
    }
  }

  /**
   * Redact registered secrets from {@code input}.
   *
   * @param input text to scan; null treated as empty
   * @return the redacted text, {@code input} itself with no occurrence, and the markers per name
   */
  public RedactionResult redact(String input) {
    if (input == null) {
      return new RedactionResult("", Map.of());
    }
    var occurrences = occurrences(input);
    return occurrences.isEmpty()
        ? new RedactionResult(input, Map.of())
        : splice(input, occurrences);
  }

  /**
   * Drop the start of a secret that a size cap cut off. Returns {@code text} without its longest
   * suffix that is a non-empty proper prefix of a registered secret, or {@code text} unchanged when
   * it has none. Where secrets overlap, that shorter end can cut through a complete secret; it is
   * then dropped from its start too. Call it on the part of a cut output that is kept, before a
   * truncation marker is appended: the cut secret no longer matches in full, so {@link
   * #redact(String)} would leave its prefix. A complete secret at the end is not touched, even when
   * it ends in its own or another secret's prefix, so {@link #redact(String)} replaces it; unless
   * another secret overlaps its start; it is then dropped as well.
   *
   * @param text the kept part of a cut output; non-null
   * @return {@code text}, possibly shortened
   */
  public String trimPartialSecret(String text) {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    return text.substring(0, text.length() - (bytes.length - prefixes.keptLength(bytes)));
  }

  /**
   * The byte form of {@link #trimPartialSecret(String)}. The input array is not mutated.
   *
   * @param input the kept part of a cut output; non-null
   * @return the input without its trailing partial secret; a fresh array
   */
  public byte[] trimPartialSecret(byte[] input) {
    return Arrays.copyOf(input, prefixes.keptLength(input));
  }

  private List<Occurrence> occurrences(String input) {
    var found = new ArrayList<Occurrence>();
    namesByValue.forEach(
        (value, name) -> {
          var start = input.indexOf(value);
          while (start >= 0) {
            found.add(new Occurrence(start, start + value.length(), name));
            start = input.indexOf(value, start + 1);
          }
        });
    found.sort(FIRST_THEN_LONGEST);
    return found;
  }

  private static RedactionResult splice(String input, List<Occurrence> occurrences) {
    var redacted = new StringBuilder(input.length());
    var counts = new LinkedHashMap<String, Integer>();
    var spanEnd = 0;
    for (var occurrence : occurrences) {
      if (occurrence.start >= spanEnd) {
        redacted.append(input, spanEnd, occurrence.start);
        redacted.append("<redacted:").append(occurrence.name).append('>');
        counts.merge(occurrence.name, 1, Integer::sum);
      }
      spanEnd = Math.max(spanEnd, occurrence.end);
    }
    redacted.append(input, spanEnd, input.length());
    return new RedactionResult(redacted.toString(), counts);
  }

  private record Occurrence(int start, int end, String name) {}
}
