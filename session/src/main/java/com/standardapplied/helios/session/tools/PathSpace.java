/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.tools;

import java.util.Arrays;

/**
 * The namespace a path argument names a target in, and the one place that argument's canonical
 * spelling is defined. A path tool's permission key is the canonical spelling of its path argument,
 * so a rule glob is written against that spelling and the tool runs on the target it names.
 */
public interface PathSpace {

  /**
   * The canonical spelling of {@code requested}.
   *
   * @param requested the path as the model spelled it; non-null
   * @return its canonical spelling
   * @throws RuntimeException whose message says why, when {@code requested} names no target of this
   *     space
   */
  String canonical(String requested);

  /**
   * Whether a rule glob can match a canonical spelling of this space.
   *
   * @param glob a rule's glob; non-null
   * @return {@code false} when no canonical spelling can match {@code glob}
   */
  boolean admits(String glob);

  /**
   * A canonical spelling to show in the message refusing a glob this space does not {@link #admits
   * admit}.
   *
   * @return a sample canonical spelling
   */
  String example();

  /**
   * Whether every segment of {@code glob} after its first is one a canonical spelling can hold: no
   * empty, {@code .} or {@code ..} segment.
   *
   * @param glob a rule's glob; non-null
   * @return {@code true} when the segments after the first are proper
   */
  static boolean laterSegmentsProper(String glob) {
    return Arrays.stream(glob.split("/", -1))
        .skip(1)
        .noneMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
  }
}
