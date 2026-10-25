/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.core.common.Strings;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of a permission table; the list of {@link Permission} it sits in decides its effect.
 * Matches if the tool name equals {@code toolName} AND, when {@code argPattern} is present, the
 * canonical args (from {@link
 * com.standardapplied.helios.session.tools.ToolPermissionKey#canonicalArgs()}) match the glob.
 *
 * <p>A path tool's canonical args are its canonical path: {@code .} or {@code ./a/b} in a workspace
 * (an absolute real path for a trusted workspace's target outside its root), {@code /memories/} or
 * {@code /memories/a/b} in memory. Glob syntax, as {@link RuleMatcher} reads it: a trailing {@code
 * /**} matches the path before it and everything below it; {@code /**}{@code /} and a leading
 * {@code **}{@code /} match zero or more whole segments; any other {@code **} matches any
 * characters; {@code *} matches within a segment and {@code ?} one character of it; every other
 * character, brackets included, is literal. A glob for a registered path tool that no canonical
 * path can match, such as {@code secrets/**} or {@code ./docs/}, fails the session's creation. An
 * absent {@code argPattern} means "any args."
 *
 * @param toolName the tool's stable name; non-blank
 * @param argPattern optional glob over the tool's canonical args; non-null Optional
 */
public record PermissionRule(String toolName, Optional<String> argPattern) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code toolName} or {@code argPattern} is null
   * @throws IllegalArgumentException if {@code toolName} is blank or {@code argPattern} contains a
   *     blank string
   */
  public PermissionRule {
    Objects.requireNonNull(toolName, "toolName must not be null");
    if (Strings.isBlank(toolName)) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    Objects.requireNonNull(argPattern, "argPattern must not be null");
    if (argPattern.isPresent() && Strings.isBlank(argPattern.orElseThrow())) {
      throw new IllegalArgumentException("argPattern must not be blank when present");
    }
  }

  /**
   * A rule that matches every call of a tool.
   *
   * @param toolName the tool's stable name; non-blank
   * @return a fresh rule with {@code argPattern == Optional.empty()}
   */
  public static PermissionRule any(String toolName) {
    return new PermissionRule(toolName, Optional.empty());
  }

  /**
   * A rule that matches the calls of a tool whose canonical args match a glob.
   *
   * @param toolName the tool's stable name; non-blank
   * @param glob the glob; non-blank
   * @return a fresh rule
   * @throws NullPointerException if {@code glob} is null
   */
  public static PermissionRule of(String toolName, String glob) {
    return new PermissionRule(toolName, Optional.of(glob));
  }
}
