/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import com.standardapplied.helios.core.common.Strings;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pins a binary to an absolute, executable path once, so a later change to {@code PATH} cannot
 * shadow it. The lookup is deterministic: no caching and no fallback beyond the supplied {@code
 * PATH}.
 */
public final class BinaryResolver {

  private BinaryResolver() {}

  /**
   * Resolve {@code spec} against {@code pathEnv}, returning the absolute, executable binary path.
   * Accepts either an absolute path (which is checked for executability) or a basename (which is
   * looked up against the supplied {@code pathEnv}, using {@link File#pathSeparator} to split
   * entries).
   *
   * @param spec absolute path or basename; non-blank
   * @param pathEnv the {@code PATH} environment variable to search; non-null, non-empty for
   *     basename lookups
   * @return the absolute path to an executable file
   * @throws IllegalArgumentException if {@code spec} is blank or contains separators without being
   *     absolute
   * @throws IllegalStateException if the binary cannot be located on the supplied {@code PATH}
   */
  public static Path resolve(String spec, String pathEnv) {
    if (Strings.isBlank(spec)) {
      throw new IllegalArgumentException("Binary spec must not be blank");
    }
    var direct = Path.of(spec);
    if (direct.isAbsolute()) {
      if (!isExecutableFile(direct)) {
        throw new IllegalStateException("Binary not executable at " + direct);
      }
      return direct.toAbsolutePath();
    }
    if (spec.contains(File.separator)) {
      throw new IllegalArgumentException(
          "Binary spec must be an absolute path or a basename (no separators): " + spec);
    }
    return searchPath(spec, pathEnv);
  }

  private static Path searchPath(String spec, String pathEnv) {
    if (pathEnv == null || pathEnv.isEmpty()) {
      throw new IllegalStateException("PATH is empty; cannot resolve '" + spec + "'");
    }
    for (var dir : pathEnv.split(File.pathSeparator)) {
      if (!dir.isEmpty() && isExecutableFile(Path.of(dir, spec))) {
        return Path.of(dir, spec).toAbsolutePath();
      }
    }
    throw new IllegalStateException("Binary '" + spec + "' not found on PATH");
  }

  private static boolean isExecutableFile(Path path) {
    return Files.isRegularFile(path) && Files.isExecutable(path);
  }
}
