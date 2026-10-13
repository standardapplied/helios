/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The message of an error a provider reports inside a stream: a summary, then the error's code and
 * its own message, each when present, joined by {@code ": "}. Never the raw event, which can echo
 * the request.
 */
public final class StreamErrorMessage {

  private StreamErrorMessage() {}

  /**
   * The message, e.g. {@code API stream error: server_error: The model failed}.
   *
   * @param summary what failed; non-null
   * @param code the provider's error code, or {@code null}
   * @param message the provider's error message, or {@code null}
   * @return the joined message
   */
  public static String of(String summary, String code, String message) {
    return Stream.of(Objects.requireNonNull(summary, "summary must not be null"), code, message)
        .filter(Objects::nonNull)
        .collect(Collectors.joining(": "));
  }
}
