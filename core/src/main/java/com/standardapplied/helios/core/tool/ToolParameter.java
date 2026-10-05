/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.tool;

import com.standardapplied.helios.core.schema.SchemaGenerator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Definition of a tool parameter (JSON Schema style).
 *
 * <p>The {@code items} field is the legacy hand-rolled item-schema descriptor for arrays of
 * primitive types ({@code List<String>}, {@code List<Integer>}). For arrays whose elements are
 * record-shaped POJOs, use {@code itemsClass} instead — the schema is derived at build time via
 * {@link com.standardapplied.helios.core.schema.SchemaGenerator} so callers don't hand-roll JSON
 * Schema for objects with nested fields. {@code itemsClass} takes precedence over {@code items}
 * when both are set.
 *
 * @param name the parameter name
 * @param description description of the parameter for the model
 * @param type the JSON Schema type
 * @param required whether the parameter is required
 * @param defaultValue optional default value
 * @param items for ARRAY type with primitive elements, the schema of array items
 * @param itemsClass for ARRAY type with record-shaped elements, the Java class whose JSON Schema is
 *     derived via {@code SchemaGenerator} and emitted as the {@code items} schema
 */
public record ToolParameter(
    String name,
    String description,
    ParameterType type,
    boolean required,
    Object defaultValue,
    ToolParameter items,
    Class<?> itemsClass) {

  public static Builder newBuilder() {
    return new Builder();
  }

  Map<String, Object> jsonSchema() {
    var schema = typeAndDescription();
    if (defaultValue != null) {
      schema.put("default", defaultValue);
    }
    if (type == ParameterType.ARRAY) {
      schema.put("items", itemsSchema());
    }
    return schema;
  }

  private LinkedHashMap<String, Object> typeAndDescription() {
    var schema = new LinkedHashMap<String, Object>();
    schema.put("type", type.jsonType());
    if (description != null) {
      schema.put("description", description);
    }
    return schema;
  }

  /**
   * Record-shaped items derive their schema from the record. An array without a declared item shape
   * gets a permissive object item, because provider APIs (Gemini in particular) reject an array
   * property with no {@code items} schema.
   */
  private Map<String, Object> itemsSchema() {
    if (itemsClass != null) {
      return SchemaGenerator.generate(itemsClass).toMap();
    }
    if (items != null) {
      return items.typeAndDescription();
    }
    var fallback = new LinkedHashMap<String, Object>();
    fallback.put("type", "object");
    return fallback;
  }

  public static class Builder {
    private String name;
    private String description;
    private ParameterType type = ParameterType.STRING;
    private boolean required = false;
    private Object defaultValue;
    private ToolParameter items;
    private Class<?> itemsClass;

    private Builder() {}

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withType(ParameterType type) {
      this.type = type;
      return this;
    }

    public Builder withRequired(boolean required) {
      this.required = required;
      return this;
    }

    public Builder withDefaultValue(Object defaultValue) {
      this.defaultValue = defaultValue;
      return this;
    }

    public Builder withItems(ToolParameter items) {
      this.items = items;
      return this;
    }

    /**
     * Declare the items class for an {@link ParameterType#ARRAY ARRAY} parameter whose elements are
     * a record-shaped POJO. The items JSON Schema is derived via {@link
     * com.standardapplied.helios.core.schema.SchemaGenerator} at request-build time, so the tool
     * author doesn't hand-roll a {@code properties} map for each field.
     *
     * @param itemsClass the record class; non-null
     * @return this builder
     */
    public Builder withItemsClass(Class<?> itemsClass) {
      this.itemsClass = itemsClass;
      return this;
    }

    public ToolParameter build() {
      return new ToolParameter(name, description, type, required, defaultValue, items, itemsClass);
    }
  }
}
