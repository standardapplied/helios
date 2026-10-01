/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Represents a JSON Schema for structured output validation.
 *
 * @param type the JSON type (object, array, string, number, integer, boolean)
 * @param properties property schemas for object types
 * @param items item schema for array types
 * @param required list of required property names
 * @param enumValues allowed values for enum types
 * @param description optional description of the schema
 * @param format optional format hint (e.g., "date-time", "email")
 * @param additionalProperties schema for additional properties in Map-based objects
 */
public record JsonSchema(
    String type,
    Map<String, JsonSchema> properties,
    JsonSchema items,
    List<String> required,
    List<String> enumValues,
    String description,
    String format,
    JsonSchema additionalProperties) {

  public static JsonSchema string() {
    return new JsonSchema("string", null, null, null, null, null, null, null);
  }

  public static JsonSchema string(String description) {
    return new JsonSchema("string", null, null, null, null, description, null, null);
  }

  public static JsonSchema integer() {
    return new JsonSchema("integer", null, null, null, null, null, null, null);
  }

  public static JsonSchema number() {
    return new JsonSchema("number", null, null, null, null, null, null, null);
  }

  public static JsonSchema bool() {
    return new JsonSchema("boolean", null, null, null, null, null, null, null);
  }

  public static JsonSchema array(JsonSchema items) {
    return new JsonSchema("array", null, items, null, null, null, null, null);
  }

  public static JsonSchema enumOf(List<String> values) {
    return new JsonSchema("string", null, null, null, values, null, null, null);
  }

  /**
   * Creates an object schema for Map types with a value type schema.
   *
   * @param valueSchema the schema for map values
   * @return an object schema with additionalProperties
   */
  public static JsonSchema map(JsonSchema valueSchema) {
    return new JsonSchema("object", null, null, null, null, null, null, valueSchema);
  }

  /**
   * Returns a copy of this schema with the given description.
   *
   * @param description the description to set
   * @return a new JsonSchema with the description
   */
  public JsonSchema withDescription(String description) {
    return new JsonSchema(
        type, properties, items, required, enumValues, description, format, additionalProperties);
  }

  public static Builder object() {
    return new Builder();
  }

  /**
   * Converts this schema to a Map suitable for JSON serialization.
   *
   * @return map representation of the schema
   */
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("type", type);

    if (properties != null && !properties.isEmpty()) {
      var propsMap = new LinkedHashMap<String, Object>();
      for (var entry : properties.entrySet()) {
        propsMap.put(entry.getKey(), entry.getValue().toMap());
      }
      map.put("properties", propsMap);
    }

    if (items != null) {
      map.put("items", items.toMap());
    }

    if (required != null && !required.isEmpty()) {
      map.put("required", required);
    }

    if (enumValues != null && !enumValues.isEmpty()) {
      map.put("enum", enumValues);
    }

    if (description != null) {
      map.put("description", description);
    }

    if (format != null) {
      map.put("format", format);
    }

    if (additionalProperties != null) {
      map.put("additionalProperties", additionalProperties.toMap());
    }

    return map;
  }

  public static class Builder {
    private final Map<String, JsonSchema> properties = new LinkedHashMap<>();
    private final LinkedHashSet<String> required = new LinkedHashSet<>();
    private String description;

    private Builder() {}

    public Builder withProperty(String name, JsonSchema schema) {
      properties.put(name, schema);
      return this;
    }

    public Builder withProperty(String name, JsonSchema schema, boolean isRequired) {
      properties.put(name, schema);
      if (isRequired) {
        required.add(name);
      }
      return this;
    }

    public Builder withRequired(String... names) {
      required.addAll(List.of(names));
      return this;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public JsonSchema build() {
      return new JsonSchema(
          "object",
          Map.copyOf(properties),
          null,
          required.isEmpty() ? null : List.copyOf(required.stream().toList()),
          null,
          description,
          null,
          null);
    }
  }
}
