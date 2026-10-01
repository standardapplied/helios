/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class TransientStreamExceptionTest {

  @Test
  void preservesMessageCauseAndProviderName() {
    var cause = new IOException("Connection reset by peer");
    var ex = new TransientStreamException("Stream read error", cause, "anthropic");

    assertEquals("Stream read error", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertEquals("anthropic", ex.providerName());
  }

  @Test
  void nullCauseIsAllowed() {
    var ex = new TransientStreamException("stream broke", null, "openai");
    assertNull(ex.getCause());
    assertEquals("openai", ex.providerName());
  }

  @Test
  void nullMessageRejected() {
    assertThrows(
        NullPointerException.class, () -> new TransientStreamException(null, null, "anthropic"));
  }

  @Test
  void blankMessageRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new TransientStreamException("   ", null, "anthropic"));
    assertEquals("message must not be blank", ex.getMessage());
  }

  @Test
  void nullProviderNameRejected() {
    assertThrows(
        NullPointerException.class,
        () -> new TransientStreamException("Stream read error", null, null));
  }

  @Test
  void blankProviderNameRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new TransientStreamException("Stream read error", null, "  "));
    assertEquals("providerName must not be blank", ex.getMessage());
  }
}
