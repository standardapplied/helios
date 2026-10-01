/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

/**
 * Definition of a tool parameter (JSON Schema style).
 *
 * <p>The {@code items} field is the legacy hand-rolled item-schema descriptor for arrays of
 * primitive types ({@code List<String>}, {@code List<Integer>}). For arrays whose elements are
 * record-shaped POJOs, use {@code itemsClass} instead — the schema is derived at build time via
 * {@link ai.singlr.core.schema.SchemaGenerator} so callers don't hand-roll JSON Schema for objects
 * with nested fields. {@code itemsClass} takes precedence over {@code items} when both are set.
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
     * ai.singlr.core.schema.SchemaGenerator} at request-build time, so the tool author doesn't
     * hand-roll a {@code properties} map for each field.
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
