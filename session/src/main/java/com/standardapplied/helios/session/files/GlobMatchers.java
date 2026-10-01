/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import java.nio.file.FileSystem;
import java.nio.file.PathMatcher;

/**
 * Glob compilation with Unix-tool semantics — {@code **&#47;*.md} matches markdown files anywhere,
 * <i>including</i> at the root.
 *
 * <p>Java NIO's default {@link FileSystem#getPathMatcher} treats {@code **} as "zero or more
 * directory segments, with literal separator required after," so {@code **&#47;*.md} demands at
 * least one parent directory and silently skips root-level files. Every other glob system agents
 * are likely to have seen (ripgrep, fd, gitignore, Python pathlib, Go filepath/match) treats {@code
 * **&#47;*.md} as recursive-including-root. The tool descriptions advertised by {@link GlobTool}
 * and {@link GrepTool} use {@code **&#47;*.java} as the example, so the model reaches for the
 * pattern it can't actually use under raw JDK semantics. Live agent-session tests caught this on
 * the first run.
 *
 * <p>Fix: for any pattern starting with {@code **&#47;}, also try the pattern with that prefix
 * stripped. A file matches if either form accepts it. Semantics preserved for non-{@code
 * **&#47;}-leading patterns; existing call sites unaffected.
 */
final class GlobMatchers {

  private GlobMatchers() {}

  /**
   * Compile a glob pattern against the given filesystem with Unix-tool semantics.
   *
   * @param fs filesystem whose {@code getPathMatcher} drives the compilation
   * @param pattern glob pattern in JDK syntax
   * @return a matcher with the recursive-including-root fix applied
   * @throws IllegalArgumentException if either compiled form is malformed
   */
  static PathMatcher compile(FileSystem fs, String pattern) {
    var primary = fs.getPathMatcher("glob:" + pattern);
    if (pattern.startsWith("**/")) {
      var rootForm = fs.getPathMatcher("glob:" + pattern.substring(3));
      return path -> primary.matches(path) || rootForm.matches(path);
    }
    return primary;
  }
}
