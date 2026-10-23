/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RequestLimitsTest {

  @Test
  void theDefaultsAreTheDocumentedProductionValues() {
    assertEquals(new RequestLimits(262_144, 32), RequestLimits.defaults());
  }

  @Test
  void everyBoundMustBePositive() {
    assertRejected("maxRequestBytes must be positive, got 0", 0, 1);
    assertRejected("maxConcurrentRequestsPerPrincipal must be positive, got -2", 1, -2);
  }

  @Test
  void theRequestBoundStopsAtItsCeiling() {
    var ceiling = RequestLimits.MAX_REQUEST_BYTES_CEILING;

    assertEquals(ceiling, new RequestLimits(ceiling, 1).maxRequestBytes());
    assertRejected("maxRequestBytes must be at most 67108864, got 67108865", ceiling + 1, 1);
  }

  private static void assertRejected(String message, int maxRequestBytes, int perPrincipal) {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new RequestLimits(maxRequestBytes, perPrincipal));
    assertEquals(message, ex.getMessage());
  }
}
