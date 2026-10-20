/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** String utilities for template rendering. */
public final class Strings {

  private Strings() {}

  /**
   * Renders a template by replacing {placeholder} with values from the map.
   *
   * @param template the template string with {placeholder} markers
   * @param values the values to substitute
   * @return the rendered string
   */
  public static String render(String template, Map<String, String> values) {
    if (template == null || values == null || values.isEmpty()) {
      return template;
    }
    var result = template;
    for (var entry : values.entrySet()) {
      result = result.replace("{" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }

  /**
   * Renders a template with validation of allowed placeholders. Only placeholders in the allowed
   * set will be replaced.
   *
   * @param template the template string
   * @param values the values to substitute
   * @param allowedKeys the set of allowed placeholder names
   * @return the rendered string
   */
  public static String renderSafe(
      String template, Map<String, String> values, Set<String> allowedKeys) {
    if (template == null || values == null || values.isEmpty()) {
      return template;
    }
    var result = template;
    for (var entry : values.entrySet()) {
      if (allowedKeys.contains(entry.getKey())) {
        result = result.replace("{" + entry.getKey() + "}", entry.getValue());
      }
    }
    return result;
  }

  /** Checks if a string is null or empty. */
  public static boolean isEmpty(String s) {
    return s == null || s.isEmpty();
  }

  /** Checks if a string is null, empty, or contains only whitespace. */
  public static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Returns the string if not blank, otherwise returns the default value. */
  public static String orDefault(String s, String defaultValue) {
    return isBlank(s) ? defaultValue : s;
  }

  /**
   * {@code text} cut to at most {@code maxChars} characters, never inside a surrogate pair, then
   * {@code note} formatted with how many were cut; {@code text} itself when it is no longer. Redact
   * a text before cutting it, so a secret the cut falls across is never partly shown.
   *
   * @param text the text; non-null
   * @param maxChars the characters kept at most; positive
   * @param note the note's format, its one {@code %d} the number of characters cut; non-null
   * @return the bounded text
   * @throws IllegalArgumentException if {@code maxChars} is not positive
   * @throws NullPointerException if {@code text} or {@code note} is null
   */
  public static String truncated(String text, int maxChars, String note) {
    if (maxChars <= 0) {
      throw new IllegalArgumentException("maxChars must be positive: " + maxChars);
    }
    Objects.requireNonNull(note, "note must not be null");
    if (text.length() <= maxChars) {
      return text;
    }
    var end =
        Character.isSurrogatePair(text.charAt(maxChars - 1), text.charAt(maxChars))
            ? maxChars - 1
            : maxChars;
    return text.substring(0, end) + note.formatted(text.length() - end);
  }
}
