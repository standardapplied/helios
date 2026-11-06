/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.tool;

/** JSON Schema types for tool parameters. */
public enum ParameterType {
  /** A JSON string; the default type of a {@link ToolParameter}. */
  STRING("string"),
  /** A JSON number with no fractional part. */
  INTEGER("integer"),
  /** Any JSON number, integral or fractional. */
  NUMBER("number"),
  /** JSON {@code true} or {@code false}. */
  BOOLEAN("boolean"),
  /**
   * A JSON array; its element schema comes from the parameter's {@code items} or {@code
   * itemsClass}.
   */
  ARRAY("array"),
  /** A JSON object, with no declared properties. */
  OBJECT("object");

  private final String jsonType;

  ParameterType(String jsonType) {
    this.jsonType = jsonType;
  }

  /**
   * The JSON Schema type string, the {@code type} keyword sent to the model.
   *
   * @return the lower-case keyword, such as {@code "string"} or {@code "array"}
   */
  public String jsonType() {
    return jsonType;
  }
}
