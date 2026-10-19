/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.memory;

import static com.standardapplied.helios.session.memory.MemoryBackend.PREFIX;

import com.standardapplied.helios.session.tools.PathSpace;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * The memory namespace as a {@link PathSpace}: a canonical memory path is {@code /memories/} for
 * the memory root and {@code /memories/a/b} below it, with no {@code .}, {@code ..} or empty
 * segment and no trailing {@code /}. Canonicalisation is lexical and never touches a backend, since
 * memory {@code create} makes missing parents; a backend confines the path when it runs.
 */
final class MemoryPaths implements PathSpace {

  static final MemoryPaths INSTANCE = new MemoryPaths();

  private MemoryPaths() {}

  /**
   * The canonical spelling of a memory path.
   *
   * @param requested the path as the model spelled it; non-null
   * @return its canonical spelling
   * @throws IllegalArgumentException if {@code requested} does not start with {@link
   *     MemoryBackend#PREFIX} or climbs above the memory root
   */
  @Override
  public String canonical(String requested) {
    Objects.requireNonNull(requested, "requested must not be null");
    if (!requested.startsWith(PREFIX)) {
      throw new IllegalArgumentException(
          "path must start with " + PREFIX + ", got '" + requested + "'");
    }
    var segments = new ArrayDeque<String>();
    for (var segment : requested.substring(PREFIX.length()).split("/")) {
      if (segment.equals("..")) {
        if (segments.isEmpty()) {
          throw new IllegalArgumentException("path escapes memory root: " + requested);
        }
        segments.removeLast();
      } else if (!segment.isEmpty() && !segment.equals(".")) {
        segments.addLast(segment);
      }
    }
    return PREFIX + String.join("/", segments);
  }

  /**
   * Whether {@code glob} can match a canonical memory path: it is {@code /memories/}, or starts
   * with {@code /memories/} or {@code **}, and no later segment is empty, {@code .} or {@code ..}.
   *
   * @param glob a rule's glob; non-null
   * @return whether a canonical memory path can match it
   */
  @Override
  public boolean admits(String glob) {
    if (glob.equals(PREFIX)) {
      return true;
    }
    if (glob.startsWith(PREFIX)) {
      return PathSpace.laterSegmentsProper(glob.substring(PREFIX.length() - 1));
    }
    return glob.startsWith("**") && PathSpace.laterSegmentsProper(glob);
  }

  @Override
  public String example() {
    return PREFIX + "notes.md";
  }
}
