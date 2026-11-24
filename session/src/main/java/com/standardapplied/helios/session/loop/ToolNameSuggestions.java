/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session.loop;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The registered tool names closest to a name the model called that is not registered, so the
 * not-found result can name what the model could have called. Closeness is the Levenshtein
 * distance, without regard to case ({@link Locale#ROOT}); a name qualifies at a distance of at most
 * the larger of 2 and a third of the called name's length. A called name over 256 characters gets
 * no suggestion, which bounds the work a hostile name can buy at 256 × the registered names'
 * length.
 */
final class ToolNameSuggestions {

  private static final int MAX_CALLED_LENGTH = 256;
  private static final int MAX_SUGGESTIONS = 3;
  private static final int MIN_DISTANCE_LIMIT = 2;

  private record Scored(String name, int distance) {}

  private ToolNameSuggestions() {}

  /**
   * At most three of {@code registered}, nearest first, ties in {@code registered}'s order.
   *
   * @param called the name the model called; non-null
   * @param registered the registered names, in registration order; non-null
   * @return the qualifying names, empty when none qualifies or {@code called} is over 256
   *     characters
   */
  static List<String> closest(String called, List<String> registered) {
    if (called.length() > MAX_CALLED_LENGTH) {
      return List.of();
    }
    var target = called.toLowerCase(Locale.ROOT);
    var limit = Math.max(MIN_DISTANCE_LIMIT, called.length() / 3);
    return registered.stream()
        .map(name -> new Scored(name, distance(target, name.toLowerCase(Locale.ROOT))))
        .filter(scored -> scored.distance() <= limit)
        .sorted(Comparator.comparingInt(Scored::distance))
        .limit(MAX_SUGGESTIONS)
        .map(Scored::name)
        .toList();
  }

  private static int distance(String a, String b) {
    var previous = new int[b.length() + 1];
    var current = new int[b.length() + 1];
    for (var j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (var i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (var j = 1; j <= b.length(); j++) {
        var substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
      }
      var swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
