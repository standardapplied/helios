/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight {@link JsonSchema} validator that produces human-readable error messages suitable for
 * showing to a model. Implements a strict subset of JSON Schema sufficient for typed structured
 * output and {@code submit()} validation: object/array/string/integer/number/boolean types,
 * required properties, enum values, nested properties, array items, and {@code
 * additionalProperties}.
 *
 * <p>Errors are written model-first: every message names the failing field by JSON-pointer-ish path
 * and states what was expected, so the model can correct its next attempt without parsing schema
 * syntax.
 *
 * <p>Used by each provider's structured-output parse path ({@code parseStructuredContent} in {@code
 * AnthropicModel}, {@code GeminiModel}, {@code OpenAIModel}) which runs this against the
 * deserialized response Map before Jackson type-coerces; failures throw a {@link
 * StructuredOutputParseException} carrying the diff so the session loop can inject a corrective
 * USER message and retry instead of terminating.
 */
public final class SchemaValidator {

  private SchemaValidator() {}

  /**
   * Validate {@code value} against {@code schema}. Returns an empty list when validation passes;
   * otherwise a list of error messages, each one independently actionable.
   *
   * @param value the value to validate (typically a {@code Map} or {@code List} from a JSON parse)
   * @param schema the schema to validate against; {@code null} returns an empty list
   * @return per-error messages naming the failing field path and the expectation
   */
  public static List<String> validate(Object value, JsonSchema schema) {
    var errors = new ArrayList<String>();
    validate(value, schema, "", errors);
    return errors;
  }

  private static void validate(Object value, JsonSchema schema, String path, List<String> errors) {
    if (schema == null) {
      return;
    }
    var type = schema.type();
    if (type == null) {
      return;
    }
    switch (type) {
      case "object" -> validateObject(value, schema, path, errors);
      case "array" -> validateArray(value, schema, path, errors);
      case "string" -> validateString(value, schema, path, errors);
      case "integer" -> validateInteger(value, path, errors);
      case "number" -> validateNumber(value, path, errors);
      case "boolean" -> validateBoolean(value, path, errors);
      default -> {
        // Unknown schema types pass through — we only validate what we recognize.
      }
    }
  }

  private static void validateObject(
      Object value, JsonSchema schema, String path, List<String> errors) {
    if (!(value instanceof Map<?, ?> map)) {
      errors.add(field(path) + "expected object, got " + describeType(value));
      return;
    }
    if (schema.required() != null) {
      for (var name : schema.required()) {
        if (!map.containsKey(name) || map.get(name) == null) {
          errors.add(field(child(path, name)) + "is required but missing");
        }
      }
    }
    if (schema.properties() != null) {
      for (var entry : schema.properties().entrySet()) {
        var name = entry.getKey();
        if (map.containsKey(name) && map.get(name) != null) {
          validate(map.get(name), entry.getValue(), child(path, name), errors);
        }
      }
    }
    if (schema.additionalProperties() != null) {
      var declared = schema.properties() != null ? schema.properties().keySet() : List.<String>of();
      for (var entry : map.entrySet()) {
        var k = String.valueOf(entry.getKey());
        if (!declared.contains(k)) {
          validate(entry.getValue(), schema.additionalProperties(), child(path, k), errors);
        }
      }
    }
  }

  private static void validateArray(
      Object value, JsonSchema schema, String path, List<String> errors) {
    if (!(value instanceof List<?> list)) {
      errors.add(field(path) + "expected array, got " + describeType(value));
      return;
    }
    if (schema.items() != null) {
      for (int i = 0; i < list.size(); i++) {
        validate(list.get(i), schema.items(), path + "[" + i + "]", errors);
      }
    }
  }

  private static void validateString(
      Object value, JsonSchema schema, String path, List<String> errors) {
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

  private static void validateInteger(Object value, String path, List<String> errors) {
    if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte) {
      return;
    }
    if (value instanceof Number n) {
      var d = n.doubleValue();
      if (d == Math.floor(d) && !Double.isInfinite(d)) {
        return;
      }
      errors.add(field(path) + "expected integer, got non-integer number " + n);
      return;
    }
    errors.add(field(path) + "expected integer, got " + describeType(value));
  }

  private static void validateNumber(Object value, String path, List<String> errors) {
    if (!(value instanceof Number)) {
      errors.add(field(path) + "expected number, got " + describeType(value));
    }
  }

  private static void validateBoolean(Object value, String path, List<String> errors) {
    if (!(value instanceof Boolean)) {
      errors.add(field(path) + "expected boolean, got " + describeType(value));
    }
  }

  private static String child(String parent, String name) {
    return parent.isEmpty() ? name : parent + "." + name;
  }

  private static String field(String path) {
    return path.isEmpty() ? "" : "field '" + path + "' ";
  }

  private static String describeType(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Map) {
      return "object";
    }
    if (value instanceof List) {
      return "array";
    }
    if (value instanceof String) {
      return "string";
    }
    if (value instanceof Boolean) {
      return "boolean";
    }
    if (value instanceof Number) {
      return "number";
    }
    return value.getClass().getSimpleName();
  }
}
