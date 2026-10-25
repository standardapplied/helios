/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import java.util.List;
import java.util.Objects;

/**
 * Per-session UX-level policy: which tool calls to allow silently, which to ask the user about, and
 * which to deny outright. Sandboxes are the real security boundary; permissions are the user-facing
 * guardrail per spec §12.
 *
 * <p>The most restrictive matching rule decides, in every mode: the first matching {@code deny}
 * rule, else the first matching {@code ask} rule, else the first matching {@code allow} rule, else
 * the mode's default for the tool's category (see {@link DefaultPermissionEvaluator}). So an allow
 * never carves an exception out of a matching ask or deny: to ask about {@code ./secrets/**} except
 * one file, list the file's siblings as asks instead, or, where the mode already asks for the
 * tool's category, drop the broad ask and keep the allow.
 *
 * <p>A path tool's rule matches its canonical path ({@code .} or {@code ./a/b} in a workspace,
 * {@code /memories/} or {@code /memories/a/b} in memory), however the model spelled it; see {@link
 * RuleMatcher} for the glob dialect. A DENY rule also withholds the entries a walking tool ({@code
 * Glob}, {@code Grep}, {@code LS}, a {@code MemoryRead} listing) would return below its path, and a
 * {@code Read} DENY or ASK rule that decides a file withholds it from {@code Grep}, without asking.
 * The decision that admitted a walk's start covers what it finds except what these rules withhold;
 * a mode or category default, such as {@link PermissionMode#LOCKED_DOWN}'s, is not a rule and
 * withholds nothing.
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
   * The name a refusal by the permission policy carries as its {@code by}, in its {@code Rejected}
   * outcome and {@code ToolBlocked} event; reserved, so no hook may take it.
   */
  public static final String NAME = "Permission";

  /**
   * Canonical constructor; defensively copies all three rule lists.
   *
   * @throws NullPointerException if any argument is null or any list contains null elements
   */
  public Permission {
    Objects.requireNonNull(mode, "mode must not be null");
    allow = List.copyOf(Objects.requireNonNull(allow, "allow must not be null"));
    ask = List.copyOf(Objects.requireNonNull(ask, "ask must not be null"));
    deny = List.copyOf(Objects.requireNonNull(deny, "deny must not be null"));
  }

  /**
   * The canonical "interactive workspace" preset: {@link PermissionMode#DEFAULT}, which allows
   * reads, listings and searches and asks before writes and execution, plus memory writes allowed
   * on {@code /memories/**}. No asks or denies — users add their own.
   *
   * @return a fresh preset
   */
  public static Permission defaultInWorkspace() {
    return new Permission(
        PermissionMode.DEFAULT,
        List.of(PermissionRule.of("MemoryWrite", "/memories/**")),
        List.of(),
        List.of());
  }

  /**
   * The {@link PermissionMode#PLAN} preset — every write/execution is denied. Useful for an
   * outline-only pass.
   *
   * @return a fresh preset
   */
  public static Permission planMode() {
    return empty(PermissionMode.PLAN);
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
   * filesystem reads, no writes, no memory I/O. Use it for sessions where the execution sandbox is
   * the real security boundary and every other tool would be a hole.
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
        List.of(PermissionRule.any("Execute"), PermissionRule.any("AskUserQuestion")),
        List.of(),
        List.of());
  }
}
