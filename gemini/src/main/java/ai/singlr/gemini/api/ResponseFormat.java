/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import ai.singlr.core.common.Strings;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Polymorphic {@code response_format} field on {@link InteractionRequest} ({@code Api-Revision:
 * 2026-05-20}).
 *
 * <p>Replaces the legacy raw-schema map and the removed {@code response_mime_type} field. The
 * discriminator is the {@link #type()}:
 *
 * <ul>
 *   <li>{@code text} — text output. Pass {@link #mimeType()} {@code application/json} together with
 *       {@link #schema()} for structured (JSON-Schema constrained) text.
 *   <li>{@code image} — image output. {@link #aspectRatio()} and {@link #imageSize()} are honored.
 * </ul>
 *
 * <p>Multiple modalities (text + audio etc.) are requested by passing an array of {@link
 * ResponseFormat} values as the request's {@code response_format} field.
 *
 * @param type the response-format discriminator
 * @param mimeType MIME type of the response (e.g. {@code application/json} for JSON-schema text,
 *     {@code image/jpeg} for image output)
 * @param schema JSON Schema constraining structured text output
 * @param aspectRatio aspect ratio for image output (e.g. {@code 1:1})
 * @param imageSize size hint for image output (e.g. {@code 1K})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseFormat(
    String type,
    @JsonProperty("mime_type") String mimeType,
    Map<String, Object> schema,
    @JsonProperty("aspect_ratio") String aspectRatio,
    @JsonProperty("image_size") String imageSize) {

  /** Plain text output (no schema, no explicit MIME). */
  public static ResponseFormat text() {
    return new ResponseFormat("text", null, null, null, null);
  }

  /** JSON output constrained by the supplied JSON Schema. */
  public static ResponseFormat json(Map<String, Object> schema) {
    if (schema == null) {
      throw new IllegalArgumentException("schema is required for JSON response format");
    }
    return new ResponseFormat("text", "application/json", schema, null, null);
  }

  /** Image output with the given MIME type and optional sizing hints. */
  public static ResponseFormat image(String mimeType, String aspectRatio, String imageSize) {
    if (Strings.isBlank(mimeType)) {
      throw new IllegalArgumentException("mimeType is required for image response format");
    }
    return new ResponseFormat("image", mimeType, null, aspectRatio, imageSize);
  }
}
