/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.common.Strings;
import java.util.Arrays;
import java.util.Objects;

/**
 * An inline file attachment for multimodal messages.
 *
 * <p>Carries raw bytes and MIME type for images, documents, audio, and video sent alongside text
 * prompts. Binary data is never persisted to memory — the agent strips inline files before storing
 * messages.
 *
 * @param data the raw file bytes
 * @param mimeType the MIME type (e.g., "image/png", "application/pdf")
 */
public record InlineFile(byte[] data, String mimeType) {

  /**
   * Canonical constructor. The array is kept as given, not copied.
   *
   * @throws NullPointerException if {@code data} is null
   * @throws IllegalArgumentException if {@code data} is empty or {@code mimeType} is null or blank
   */
  public InlineFile {
    Objects.requireNonNull(data, "data must not be null");
    if (data.length == 0) {
      throw new IllegalArgumentException("data must not be empty");
    }
    if (Strings.isBlank(mimeType)) {
      throw new IllegalArgumentException("mimeType must not be null or blank");
    }
  }

  /**
   * Creates an inline file, validated as the canonical constructor validates it.
   *
   * @param data the raw file bytes, not copied; non-empty
   * @param mimeType the MIME type, such as {@code image/png}; non-blank
   * @return the inline file
   * @throws NullPointerException if {@code data} is null
   * @throws IllegalArgumentException if {@code data} is empty or {@code mimeType} is null or blank
   */
  public static InlineFile of(byte[] data, String mimeType) {
    return new InlineFile(data, mimeType);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof InlineFile other)) return false;
    return Arrays.equals(data, other.data) && mimeType.equals(other.mimeType);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(data) + mimeType.hashCode();
  }

  @Override
  public String toString() {
    return "InlineFile[mimeType=" + mimeType + ", size=" + data.length + "]";
  }
}
