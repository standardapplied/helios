/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CircuitBreakerOpenExceptionTest {

  @Test
  void defaultConstructor() {
    var exception = new CircuitBreakerOpenException();

    assertEquals("Circuit breaker is open", exception.getMessage());
  }

  @Test
  void customMessage() {
    var exception = new CircuitBreakerOpenException("Service unavailable");

    assertEquals("Service unavailable", exception.getMessage());
  }
}
