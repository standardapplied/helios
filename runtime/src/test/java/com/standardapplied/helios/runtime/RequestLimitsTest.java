/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RequestLimitsTest {

  @Test
  void theDefaultsAreTheDocumentedProductionValues() {
    assertEquals(new RequestLimits(262_144, 256, 16, 4, 4), RequestLimits.defaults());
    assertEquals(RequestLimits.defaults(), RequestLimits.newBuilder().build());
  }

  @Test
  void theBuilderSetsEveryBound() {
    var limits =
        RequestLimits.newBuilder()
            .withMaxRequestBytes(1024)
            .withMaxMessageUploads(5)
            .withMaxMessageUploadsPerPrincipal(4)
            .withMaxEventStreamsPerSession(2)
            .withMaxResultPollsPerSession(3)
            .build();

    assertEquals(new RequestLimits(1024, 5, 4, 2, 3), limits);
  }

  @Test
  void everyBoundMustBePositive() {
    assertRejected(
        "maxRequestBytes must be positive, got 0",
        RequestLimits.newBuilder().withMaxRequestBytes(0));
    assertRejected(
        "maxMessageUploads must be positive, got 0",
        RequestLimits.newBuilder().withMaxMessageUploads(0));
    assertRejected(
        "maxMessageUploadsPerPrincipal must be positive, got -2",
        RequestLimits.newBuilder().withMaxMessageUploadsPerPrincipal(-2));
    assertRejected(
        "maxEventStreamsPerSession must be positive, got -1",
        RequestLimits.newBuilder().withMaxEventStreamsPerSession(-1));
    assertRejected(
        "maxResultPollsPerSession must be positive, got 0",
        RequestLimits.newBuilder().withMaxResultPollsPerSession(0));
  }

  @Test
  void theRequestBoundStopsAtItsCeiling() {
    var ceiling = RequestLimits.MAX_REQUEST_BYTES_CEILING;

    assertEquals(
        ceiling, RequestLimits.newBuilder().withMaxRequestBytes(ceiling).build().maxRequestBytes());
    assertRejected(
        "maxRequestBytes must be at most 67108864, got 67108865",
        RequestLimits.newBuilder().withMaxRequestBytes(ceiling + 1));
  }

  private static void assertRejected(String message, RequestLimits.Builder builder) {
    var ex = assertThrows(IllegalArgumentException.class, builder::build);
    assertEquals(message, ex.getMessage());
  }
}
