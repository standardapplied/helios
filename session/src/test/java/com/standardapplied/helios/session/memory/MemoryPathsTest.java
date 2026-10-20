/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The memory namespace's canonical spelling, lexical and backend-free. */
final class MemoryPathsTest {

  private static final MemoryPaths PATHS = MemoryPaths.INSTANCE;

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "/memories/, /memories/",
    "/memories/., /memories/",
    "/memories/a.md, /memories/a.md",
    "/memories/a/, /memories/a",
    "/memories//a//b.md, /memories/a/b.md",
    "/memories/./locked/a.md, /memories/locked/a.md",
    "/memories/x/../locked/a.md, /memories/locked/a.md",
    "/memories/x/.., /memories/",
    "/memories/a/b/../c.md, /memories/a/c.md"
  })
  void everySpellingOfAMemoryPathIsCanonicalisedToOne(String requested, String canonical) {
    assertEquals(canonical, PATHS.canonical(requested));
    assertEquals(canonical, PATHS.canonical(canonical));
  }

  @Test
  void aPathOutsideTheNamespaceIsRefused() {
    assertEquals(
        "path must start with /memories/, got 'notes.md'",
        assertThrows(IllegalArgumentException.class, () -> PATHS.canonical("notes.md"))
            .getMessage());
    assertEquals(
        "path must start with /memories/, got '/memories'",
        assertThrows(IllegalArgumentException.class, () -> PATHS.canonical("/memories"))
            .getMessage());
  }

  @Test
  void aPathClimbingAboveTheMemoryRootIsRefused() {
    assertEquals(
        "path escapes memory root: /memories/../x",
        assertThrows(IllegalArgumentException.class, () -> PATHS.canonical("/memories/../x"))
            .getMessage());
    assertEquals(
        "path escapes memory root: /memories/a/../../x",
        assertThrows(IllegalArgumentException.class, () -> PATHS.canonical("/memories/a/../../x"))
            .getMessage());
  }

  @Test
  void canonicalRejectsNull() {
    assertEquals(
        "requested must not be null",
        assertThrows(NullPointerException.class, () -> PATHS.canonical(null)).getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/memories/", "/memories/**", "/memories/locked/**", "**", "**/a.md", "**.md"})
  void aGlobAnchoredInTheNamespaceIsAdmitted(String glob) {
    assertTrue(PATHS.admits(glob));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "memories/**",
        "/memories",
        "/memories/a/",
        "/memories/./a",
        "/memories/../a",
        "**/../a",
        "*"
      })
  void aGlobNoCanonicalMemoryPathCanMatchIsNotAdmitted(String glob) {
    assertFalse(PATHS.admits(glob));
  }

  @Test
  void theExampleIsACanonicalMemoryPath() {
    assertEquals("/memories/notes.md", PATHS.example());
  }
}
