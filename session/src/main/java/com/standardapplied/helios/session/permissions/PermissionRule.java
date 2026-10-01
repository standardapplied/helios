/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import ai.singlr.core.common.Strings;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of a permission table. Matches if the tool name equals {@code toolName} AND, when {@code
 * argPattern} is present, the canonical args (from {@link
 * ai.singlr.session.tools.ToolPermissionKey#canonicalArgs()}) match the glob.
 *
 * <p>Glob syntax follows {@code java.nio.file.PathMatcher} semantics — {@code *} matches a single
 * path segment, {@code **} matches any number of segments, brackets / question marks supported. An
 * absent {@code argPattern} means "any args."
 *
 * @param effect ALLOW / ASK / DENY decision applied if the rule matches; non-null
 * @param toolName the tool's stable name; non-blank
 * @param argPattern optional glob over the tool's canonical args; non-null Optional
 */
public record PermissionRule(
    PermissionEffect effect, String toolName, Optional<String> argPattern) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code effect}, {@code toolName}, or {@code argPattern} is null
   * @throws IllegalArgumentException if {@code toolName} is blank or {@code argPattern} contains a
   *     blank string
   */
  public PermissionRule {
    Objects.requireNonNull(effect, "effect must not be null");
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
   * Factory for an "any-args" rule.
   *
   * @param effect ALLOW / ASK / DENY
   * @param toolName the tool's stable name; non-blank
   * @return a fresh rule with {@code argPattern == Optional.empty()}
   */
  public static PermissionRule any(PermissionEffect effect, String toolName) {
    return new PermissionRule(effect, toolName, Optional.empty());
  }

  /**
   * Factory for a rule that matches a specific glob.
   *
   * @param effect ALLOW / ASK / DENY
   * @param toolName the tool's stable name; non-blank
   * @param glob the path glob; non-blank
   * @return a fresh rule
   */
  public static PermissionRule withGlob(PermissionEffect effect, String toolName, String glob) {
    return new PermissionRule(effect, toolName, Optional.of(glob));
  }
}
