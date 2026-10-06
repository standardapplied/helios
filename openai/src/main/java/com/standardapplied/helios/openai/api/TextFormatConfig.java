/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Text format configuration for structured output.
 *
 * <p>Used inside the {@code text.format} parameter to request JSON schema output.
 *
 * @param type format type: "json_schema" or "text"
 * @param name schema name (for type "json_schema")
 * @param schema the JSON Schema (for type "json_schema")
 * @param strict whether to enforce strict schema adherence (for type "json_schema")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TextFormatConfig(
    String type, String name, Map<String, Object> schema, Boolean strict) {

  /**
   * Build a {@code json_schema} format config with explicit {@code strict} mode.
   *
   * <p>Pass {@code false} when the schema contains an open-keyed object ({@code Map<String, X>}
   * with an unbounded key set). OpenAI's strict mode requires every {@code object} type to declare
   * {@code additionalProperties: false} and to list every property in {@code required} — open Maps
   * violate both constraints and the API rejects the request with HTTP 400.
   */
  public static TextFormatConfig jsonSchema(
      String name, Map<String, Object> schema, boolean strict) {
    return new TextFormatConfig("json_schema", name, schema, strict);
  }
}
