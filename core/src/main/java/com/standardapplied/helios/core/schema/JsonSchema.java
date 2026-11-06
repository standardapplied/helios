/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.schema;

import com.standardapplied.helios.core.common.OrderedMaps;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Represents a JSON Schema for structured output validation.
 *
 * <p>Every component but {@code type} is optional: {@code null} omits the keyword from {@link
 * #toMap()}, as does an empty {@code properties}, {@code required} or {@code enumValues}.
 *
 * @param type the JSON type (object, array, string, number, integer, boolean)
 * @param properties property schemas for object types, in the order they are sent to a model;
 *     stored as an unmodifiable copy in the given order; a null name or schema is rejected; {@code
 *     null} for a schema with no properties
 * @param items item schema for array types; {@code null} otherwise
 * @param required list of required property names; {@code null} when none is required
 * @param enumValues allowed values for enum types; {@code null} when the value is unconstrained
 * @param description optional description of the schema, read by the model; may be {@code null}
 * @param format optional format hint (e.g., "date-time", "email"); may be {@code null}
 * @param additionalProperties schema for additional properties in Map-based objects; {@code null}
 *     otherwise
 */
public record JsonSchema(
    String type,
    SequencedMap<String, JsonSchema> properties,
    JsonSchema items,
    List<String> required,
    List<String> enumValues,
    String description,
    String format,
    JsonSchema additionalProperties) {

  /**
   * Canonical constructor; copies a non-null {@code properties} into an unmodifiable map in its
   * iteration order. Other components are stored as given.
   *
   * @throws NullPointerException if {@code properties} holds a {@code null} name or schema
   */
  public JsonSchema {
    if (properties != null) {
      properties = OrderedMaps.copyOf(properties);
    }
  }

  /**
   * Returns a schema accepting any JSON string.
   *
   * @return a {@code string} schema with no description or format
   */
  public static JsonSchema string() {
    return new JsonSchema("string", null, null, null, null, null, null, null);
  }

  /**
   * Returns a schema accepting any JSON string, described for the model.
   *
   * @param description what the string holds; {@code null} omits the description
   * @return a {@code string} schema carrying {@code description}
   */
  public static JsonSchema string(String description) {
    return new JsonSchema("string", null, null, null, null, description, null, null);
  }

  /**
   * Returns a schema accepting a JSON number with no fractional part.
   *
   * @return an {@code integer} schema
   */
  public static JsonSchema integer() {
    return new JsonSchema("integer", null, null, null, null, null, null, null);
  }

  /**
   * Returns a schema accepting any JSON number, integral or fractional.
   *
   * @return a {@code number} schema
   */
  public static JsonSchema number() {
    return new JsonSchema("number", null, null, null, null, null, null, null);
  }

  /**
   * Returns a schema accepting {@code true} or {@code false}.
   *
   * @return a {@code boolean} schema
   */
  public static JsonSchema bool() {
    return new JsonSchema("boolean", null, null, null, null, null, null, null);
  }

  /**
   * Returns a schema accepting a JSON array whose every element matches {@code items}.
   *
   * @param items the element schema; {@code null} leaves the elements unconstrained
   * @return an {@code array} schema
   */
  public static JsonSchema array(JsonSchema items) {
    return new JsonSchema("array", null, items, null, null, null, null, null);
  }

  /**
   * Returns a schema accepting only one of the given strings.
   *
   * @param values the allowed values, in the order they are sent; stored as given; {@code null} or
   *     empty leaves the string unconstrained
   * @return a {@code string} schema with an {@code enum} constraint
   */
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

  /**
   * Returns a builder for an {@code object} schema with no properties yet.
   *
   * @return a new builder
   */
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
    putIfPresent(map, "properties", propertiesMap());
    putIfPresent(map, "items", mapOf(items));
    putIfPresent(map, "required", nonEmpty(required));
    putIfPresent(map, "enum", nonEmpty(enumValues));
    putIfPresent(map, "description", description);
    putIfPresent(map, "format", format);
    putIfPresent(map, "additionalProperties", mapOf(additionalProperties));
    return map;
  }

  private Map<String, Object> propertiesMap() {
    if (properties == null || properties.isEmpty()) {
      return null;
    }
    var propsMap = new LinkedHashMap<String, Object>();
    for (var entry : properties.entrySet()) {
      propsMap.put(entry.getKey(), entry.getValue().toMap());
    }
    return propsMap;
  }

  private static Map<String, Object> mapOf(JsonSchema schema) {
    return schema == null ? null : schema.toMap();
  }

  private static <C extends Collection<?>> C nonEmpty(C values) {
    return values == null || values.isEmpty() ? null : values;
  }

  private static void putIfPresent(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  /**
   * Builds an {@code object} {@link JsonSchema} property by property; not thread-safe, so confine
   * one builder to a thread.
   */
  public static class Builder {
    private final SequencedMap<String, JsonSchema> properties = new LinkedHashMap<>();
    private final LinkedHashSet<String> required = new LinkedHashSet<>();
    private String description;

    private Builder() {}

    /**
     * Adds an optional property. Properties keep the order they are added in; adding a name again
     * replaces its schema in its original position.
     *
     * @param name the property name; must not be {@code null}
     * @param schema the property's schema; must not be {@code null}
     * @return this builder
     */
    public Builder withProperty(String name, JsonSchema schema) {
      properties.put(name, schema);
      return this;
    }

    /**
     * Adds a property, required or optional. Properties keep the order they are added in; adding a
     * name again replaces its schema, and never makes a required name optional.
     *
     * @param name the property name; must not be {@code null}
     * @param schema the property's schema; must not be {@code null}
     * @param isRequired whether the property must be present
     * @return this builder
     */
    public Builder withProperty(String name, JsonSchema schema, boolean isRequired) {
      properties.put(name, schema);
      if (isRequired) {
        required.add(name);
      }
      return this;
    }

    /**
     * Marks names as required, in addition to any already required; a name already required keeps
     * its position. A name need not be a property added so far.
     *
     * @param names the required property names; none may be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code names} or one of them is {@code null}
     */
    public Builder withRequired(String... names) {
      required.addAll(List.of(names));
      return this;
    }

    /**
     * Sets the object's description, read by the model. Unset by default.
     *
     * @param description the description; {@code null} omits it
     * @return this builder
     */
    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    /**
     * Builds the object schema; with no required name its {@code required} is {@code null}.
     *
     * @return a new {@code object} schema holding this builder's properties
     * @throws NullPointerException if a property name or schema is {@code null}
     */
    public JsonSchema build() {
      return new JsonSchema(
          "object",
          properties,
          null,
          required.isEmpty() ? null : List.copyOf(required.stream().toList()),
          null,
          description,
          null,
          null);
    }
  }
}
