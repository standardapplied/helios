/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import ai.singlr.core.common.Strings;
import java.util.Objects;

/**
 * Canonical key used by the permission system to match a tool invocation against rules.
 *
 * <p>The {@code toolName} identifies the tool; the {@code canonicalArgs} is a tool-defined
 * normalised representation of the call's arguments — typically a path, a domain, a query
 * substring, or similar. Rules match on either or both fields.
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code Read} produces {@code ("Read", "/workspace/foo.txt")}.
 *   <li>{@code Grep} produces {@code ("Grep", "/workspace/src")} — the search path, not the
 *       pattern.
 *   <li>{@code Fetch} produces {@code ("Fetch", "api.example.com")} — the host.
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
}
