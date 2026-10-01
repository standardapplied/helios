/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryExhaustedExceptionTest {

  @Test
  void constructorWithCause() {
    var cause = new RuntimeException("Original error");
    var exception = new RetryExhaustedException(3, cause);

    assertEquals(3, exception.attempts());
    assertSame(cause, exception.getCause());
    assertTrue(exception.getMessage().contains("3"));
  }

  @Test
  void constructorWithNullCause() {
    var exception = new RetryExhaustedException(5, null);

    assertEquals(5, exception.attempts());
    assertEquals(null, exception.getCause());
  }

  @Test
  void messageFormat() {
    var exception = new RetryExhaustedException(10, new IOException("test"));

    assertEquals("Retry exhausted after 10 attempts: test", exception.getMessage());
  }

  @Test
  void messageFormatNullCause() {
    var exception = new RetryExhaustedException(2, null);

    assertEquals("Retry exhausted after 2 attempts", exception.getMessage());
  }

  static class IOException extends Exception {
    IOException(String message) {
      super(message);
    }
  }
}
