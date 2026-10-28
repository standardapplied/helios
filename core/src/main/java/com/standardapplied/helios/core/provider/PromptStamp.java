/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The stamp of the prompt a request was built under: the lowercase hex SHA-256 of the canonical
 * JSON of its system instruction and tool definitions. A provider records it on the assistant
 * message a request produced and compares it with the next request's, to replay only the reasoning
 * its API still accepts.
 */
public final class PromptStamp {

  private PromptStamp() {}

  /**
   * The stamp of {@code canonicalPrefix}.
   *
   * @param canonicalPrefix the canonical JSON of a request's system instruction and tools
   * @return 64 lowercase hex digits
   */
  public static String of(byte[] canonicalPrefix) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalPrefix));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("This JVM provides no SHA-256, which every JDK must", e);
    }
  }
}
