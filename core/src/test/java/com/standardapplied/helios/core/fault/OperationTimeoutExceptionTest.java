/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OperationTimeoutExceptionTest {

  @Test
  void constructorWithTimeout() {
    var timeout = Duration.ofMinutes(5);
    var exception = new OperationTimeoutException(timeout);

    assertEquals(timeout, exception.timeout());
    assertTrue(exception.getMessage().contains("PT5M"));
  }

  @Test
  void messageFormat() {
    var exception = new OperationTimeoutException(Duration.ofSeconds(30));

    assertEquals("Operation timed out after PT30S", exception.getMessage());
  }
}
