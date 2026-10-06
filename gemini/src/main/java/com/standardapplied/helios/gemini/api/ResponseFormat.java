/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * The {@code response_format} field of an {@link InteractionRequest}: structured text, JSON
 * constrained by a JSON Schema. It replaces the legacy raw-schema map and the removed {@code
 * response_mime_type} field.
 *
 * @param type the response-format discriminator, {@code text}
 * @param mimeType MIME type of the response, {@code application/json}
 * @param schema JSON Schema constraining the structured text
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseFormat(
    String type, @JsonProperty("mime_type") String mimeType, Map<String, Object> schema) {

  /** JSON output constrained by the supplied JSON Schema. */
  public static ResponseFormat json(Map<String, Object> schema) {
    if (schema == null) {
      throw new IllegalArgumentException("schema is required for JSON response format");
    }
    return new ResponseFormat("text", "application/json", schema);
  }
}
