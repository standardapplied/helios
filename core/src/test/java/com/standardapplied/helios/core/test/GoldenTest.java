/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

class GoldenTest {

  @TempDir Path dir;

  @Test
  void matchesAFileWithTheSameTextWhateverItsLineEndings() throws IOException {
    var file = Files.writeString(dir.resolve("expected.txt"), "one\r\ntwo\n");

    Golden.assertMatches(file, "one\ntwo\r\n", false);
  }

  @Test
  void failsOnAnyDifference() throws IOException {
    var file = Files.writeString(dir.resolve("expected.txt"), "one\n");

    assertThrows(AssertionFailedError.class, () -> Golden.assertMatches(file, "one \n", false));
  }

  @Test
  void updateRewritesTheFileFromTheActualText() throws IOException {
    var file = dir.resolve("nested").resolve("expected.txt");

    Golden.assertMatches(file, "fresh\r\n", true);

    assertEquals("fresh\n", Files.readString(file));
  }
}
