/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.tools;

import com.standardapplied.helios.core.common.Strings;
import java.util.Objects;

/**
 * Canonical key used by the permission system to match a tool invocation against rules.
 *
 * <p>The {@code toolName} identifies the tool; the {@code canonicalArgs} is a tool-defined
 * normalised representation of the call's arguments — typically a path, a domain, a query
 * substring, or similar. Rules match on either or both fields. A path tool's canonical args are the
 * canonical spelling of its path argument ({@link ToolBinding#canonicalize}): {@code .} or {@code
 * ./a/b} in a workspace, with no {@code .}, {@code ..} or empty segment and no trailing {@code /},
 * and {@code /memories/} or {@code /memories/a/b} in memory.
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code Read} produces {@code ("Read", "./foo.txt")}, the canonical path of its target.
 *   <li>{@code Grep} produces {@code ("Grep", "./src")} — the canonical search path, not the
 *       pattern.
 *   <li>{@code MemoryRead} produces {@code ("MemoryRead", "/memories/notes.md")}.
 *   <li>{@code AskUserQuestion} produces {@code ("AskUserQuestion", "")} — control tools rarely
 *       need finer matching.
 * </ul>
 *
 * @param toolName the tool's stable name; non-blank
 * @param canonicalArgs the canonical argument form; non-null, may be empty
 */
public record ToolPermissionKey(String toolName, String canonicalArgs) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code toolName} or {@code canonicalArgs} is null
   * @throws IllegalArgumentException if {@code toolName} is blank
   */
  public ToolPermissionKey {
    Objects.requireNonNull(toolName, "toolName must not be null");
    if (Strings.isBlank(toolName)) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    Objects.requireNonNull(canonicalArgs, "canonicalArgs must not be null");
  }

  /**
   * Build a key from a tool name alone, with empty canonical args.
   *
   * @param toolName the tool's stable name; non-blank
   * @return a fresh key
   */
  public static ToolPermissionKey of(String toolName) {
    return new ToolPermissionKey(toolName, "");
  }

  /**
   * The {@code <tool>(<canonical args>)} form of this key, as permission reasons and approval
   * prompts show it; just the tool name when the args are empty.
   *
   * @return the display form
   */
  public String describe() {
    return toolName + (canonicalArgs.isEmpty() ? "" : "(" + canonicalArgs + ")");
  }
}
