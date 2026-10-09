/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.schema;

import static com.standardapplied.helios.core.schema.ScalarValidator.describeType;
import static com.standardapplied.helios.core.schema.ScalarValidator.field;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight {@link JsonSchema} validator that produces human-readable error messages suitable for
 * showing to a model. Implements a strict subset of JSON Schema sufficient for typed structured
 * output validation: object/array/string/integer/number/boolean types, required properties, enum
 * values, nested properties, array items, and {@code additionalProperties}.
 *
 * <p>Errors are written model-first: every message names the failing field by JSON-pointer-ish path
 * and states what was expected, so the model can correct its next attempt without parsing schema
 * syntax.
 *
 * <p>Used by every provider's structured-output parse path ({@link StructuredContentParser}, which
 * {@code core.provider.StreamingModel} runs on a drained turn) against the deserialized response
 * Map before Jackson type-coerces; failures throw a {@link StructuredOutputParseException} carrying
 * the diff so the session loop can inject a corrective USER message and retry instead of
 * terminating.
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
      case "string" -> ScalarValidator.validateString(value, schema, path, errors);
      case "integer" -> ScalarValidator.validateInteger(value, path, errors);
      case "number" -> ScalarValidator.validateNumber(value, path, errors);
      case "boolean" -> ScalarValidator.validateBoolean(value, path, errors);
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
    requireFields(map, schema, path, errors);
    validateDeclared(map, schema, path, errors);
    validateAdditional(map, schema, path, errors);
  }

  private static void requireFields(
      Map<?, ?> map, JsonSchema schema, String path, List<String> errors) {
    if (schema.required() == null) {
      return;
    }
    for (var name : schema.required()) {
      if (map.get(name) == null) {
        errors.add(field(child(path, name)) + "is required but missing");
      }
    }
  }

  private static void validateDeclared(
      Map<?, ?> map, JsonSchema schema, String path, List<String> errors) {
    if (schema.properties() == null) {
      return;
    }
    for (var entry : schema.properties().entrySet()) {
      var name = entry.getKey();
      if (map.get(name) != null) {
        validate(map.get(name), entry.getValue(), child(path, name), errors);
      }
    }
  }

  private static void validateAdditional(
      Map<?, ?> map, JsonSchema schema, String path, List<String> errors) {
    if (schema.additionalProperties() == null) {
      return;
    }
    var declared = schema.properties() != null ? schema.properties().keySet() : List.<String>of();
    for (var entry : map.entrySet()) {
      var k = String.valueOf(entry.getKey());
      if (!declared.contains(k)) {
        validate(entry.getValue(), schema.additionalProperties(), child(path, k), errors);
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

  private static String child(String parent, String name) {
    return parent.isEmpty() ? name : parent + "." + name;
  }
}
