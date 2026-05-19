/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Per-session UX-level policy: which tool calls to allow silently, which to ask the user about, and
 * which to deny outright. Sandboxes are the real security boundary; permissions are the user-facing
 * guardrail per spec §12.
 *
 * <p>Evaluation order (see {@link DefaultPermissionEvaluator} for the implementation): DENY rules
 * win first; then {@link PermissionMode#BYPASS_PERMISSIONS} short-circuits to ALLOW; then explicit
 * ALLOW rules; then explicit ASK rules; then the category-based default policy.
 *
 * @param mode the coarse policy lever; non-null
 * @param allow rules that, on match, allow the call; non-null
 * @param ask rules that, on match, ask the user for confirmation; non-null
 * @param deny rules that, on match, refuse the call; non-null
 */
public record Permission(
    PermissionMode mode,
    List<PermissionRule> allow,
    List<PermissionRule> ask,
    List<PermissionRule> deny) {

  /**
   * Canonical constructor; defensively copies all three rule lists.
   *
   * @throws NullPointerException if any argument is null or any list contains null elements
   */
  public Permission {
    Objects.requireNonNull(mode, "mode must not be null");
    Objects.requireNonNull(allow, "allow must not be null");
    Objects.requireNonNull(ask, "ask must not be null");
    Objects.requireNonNull(deny, "deny must not be null");
    allow = List.copyOf(allow);
    ask = List.copyOf(ask);
    deny = List.copyOf(deny);
  }

  /**
   * The canonical "interactive workspace" preset: reads, listings, searches, and memory I/O are
   * allowed silently against {@code ./**}; writes, edits, and execution are asked. No explicit
   * denies — users add their own.
   *
   * @return a fresh preset
   */
  public static Permission defaultInWorkspace() {
    return new Permission(
        PermissionMode.DEFAULT,
        List.of(
            PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "./**"),
            PermissionRule.withGlob(PermissionEffect.ALLOW, "LS", "./**"),
            PermissionRule.withGlob(PermissionEffect.ALLOW, "Glob", "./**"),
            PermissionRule.withGlob(PermissionEffect.ALLOW, "Grep", "./**"),
            PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryRead", "/memories/**"),
            PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryWrite", "/memories/**")),
        List.of(
            PermissionRule.withGlob(PermissionEffect.ASK, "Write", "./**"),
            PermissionRule.withGlob(PermissionEffect.ASK, "Edit", "./**"),
            PermissionRule.withGlob(PermissionEffect.ASK, "MultiEdit", "./**"),
            PermissionRule.any(PermissionEffect.ASK, "Execute")),
        List.of());
  }

  /**
   * The {@link PermissionMode#PLAN} preset — every write/execution is denied. Useful for an
   * outline-only pass.
   *
   * @return a fresh preset
   */
  public static Permission planMode() {
    return new Permission(
        PermissionMode.PLAN,
        List.of(
            PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "./**"),
            PermissionRule.withGlob(PermissionEffect.ALLOW, "LS", "./**"),
            PermissionRule.withGlob(PermissionEffect.ALLOW, "Glob", "./**"),
            PermissionRule.withGlob(PermissionEffect.ALLOW, "Grep", "./**")),
        List.of(),
        List.of());
  }

  /**
   * A permission with empty rule lists in the given mode.
   *
   * @param mode the coarse mode; non-null
   * @return a fresh empty permission
   */
  public static Permission empty(PermissionMode mode) {
    return new Permission(mode, List.of(), List.of(), List.of());
  }

  /**
   * The "sandbox is the world" preset: every category default-denies under {@link
   * PermissionMode#LOCKED_DOWN}; the only explicit allows are {@code Execute} (so the sandbox can
   * run code) and {@code AskUserQuestion} (so the model can still ask for clarification). No
   * filesystem reads, no writes, no memory I/O. Used by {@code CodeActPreset}-style sessions where
   * the execution sandbox is the real security boundary and every other tool would be a hole.
   *
   * <p>Adding more tools to a session built with this preset will silently refuse them unless the
   * caller layers additional allow rules. That's intentional — a misconfigured tool registry should
   * fail closed, not open.
   *
   * @return a fresh preset
   */
  public static Permission lockedDown() {
    return new Permission(
        PermissionMode.LOCKED_DOWN,
        List.of(
            PermissionRule.any(PermissionEffect.ALLOW, "Execute"),
            PermissionRule.any(PermissionEffect.ALLOW, "AskUserQuestion")),
        List.of(),
        List.of());
  }

  /**
   * Look up an explicit rule for the given tool name in this permission's rule lists. Returns the
   * first match across {@code deny}, then {@code allow}, then {@code ask} — only used by
   * diagnostics, not by the evaluator (which inspects each list independently).
   *
   * @param toolName the tool name to look up; non-null
   * @return the first matching rule, or empty
   */
  public Optional<PermissionRule> firstRuleFor(String toolName) {
    Objects.requireNonNull(toolName, "toolName must not be null");
    return Stream.of(deny, allow, ask)
        .flatMap(List::stream)
        .filter(r -> r.toolName().equals(toolName))
        .findFirst();
  }
}
