/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import java.util.Map;

/**
 * JSON text for single values: RFC 8259 string escaping, finite numbers in their canonical Java
 * form, and objects whose values are written as booleans, numbers or strings.
 */
final class JsonText {

  private JsonText() {}

  static String quote(String s) {
    var sb = new StringBuilder(s.length() + 2).append('"');
    for (var i = 0; i < s.length(); i++) {
      var c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
      }
    }
    return sb.append('"').toString();
  }

  static String number(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("Cannot encode non-finite number: " + value);
    }
    return String.valueOf(value);
  }

  static String array(double[] values) {
    var sb = new StringBuilder("[");
    for (var i = 0; i < values.length; i++) {
      if (Double.isNaN(values[i]) || Double.isInfinite(values[i])) {
        throw new IllegalArgumentException("Cannot encode non-finite number at index " + i);
      }
      sb.append(i > 0 ? "," : "").append(values[i]);
    }
    return sb.append(']').toString();
  }

  /**
   * An object of {@code map}'s entries in iteration order. A non-finite number becomes a string; a
   * value that is not a boolean or number is written as the string of its {@code toString()}.
   */
  static String object(Map<String, ?> map) {
    var sb = new StringBuilder("{");
    for (var entry : map.entrySet()) {
      sb.append(sb.length() > 1 ? "," : "").append(quote(entry.getKey())).append(':');
      sb.append(value(entry.getValue()));
    }
    return sb.append('}').toString();
  }

  private static String value(Object value) {
    return switch (value) {
      case null -> "null";
      case Boolean b -> b.toString();
      case Number n when Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue()) ->
          quote(n.toString());
      case Number n -> n.toString();
      default -> quote(value.toString());
    };
  }
}
