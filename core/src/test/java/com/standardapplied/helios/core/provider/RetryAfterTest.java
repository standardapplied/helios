/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RetryAfterTest {

  static Stream<Arguments> values() {
    return Stream.of(
        Arguments.of("2", Optional.of(Duration.ofSeconds(2))),
        Arguments.of("0", Optional.of(Duration.ZERO)),
        Arguments.of(" 7 ", Optional.of(Duration.ofSeconds(7))),
        Arguments.of("99999999999999999999", Optional.of(Duration.ofSeconds(Long.MAX_VALUE))),
        Arguments.of("Wed, 21 Oct 2026 07:28:00 GMT", Optional.empty()),
        Arguments.of("-1", Optional.empty()),
        Arguments.of("+1", Optional.empty()),
        Arguments.of("1.5", Optional.empty()),
        Arguments.of("soon", Optional.empty()),
        Arguments.of("", Optional.empty()));
  }

  @ParameterizedTest
  @MethodSource("values")
  void readsOnlyDeltaSeconds(String value, Optional<Duration> wait) {
    assertEquals(wait, RetryAfter.of(headers(Map.of("Retry-After", List.of(value)))));
  }

  @Test
  void readsTheFirstValueWhateverTheNamesCase() {
    assertEquals(
        Optional.of(Duration.ofSeconds(3)),
        RetryAfter.of(headers(Map.of("retry-after", List.of("3", "9")))));
  }

  @Test
  void noHeaderIsNoWait() {
    assertEquals(Optional.empty(), RetryAfter.of(headers(Map.of())));
  }

  private static HttpHeaders headers(Map<String, List<String>> values) {
    return HttpHeaders.of(values, (name, value) -> true);
  }
}
