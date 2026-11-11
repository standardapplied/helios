/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-session UX-level policy: which tool calls to allow silently, which to ask the user about, and
 * which to deny outright. Sandboxes are the real security boundary; permissions are the user-facing
 * guardrail per spec §12.
 *
 * <p>The most restrictive matching rule decides, in every mode: the first matching {@code deny}
 * rule, else the first matching {@code ask} rule, else the first matching {@code allow} rule, else
 * the mode's default for the tool's category (see {@link #evaluate}). So an allow never carves an
 * exception out of a matching ask or deny: to ask about {@code ./secrets/**} except one file, list
 * the file's siblings as asks instead, or, where the mode already asks for the tool's category,
 * drop the broad ask and keep the allow.
 *
 * <p>A rule names its tool exactly, case included. A deny or ask rule must name a tool the session
 * has, or one whose content a registered walk returns ({@code Read}, for {@code Grep}): one naming
 * no such tool would never match, so it fails {@code AgentSession.open}; an allow rule may name any
 * tool.
 *
 * <p>A path tool's rule matches its canonical path ({@code .} or {@code ./a/b} in a workspace,
 * {@code /memories/} or {@code /memories/a/b} in memory), however the model spelled it; see {@link
 * PermissionRule} for the glob dialect. A walking tool ({@code Glob}, {@code Grep}, {@code LS}, a
 * {@code MemoryRead} listing) skips, without asking, the entries below its path that its own DENY
 * rules decide, and those its own ASK rules decide unless the walk itself was approved through one;
 * {@code Grep} also skips the files a {@code Read} DENY or ASK rule decides. The decision that
 * admitted a walk's start covers what it finds except what these rules withhold; a mode or category
 * default, such as {@link PermissionMode#LOCKED_DOWN}'s, is not a rule and withholds nothing.
 *
 * <p>{@link #evaluate} is the policy's pure decision for one key, the one the session enforces, so
 * a host can unit-test its policy without a session: no question is asked and nothing runs.
 *
 * <p>Build through {@link #newBuilder(PermissionMode)}, which names the list each rule joins; the
 * canonical constructor takes three lists of one type in a row, so a swap there would compile.
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
   * Canonical constructor; defensively copies all three rule lists. Build through {@link
   * #newBuilder(PermissionMode)} instead.
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
   * Start building a permission in {@code mode}, with no rules.
   *
   * @param mode the coarse mode; non-null
   * @return a fresh builder
   * @throws NullPointerException if {@code mode} is null
   */
  public static Builder newBuilder(PermissionMode mode) {
    return new Builder(mode);
  }

  /**
   * Decide {@code key} for a tool of {@code category}, as the session does before asking or
   * running: the first matching {@code deny} rule, else the first matching {@code ask} rule, else
   * the first matching {@code allow} rule, each with the effect of its list; else the mode's
   * default for the category. {@link PermissionMode#BYPASS_PERMISSIONS} allows and {@link
   * PermissionMode#LOCKED_DOWN} denies every category; otherwise {@link ToolCategory#READ} and
   * {@link ToolCategory#CONTROL} are allowed, {@link ToolCategory#NETWORK} is asked, {@link
   * ToolCategory#WRITE} is asked (denied under {@link PermissionMode#PLAN}, allowed under {@link
   * PermissionMode#ACCEPT_EDITS}) and {@link ToolCategory#EXECUTION} is asked (denied under {@link
   * PermissionMode#PLAN}). Pure: no question is asked, nothing runs.
   *
   * @param key the call's canonical permission key; non-null
   * @param category the tool's category; non-null
   * @return the decision, carrying the deciding rule when a rule decided
   * @throws NullPointerException if either argument is null
   */
  public PermissionDecision evaluate(ToolPermissionKey key, ToolCategory category) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(category, "category must not be null");
    return firstMatch(PermissionEffect.DENY, deny, key)
        .or(() -> firstMatch(PermissionEffect.ASK, ask, key))
        .or(() -> firstMatch(PermissionEffect.ALLOW, allow, key))
        .orElseGet(() -> defaultFor(category, key.describe()));
  }

  private static Optional<PermissionDecision> firstMatch(
      PermissionEffect effect, List<PermissionRule> rules, ToolPermissionKey key) {
    return RuleMatcher.firstMatch(key, rules)
        .map(
            rule -> {
              var reason =
                  effect.name().toLowerCase(Locale.ROOT)
                      + " rule "
                      + rule.describe()
                      + " matched "
                      + key.describe();
              return new PermissionDecision(effect, reason, Optional.of(rule));
            });
  }

  private PermissionDecision defaultFor(ToolCategory category, String call) {
    return switch (mode) {
      case BYPASS_PERMISSIONS -> PermissionDecision.allow("BYPASS_PERMISSIONS allows " + call);
      case LOCKED_DOWN ->
          PermissionDecision.deny(
              "LOCKED_DOWN mode forbids "
                  + category
                  + " by default ("
                  + call
                  + ") — add an explicit allow rule to open this tool");
      default -> categoryDefault(category, call);
    };
  }

  private PermissionDecision categoryDefault(ToolCategory category, String call) {
    return switch (category) {
      case READ ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.allow("default-allow under PLAN (" + call + ")")
              : PermissionDecision.allow("default-allow READ (" + call + ")");
      case WRITE ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.deny("PLAN mode forbids writes (" + call + ")")
              : mode == PermissionMode.ACCEPT_EDITS
                  ? PermissionDecision.allow("ACCEPT_EDITS allows writes (" + call + ")")
                  : PermissionDecision.ask("default-ask on WRITE (" + call + ")");
      case EXECUTION ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.deny("PLAN mode forbids execution (" + call + ")")
              : PermissionDecision.ask("default-ask on EXECUTION (" + call + ")");
      case NETWORK -> PermissionDecision.ask("default-ask on NETWORK (" + call + ")");
      case CONTROL -> PermissionDecision.allow("default-allow CONTROL (" + call + ")");
    };
  }

  /**
   * The canonical "interactive workspace" preset: {@link PermissionMode#DEFAULT}, which allows
   * reads, listings and searches and asks before writes and execution, plus memory writes allowed
   * on {@code /memories/**}. No asks or denies — users add their own.
   *
   * @return a fresh preset
   */
  public static Permission defaultInWorkspace() {
    return newBuilder(PermissionMode.DEFAULT)
        .withAllow(PermissionRule.of("MemoryWrite", "/memories/**"))
        .build();
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
    return newBuilder(mode).build();
  }

  /**
   * The "sandbox is the world" preset: every category default-denies under {@link
   * PermissionMode#LOCKED_DOWN}; the only explicit allows are {@code Execute} (so the sandbox can
   * run code) and {@code AskUserQuestion} (so the model can still ask for clarification in a
   * session that registers it). No filesystem reads, no writes, no memory I/O. Use it for sessions
   * where the execution sandbox is the real security boundary and every other tool would be a hole.
   *
   * <p>Adding more tools to a session built with this preset will silently refuse them unless the
   * caller layers additional allow rules. That's intentional — a misconfigured tool registry should
   * fail closed, not open.
   *
   * @return a fresh preset
   */
  public static Permission lockedDown() {
    return newBuilder(PermissionMode.LOCKED_DOWN)
        .withAllow(PermissionRule.any("Execute"), PermissionRule.any("AskUserQuestion"))
        .build();
  }

  /**
   * Builds a {@link Permission} by named rule lists; not thread-safe, so confine one builder to a
   * thread. Each {@code with} method appends to its list, in order.
   */
  public static final class Builder {

    private final PermissionMode mode;
    private final List<PermissionRule> allow = new ArrayList<>();
    private final List<PermissionRule> ask = new ArrayList<>();
    private final List<PermissionRule> deny = new ArrayList<>();

    private Builder(PermissionMode mode) {
      this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    /**
     * Append rules that, on match, allow the call.
     *
     * @param rules the rules; none may be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code rules} or one of them is {@code null}
     */
    public Builder withAllow(PermissionRule... rules) {
      allow.addAll(List.of(rules));
      return this;
    }

    /**
     * Append rules that, on match, ask the user for confirmation.
     *
     * @param rules the rules; none may be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code rules} or one of them is {@code null}
     */
    public Builder withAsk(PermissionRule... rules) {
      ask.addAll(List.of(rules));
      return this;
    }

    /**
     * Append rules that, on match, refuse the call.
     *
     * @param rules the rules; none may be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code rules} or one of them is {@code null}
     */
    public Builder withDeny(PermissionRule... rules) {
      deny.addAll(List.of(rules));
      return this;
    }

    /**
     * Build the permission.
     *
     * @return the permission, holding copies of the rule lists
     */
    public Permission build() {
      return new Permission(mode, allow, ask, deny);
    }
  }
}
