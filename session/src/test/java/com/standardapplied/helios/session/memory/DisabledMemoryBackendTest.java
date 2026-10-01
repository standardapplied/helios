/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class DisabledMemoryBackendTest {

  @Test
  void disabledReturnsAProcessSingleton() {
    assertSame(MemoryBackend.disabled(), MemoryBackend.disabled());
  }

  @Test
  void viewRefuses() {
    var ex = assertThrows(IOException.class, () -> MemoryBackend.disabled().view("/memories/x"));
    assertTrue(ex.getMessage().contains("view"));
    assertTrue(ex.getMessage().contains("memory turned off"));
  }

  @Test
  void listRefuses() {
    var ex = assertThrows(IOException.class, () -> MemoryBackend.disabled().list("/memories/"));
    assertTrue(ex.getMessage().contains("list"));
  }

  @Test
  void createRefuses() {
    var ex =
        assertThrows(
            IOException.class, () -> MemoryBackend.disabled().create("/memories/x", "body"));
    assertTrue(ex.getMessage().contains("create"));
  }

  @Test
  void strReplaceRefuses() {
    var ex =
        assertThrows(
            IOException.class, () -> MemoryBackend.disabled().strReplace("/memories/x", "a", "b"));
    assertTrue(ex.getMessage().contains("strReplace"));
  }

  @Test
  void insertRefuses() {
    var ex =
        assertThrows(
            IOException.class, () -> MemoryBackend.disabled().insert("/memories/x", 1, "body"));
    assertTrue(ex.getMessage().contains("insert"));
  }

  @Test
  void deleteRefuses() {
    var ex = assertThrows(IOException.class, () -> MemoryBackend.disabled().delete("/memories/x"));
    assertTrue(ex.getMessage().contains("delete"));
  }

  @Test
  void toStringIsDescriptive() {
    assertEquals("MemoryBackend.disabled()", MemoryBackend.disabled().toString());
  }
}
