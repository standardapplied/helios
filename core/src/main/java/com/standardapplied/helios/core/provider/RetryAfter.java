/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The wait a response's {@code Retry-After} header asks for, in its delta-seconds form only: the
 * one form the providers document. An HTTP date, or any other value, names no wait.
 */
final class RetryAfter {

  private static final Pattern DELTA_SECONDS = Pattern.compile("[0-9]+");

  private RetryAfter() {}

  /**
   * The first {@code Retry-After} value of {@code headers}, trimmed, as that many seconds; a count
   * beyond {@link Long#MAX_VALUE} saturates there.
   *
   * @return the wait, or empty when the header is absent or not delta-seconds
   */
  static Optional<Duration> of(HttpHeaders headers) {
    return headers
        .firstValue("Retry-After")
        .map(String::trim)
        .filter(value -> DELTA_SECONDS.matcher(value).matches())
        .map(RetryAfter::seconds);
  }

  private static Duration seconds(String digits) {
    try {
      return Duration.ofSeconds(Long.parseLong(digits));
    } catch (NumberFormatException beyondLong) {
      return Duration.ofSeconds(Long.MAX_VALUE);
    }
  }
}
