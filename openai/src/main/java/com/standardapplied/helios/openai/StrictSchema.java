/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.openai.api.ResponsesRequest;
import com.standardapplied.helios.openai.api.TextFormatConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * An output schema in the shape OpenAI's strict JSON-schema mode accepts. Strict mode requires
 * every object to set {@code additionalProperties: false}, so the transformation adds it
 * throughout. An open-keyed object — a {@code Map<String, X>}, whose {@code additionalProperties}
 * is a value schema — cannot be expressed that way, so a schema holding one is sent as is, in
 * non-strict mode, which keeps structured output without the strict validator. The transformation
 * keeps the order of every map, so properties reach the model in their declared order.
 */
final class StrictSchema {

  private StrictSchema() {}

  /** The text config asking for JSON matching {@code schema}. */
  static ResponsesRequest.TextConfig textConfig(Map<String, Object> schema) {
    var openMap = hasOpenMapShape(schema);
    var sent = openMap ? schema : addAdditionalPropertiesFalse(schema);
    return new ResponsesRequest.TextConfig(TextFormatConfig.jsonSchema("output", sent, !openMap));
  }

  /** Whether {@code schema} holds an open-keyed object anywhere. */
  static boolean hasOpenMapShape(Map<String, Object> schema) {
    if (schema == null) {
      return false;
    }
    if ("object".equals(schema.get("type"))
        && schema.get("additionalProperties") instanceof Map<?, ?>) {
      return true;
    }
    return children(schema).anyMatch(StrictSchema::hasOpenMapShape);
  }

  /**
   * {@code schema} with {@code additionalProperties: false} on every object; an open-keyed object's
   * value schema is transformed in place of being overwritten.
   */
  static Map<String, Object> addAdditionalPropertiesFalse(Map<String, Object> schema) {
    var result = new LinkedHashMap<>(schema);
    if ("object".equals(result.get("type"))) {
      result.put(
          "additionalProperties",
          result.get("additionalProperties") instanceof Map<?, ?> valueSchema
              ? addAdditionalPropertiesFalse(asSchema(valueSchema))
              : false);
      if (result.get("properties") instanceof Map<?, ?> properties) {
        result.put("properties", strictProperties(asSchema(properties)));
      }
    }
    if ("array".equals(result.get("type")) && result.get("items") instanceof Map<?, ?> items) {
      result.put("items", addAdditionalPropertiesFalse(asSchema(items)));
    }
    return result;
  }

  private static Map<String, Object> strictProperties(Map<String, Object> properties) {
    var strict = new LinkedHashMap<String, Object>();
    properties.forEach(
        (name, property) ->
            strict.put(
                name,
                property instanceof Map<?, ?> nested
                    ? addAdditionalPropertiesFalse(asSchema(nested))
                    : property));
    return strict;
  }

  /** The nested schemas of {@code schema}: its property schemas, items and value schema. */
  private static Stream<Map<String, Object>> children(Map<String, Object> schema) {
    var properties =
        schema.get("properties") instanceof Map<?, ?> map
            ? asSchema(map).values().stream()
            : Stream.empty();
    return Stream.concat(
            properties, Stream.of(schema.get("items"), schema.get("additionalProperties")))
        .filter(Map.class::isInstance)
        .map(child -> asSchema((Map<?, ?>) child));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asSchema(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }
}
