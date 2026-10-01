/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Content part within an input or output message item.
 *
 * <p>Represents text ({@code output_text} / {@code input_text}), an assistant refusal ({@code
 * refusal}), an inline image ({@code input_image}), or an attached file ({@code input_file}) in the
 * Responses API content arrays.
 *
 * @param type the content part type
 * @param text the text content (for {@code output_text} / {@code input_text} / {@code refusal})
 * @param imageUrl base64 data URI for {@code input_image} parts (e.g. {@code
 *     "data:image/png;base64,..."})
 * @param fileData base64 data URI carrying the file bytes for {@code input_file} parts
 * @param filename the original filename for {@code input_file} parts (optional)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(
    String type,
    String text,
    @JsonProperty("image_url") String imageUrl,
    @JsonProperty("file_data") String fileData,
    String filename) {

  public static ContentPart outputText(String text) {
    return new ContentPart("output_text", text, null, null, null);
  }

  public static ContentPart inputText(String text) {
    return new ContentPart("input_text", text, null, null, null);
  }

  public static ContentPart refusal(String text) {
    return new ContentPart("refusal", text, null, null, null);
  }

  /**
   * Inline image content part. The Responses API accepts a base64 data URI in {@code image_url}.
   *
   * @param mediaType the IANA media type (e.g. {@code "image/png"}); non-blank
   * @param base64Data the base64-encoded image bytes; non-blank
   * @return a fresh image part
   */
  public static ContentPart inputImage(String mediaType, String base64Data) {
    return new ContentPart(
        "input_image", null, "data:" + mediaType + ";base64," + base64Data, null, null);
  }

  /**
   * Inline file content part (e.g. PDF). The Responses API accepts a base64 data URI in {@code
   * file_data} plus an optional filename.
   *
   * @param mediaType the IANA media type (e.g. {@code "application/pdf"}); non-blank
   * @param base64Data the base64-encoded file bytes; non-blank
   * @param filename optional original filename; may be null
   * @return a fresh file part
   */
  public static ContentPart inputFile(String mediaType, String base64Data, String filename) {
    return new ContentPart(
        "input_file", null, null, "data:" + mediaType + ";base64," + base64Data, filename);
  }

  public boolean hasTypeOutputText() {
    return "output_text".equals(type);
  }

  public boolean hasTypeInputText() {
    return "input_text".equals(type);
  }

  public boolean hasTypeRefusal() {
    return "refusal".equals(type);
  }

  public boolean hasTypeInputImage() {
    return "input_image".equals(type);
  }

  public boolean hasTypeInputFile() {
    return "input_file".equals(type);
  }
}
