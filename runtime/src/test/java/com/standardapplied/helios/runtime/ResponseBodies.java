/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Reads a streamed HTTP response body to its end and closes it. */
final class ResponseBodies {

  private ResponseBodies() {}

  /** Read {@code body} to its end as UTF-8, then close it. */
  static String text(InputStream body) {
    try (body) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
