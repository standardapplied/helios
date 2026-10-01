/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ToolPermissionKeyTest {

  @Test
  void canonicalConstructorRetainsBothFields() {
    var key = new ToolPermissionKey("Read", "/workspace/foo.txt");
    assertEquals("Read", key.toolName());
    assertEquals("/workspace/foo.txt", key.canonicalArgs());
  }

  @Test
  void emptyCanonicalArgsAllowed() {
    var key = new ToolPermissionKey("AskUserQuestion", "");
    assertEquals("", key.canonicalArgs());
  }

  @Test
  void ofFactoryDefaultsEmptyArgs() {
    var key = ToolPermissionKey.of("Read");
    assertEquals("Read", key.toolName());
    assertEquals("", key.canonicalArgs());
  }

  @Test
  void rejectsNullToolName() {
    var ex = assertThrows(NullPointerException.class, () -> new ToolPermissionKey(null, ""));
    assertEquals("toolName must not be null", ex.getMessage());
  }

  @Test
  void rejectsBlankToolName() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new ToolPermissionKey("  ", ""));
    assertEquals("toolName must not be blank", ex.getMessage());
  }

  @Test
  void rejectsNullCanonicalArgs() {
    var ex = assertThrows(NullPointerException.class, () -> new ToolPermissionKey("Read", null));
    assertEquals("canonicalArgs must not be null", ex.getMessage());
  }
}
