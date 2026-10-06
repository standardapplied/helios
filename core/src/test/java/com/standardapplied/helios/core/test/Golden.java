/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Golden files under the running module's {@code src/test/resources/golden}: fixtures a test reads
 * and expected output it must reproduce exactly. Running the build with {@code
 * -Dgolden.update=true} rewrites every expected file from what the code produces now, for a
 * reviewer to diff.
 */
public final class Golden {

  static final Path ROOT = Path.of("src", "test", "resources", "golden");

  private Golden() {}

  /** The text of the golden file {@code name}. */
  public static String read(String name) {
    try {
      return Files.readString(ROOT.resolve(name)).replace("\r\n", "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Asserts that {@code actual} equals the golden file {@code name} character for character. */
  public static void assertMatches(String name, String actual) {
    assertMatches(ROOT.resolve(name), actual, Boolean.getBoolean("golden.update"));
  }

  static void assertMatches(Path file, String actual, boolean update) {
    var normalized = actual.replace("\r\n", "\n");
    try {
      if (update) {
        Files.createDirectories(file.getParent());
        Files.writeString(file, normalized);
      }
      assertEquals(Files.readString(file).replace("\r\n", "\n"), normalized, file.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
