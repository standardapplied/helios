/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A file validated for the Gemini Files API: readable, regular, not empty, within the 2 GB per-file
 * limit, named in at most 512 characters, with a valid media type.
 *
 * @param path the file
 * @param displayName its file name
 * @param mimeType its media type
 * @param size its size in bytes
 */
record UploadFile(Path path, String displayName, String mimeType, long size) {

  private static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
  private static final int MAX_NAME_LENGTH = 512;
  private static final Pattern MIME_TYPE =
      Pattern.compile("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");

  /**
   * The file at {@code path}, of {@code mimeType}.
   *
   * @throws IllegalArgumentException if the file or the media type is invalid
   * @throws GeminiException if the file cannot be inspected
   */
  static UploadFile of(Path path, String mimeType) {
    Objects.requireNonNull(path, "path must not be null");
    if (!MIME_TYPE
        .matcher(Objects.requireNonNull(mimeType, "mimeType must not be null"))
        .matches()) {
      throw new IllegalArgumentException("mimeType must be a valid media type");
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException("path must be a readable regular file: " + path);
    }
    var displayName = path.getFileName().toString();
    var size = sizeOf(path);
    if (size == 0) {
      throw new IllegalArgumentException("file must not be empty: " + path);
    }
    if (size > MAX_FILE_BYTES) {
      throw new IllegalArgumentException(
          "file exceeds the Gemini Files API 2 GB per-file limit: " + path);
    }
    if (displayName.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "file name exceeds the Gemini Files API 512-character limit");
    }
    return new UploadFile(path, displayName, mimeType, size);
  }

  private static long sizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      throw new GeminiException("Failed to inspect file " + path, e);
    }
  }
}
