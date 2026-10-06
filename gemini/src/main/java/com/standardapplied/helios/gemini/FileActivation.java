/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.FileReference;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Waits for an uploaded file to become {@code ACTIVE}, polling its state until it does, fails,
 * enters a state this client does not know, or runs out of time.
 */
final class FileActivation {

  /**
   * An active file.
   *
   * @param reference the reference a model message carries
   * @param resourceName the resource name, {@code files/{id}}
   */
  record ReadyFile(FileReference reference, String resourceName) {}

  private final FilesHttp http;
  private final Duration pollInterval;

  FileActivation(FilesHttp http, Duration pollInterval) {
    this.http = http;
    this.pollInterval = pollInterval;
  }

  /**
   * {@code value}, when it is a duration a wait can use.
   *
   * @throws IllegalArgumentException if it is negative or too large to count in nanoseconds
   */
  static Duration requireNonNegative(Duration value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    try {
      value.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(name + " is too large", e);
    }
    return value;
  }

  /**
   * The file described by {@code uploaded} once it is active, waiting at most {@code timeout}.
   *
   * @throws GeminiException if processing fails, enters an unknown state or times out
   */
  ReadyFile await(GeminiFileResource uploaded, Duration timeout)
      throws IOException, InterruptedException {
    var file = uploaded;
    var started = System.nanoTime();
    var ready = readyOrNull(file);
    while (ready == null) {
      if (System.nanoTime() - started >= timeout.toNanos()) {
        throw new GeminiException(
            "Gemini file processing timed out after " + timeout.toSeconds() + " seconds");
      }
      if (!pollInterval.isZero()) {
        Thread.sleep(pollInterval);
      }
      file = http.get(GeminiFileResource.requireName(file.name()));
      ready = readyOrNull(file);
    }
    return ready;
  }

  /** The ready file when {@code file} is active, or {@code null} while it is still processing. */
  private static ReadyFile readyOrNull(GeminiFileResource file) {
    return switch (file.state()) {
      case "ACTIVE" -> active(file);
      case "FAILED" -> throw failed(file);
      case "PROCESSING", "STATE_UNSPECIFIED" -> null;
      case null -> null;
      default ->
          throw new GeminiException(
              "Gemini file entered unknown processing state: " + file.state());
    };
  }

  private static ReadyFile active(GeminiFileResource file) {
    var resourceName = GeminiFileResource.requireName(file.name());
    if (Strings.isBlank(file.uri()) || Strings.isBlank(file.mimeType())) {
      throw new GeminiException("Active Gemini file is missing its URI or MIME type");
    }
    return new ReadyFile(FileReference.of(file.uri(), file.mimeType()), resourceName);
  }

  private static GeminiException failed(GeminiFileResource file) {
    var detail =
        file.error() != null && !Strings.isBlank(file.error().message())
            ? ": " + file.error().message()
            : "";
    return new GeminiException("Gemini file processing failed" + detail);
  }
}
