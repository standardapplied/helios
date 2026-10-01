/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class EventSinkPolicyTest {

  @Test
  void hasExpectedValues() {
    assertEquals(3, EventSinkPolicy.values().length);
    assertNotNull(EventSinkPolicy.BLOCK);
    assertNotNull(EventSinkPolicy.DROP_OLDEST);
    assertNotNull(EventSinkPolicy.SAMPLE);
  }

  @Test
  void valueOfRoundTripsName() {
    for (var policy : EventSinkPolicy.values()) {
      assertEquals(policy, EventSinkPolicy.valueOf(policy.name()));
    }
  }
}
