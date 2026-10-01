/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ReplExceptionTest {

  @Test
  void messageOnly() {
    var ex = new ReplException("test error");
    assertEquals("test error", ex.getMessage());
    assertNull(ex.getCause());
  }

  @Test
  void messageAndCause() {
    var cause = new IOException("io fail");
    var ex = new ReplException("wrapped", cause);
    assertEquals("wrapped", ex.getMessage());
    assertEquals(cause, ex.getCause());
  }

  @Test
  void isRuntimeException() {
    var ex = new ReplException("test");
    assertEquals(RuntimeException.class, ex.getClass().getSuperclass());
  }
}
