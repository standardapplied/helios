/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.schema;

import java.util.List;
import java.util.Map;

/**
 * The leaf checks of {@link SchemaValidator}: string (with enum), integer, number and boolean, plus
 * the wording every validation message shares.
 */
final class ScalarValidator {

  private ScalarValidator() {}

  static void validateString(Object value, JsonSchema schema, String path, List<String> errors) {
    if (!(value instanceof String s)) {
      errors.add(field(path) + "expected string, got " + describeType(value));
      return;
    }
    if (schema.enumValues() != null
        && !schema.enumValues().isEmpty()
        && !schema.enumValues().contains(s)) {
      errors.add(field(path) + "must be one of " + schema.enumValues() + ", got \"" + s + "\"");
    }
  }

  static void validateInteger(Object value, String path, List<String> errors) {
    switch (value) {
      case Integer _, Long _, Short _, Byte _ -> {}
      case Number n
          when n.doubleValue() == Math.floor(n.doubleValue())
              && !Double.isInfinite(n.doubleValue()) -> {}
      case Number n -> errors.add(field(path) + "expected integer, got non-integer number " + n);
      case null, default ->
          errors.add(field(path) + "expected integer, got " + describeType(value));
    }
  }

  static void validateNumber(Object value, String path, List<String> errors) {
    if (!(value instanceof Number)) {
      errors.add(field(path) + "expected number, got " + describeType(value));
    }
  }

  static void validateBoolean(Object value, String path, List<String> errors) {
    if (!(value instanceof Boolean)) {
      errors.add(field(path) + "expected boolean, got " + describeType(value));
    }
  }

  /** The message prefix naming the field at {@code path}; empty at the root. */
  static String field(String path) {
    return path.isEmpty() ? "" : "field '" + path + "' ";
  }

  /** The JSON type name of a parsed value, or the Java simple name for anything else. */
  static String describeType(Object value) {
    return switch (value) {
      case null -> "null";
      case Map<?, ?> _ -> "object";
      case List<?> _ -> "array";
      case String _ -> "string";
      case Boolean _ -> "boolean";
      case Number _ -> "number";
      default -> value.getClass().getSimpleName();
    };
  }
}
