/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.util.Map;
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
}
