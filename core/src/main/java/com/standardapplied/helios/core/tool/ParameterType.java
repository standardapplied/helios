/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

/** JSON Schema types for tool parameters. */
public enum ParameterType {
  STRING("string"),
  INTEGER("integer"),
  NUMBER("number"),
  BOOLEAN("boolean"),
  ARRAY("array"),
  OBJECT("object");

  private final String jsonType;

  ParameterType(String jsonType) {
    this.jsonType = jsonType;
  }

  /** The JSON Schema type string. */
  public String jsonType() {
    return jsonType;
  }
}
