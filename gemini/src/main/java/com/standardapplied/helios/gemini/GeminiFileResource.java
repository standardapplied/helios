/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.standardapplied.helios.core.common.Strings;
import java.util.regex.Pattern;

/**
 * A file resource as the Gemini Files API describes it.
 *
 * @param name the resource name, {@code files/{id}}
 * @param mimeType the media type
 * @param uri the URI a model message references the file by
 * @param state the processing state
 * @param error why processing failed, when it did
 */
record GeminiFileResource(
    String name,
    @JsonProperty("mimeType") String mimeType,
    String uri,
    String state,
    Status error) {

  /** A processing failure. */
  record Status(Integer code, String message) {}

  private static final Pattern NAME = Pattern.compile("files/[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");

  /**
   * {@code name}, when it is a valid resource name.
   *
   * @throws GeminiException if it is not
   */
  static String requireName(String name) {
    if (Strings.isBlank(name) || !NAME.matcher(name).matches()) {
      throw new GeminiException("Gemini Files API returned an invalid file name");
    }
    return name;
  }
}
