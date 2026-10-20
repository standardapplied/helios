/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.session.tools.PathSpace;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolBinding.PathArgument;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

/**
 * The Permission system: authorizes each tool call against a {@link Permission} and a {@link
 * ToolRegistry}. The session runs it as a fixed stage of tool dispatch, after every {@code
 * PreToolUse} hook, on the exact call that runs if it is admitted; it is not a hook, so no hook
 * priority, outcome or failure moves, skips or follows it.
 *
 * <p>Evaluation order per spec §12.4:
 *
 * <ol>
 *   <li>Take the canonical call's key, which {@link ToolBinding#canonicalize} computed.
 *   <li>If any {@code deny} rule matches → {@link PermissionEffect#DENY}.
 *   <li>If {@link PermissionMode#BYPASS_PERMISSIONS} → {@link PermissionEffect#ALLOW}.
 *   <li>If any {@code allow} rule matches → {@link PermissionEffect#ALLOW}.
 *   <li>If any {@code ask} rule matches → {@link PermissionEffect#ASK}.
 *   <li>Default-by-category: {@link ToolCategory#READ} / {@link ToolCategory#SEARCH} → Allow;
 *       {@link ToolCategory#WRITE} / {@link ToolCategory#EXECUTION} / {@link ToolCategory#NETWORK}
 *       → Ask (Deny for writes and execution under {@link PermissionMode#PLAN}, Allow for writes
 *       under {@link PermissionMode#ACCEPT_EDITS}); {@link ToolCategory#CONTROL} / {@link
 *       ToolCategory#DELEGATION} → Allow. {@link PermissionMode#LOCKED_DOWN} denies every category.
 * </ol>
 *
 * <p>{@link #authorize} decides on the key it is handed, never on one recomputed from the call's
 * arguments, and fails closed: a call naming no registered tool, a key that names another tool, and
 * an ASK with no {@link ToolApprover} are each denied. A key carrying a session secret never
 * reaches it: the session's permission stage refuses that call first, with or without a policy. An
 * ASK goes to the approver with an {@link ApprovalRequest} for this call, its reason redacted
 * through the {@link SecretRegistry} since a rule's pattern may hold a secret the key does not, and
 * its arguments as compact JSON ({@link JsonSnapshot#write}), redacted, escaped and cut after 1024
 * characters (see {@link ApprovalRequest#arguments}); see {@link ToolApprover} for how its answer
 * is read.
 *
 * <p>{@link #decidingRule} answers which rule decides a key alone, if one does; tools that walk
 * below their authorised path withhold every entry their own DENY rules, or the DENY and ASK rules
 * of a tool whose content they return, decide.
 *
 * <p>Building an evaluator checks every rule's glob against the {@link PathSpace} of the tool it
 * names, or, for a tool not registered, of every walk that returns that tool's content: a rule
 * whose glob can never match a canonical path there is a configuration error.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. Safe to share across sessions when the approver is.
 */
public final class DefaultPermissionEvaluator {

  private static final int MAX_ARGUMENT_CHARS = 1024;

  private final Permission permission;
  private final ToolRegistry tools;
  private final RuleMatcher matcher;
  private final Optional<ToolApprover> approver;
  private final SecretRegistry secrets;

  private DefaultPermissionEvaluator(
      Permission permission, ToolRegistry tools, ToolApprover approver, SecretRegistry secrets) {
    this.permission = permission;
    this.tools = tools;
    this.matcher = new RuleMatcher();
    this.approver = Optional.ofNullable(approver);
    this.secrets = secrets;
  }

  /**
   * Start building an evaluator; without {@link Builder#withApprover} ASK refuses the call:
   *
   * <pre>{@code
   * var eval = DefaultPermissionEvaluator.newBuilder(perm, tools)
   *     .withApprover(ToolApprover.askingUser(gateway))
   *     .withSecretRegistry(sessionContext.secrets())
   *     .build();
   * }</pre>
   *
   * @param permission the policy to enforce; non-null
   * @param tools the tool registry; non-null
   * @return a fresh builder
   * @throws NullPointerException if either argument is null
   */
  public static Builder newBuilder(Permission permission, ToolRegistry tools) {
    return new Builder(permission, tools);
  }

  /**
   * The policy this evaluator enforces.
   *
   * @return the permission record
   */
  public Permission permission() {
    return permission;
  }

  /**
   * Decide whether the canonical call, exactly as given, may run, on its key. Never returns {@link
   * PermissionEffect#ASK}: an ASK is resolved through the approver, which gets the canonical call
   * and key, before this returns.
   *
   * @param canonical the final call, canonicalised, and its key; non-null
   * @return an {@link PermissionEffect#ALLOW} or {@link PermissionEffect#DENY} decision
   * @throws NullPointerException if {@code canonical} is null
   */
  public PermissionDecision authorize(ToolBinding.CanonicalCall canonical) {
    Objects.requireNonNull(canonical, "canonical must not be null");
    var call = canonical.call();
    var registered = tools.get(call.name());
    if (registered.isEmpty()) {
      return PermissionDecision.deny("no tool named '" + call.name() + "' is registered");
    }
    var category = registered.orElseThrow().category();
    var key = canonical.key();
    if (!key.toolName().equals(call.name())) {
      return PermissionDecision.deny(
          "permission key of " + call.name() + " names another tool: " + describe(key));
    }
    var decision = evaluate(key, category);
    if (decision.effect() != PermissionEffect.ASK) {
      return decision;
    }
    var redactor = secrets.redactor();
    var reason = redactor.redact(decision.reason()).text();
    var arguments =
        Strings.truncated(
            redactor.redact(JsonSnapshot.write(call.arguments())).text(),
            MAX_ARGUMENT_CHARS,
            "\nArguments cut: %d more chars not shown");
    return approve(new ApprovalRequest(call, arguments, key, category, decision.rule(), reason));
  }

  private PermissionDecision approve(ApprovalRequest request) {
    if (approver.isEmpty()) {
      return PermissionDecision.deny("no approver for " + request.reason());
    }
    try {
      var answer = approver.orElseThrow().approve(request);
      if (answer == null) {
        return PermissionDecision.deny("approver returned null for " + request.reason());
      }
      return answer.effect() == PermissionEffect.ASK
          ? PermissionDecision.deny("approver returned ASK for " + request.reason())
          : answer;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return PermissionDecision.deny("interrupted while awaiting approval of " + request.reason());
    } catch (CancellationException e) {
      return PermissionDecision.deny(
          "approval of " + request.reason() + " cancelled: " + cancellationReason(e));
    } catch (RuntimeException e) {
      return PermissionDecision.deny(
          "approver threw on "
              + request.reason()
              + ": "
              + e.getClass().getName()
              + ": "
              + e.getMessage());
    }
  }

  /**
   * The rule that decides {@code key}, in {@link #evaluate}'s order: the first matching DENY rule
   * in any mode, then, unless the mode is {@link PermissionMode#BYPASS_PERMISSIONS}, the first
   * matching ALLOW rule, then the first matching ASK rule. Empty when a mode or category default
   * decides.
   *
   * @param key a permission key; non-null
   * @return the deciding rule, or empty
   * @throws NullPointerException if {@code key} is null
   */
  public Optional<PermissionRule> decidingRule(ToolPermissionKey key) {
    var denied = matcher.firstMatch(key, permission.deny());
    if (denied.isPresent() || permission.mode() == PermissionMode.BYPASS_PERMISSIONS) {
      return denied;
    }
    return matcher
        .firstMatch(key, permission.allow())
        .or(() -> matcher.firstMatch(key, permission.ask()));
  }

  /**
   * Evaluate a tool call's permission key against the policy.
   *
   * @param key the canonical permission key; non-null
   * @param category the tool's category; non-null
   * @return the resolved decision, carrying the rule that decided if one did
   */
  public PermissionDecision evaluate(ToolPermissionKey key, ToolCategory category) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(category, "category must not be null");

    var rule = decidingRule(key);
    if (rule.isPresent()) {
      return byRule(rule.orElseThrow(), key);
    }
    if (permission.mode() == PermissionMode.BYPASS_PERMISSIONS) {
      return PermissionDecision.allow("BYPASS_PERMISSIONS allows " + describe(key));
    }
    return defaultForCategory(category, permission.mode(), describe(key));
  }

  /** The {@code <tool>(<canonical args>)} form of a key, without parentheses for empty args. */
  static String describe(ToolPermissionKey key) {
    return key.toolName() + (key.canonicalArgs().isEmpty() ? "" : "(" + key.canonicalArgs() + ")");
  }

  /**
   * Why an approval was cancelled. {@link java.util.concurrent.CompletableFuture#get()} rethrows
   * the cancellation that completed a future as the cause of a new one, so the reason is the
   * cause's when there is one.
   */
  private static String cancellationReason(CancellationException e) {
    var cause = e.getCause();
    return cause == null ? e.getMessage() : cause.getMessage();
  }

  private static PermissionDecision byRule(PermissionRule rule, ToolPermissionKey key) {
    var pattern = rule.argPattern().map(glob -> "(" + glob + ")").orElse("");
    return PermissionDecision.byRule(
        rule,
        rule.effect().name().toLowerCase(Locale.ROOT)
            + " rule "
            + rule.toolName()
            + pattern
            + " matched "
            + describe(key));
  }

  private static PermissionDecision defaultForCategory(
      ToolCategory category, PermissionMode mode, String call) {
    if (mode == PermissionMode.LOCKED_DOWN) {
      return PermissionDecision.deny(
          "LOCKED_DOWN mode forbids "
              + category
              + " by default ("
              + call
              + ") — add an explicit allow rule to open this tool");
    }
    return switch (category) {
      case READ, SEARCH ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.allow("default-allow under PLAN (" + call + ")")
              : PermissionDecision.allow("default-allow READ/SEARCH (" + call + ")");
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
      case DELEGATION -> PermissionDecision.allow("default-allow DELEGATION (" + call + ")");
    };
  }

  /**
   * Fluent builder for {@link DefaultPermissionEvaluator}. Construct via {@link
   * DefaultPermissionEvaluator#newBuilder(Permission, ToolRegistry)}.
   */
  public static final class Builder {

    private final Permission permission;
    private final ToolRegistry tools;
    private ToolApprover approver;
    private SecretRegistry secrets = new SecretRegistry();

    private Builder(Permission permission, ToolRegistry tools) {
      this.permission = Objects.requireNonNull(permission, "permission must not be null");
      this.tools = Objects.requireNonNull(tools, "tools must not be null");
    }

    /**
     * Wire the {@link ToolApprover} that decides ASK calls; without one, ASK refuses.
     *
     * @param approver the approver; non-null
     * @return this builder
     * @throws NullPointerException if {@code approver} is null
     */
    public Builder withApprover(ToolApprover approver) {
      this.approver = Objects.requireNonNull(approver, "approver must not be null");
      return this;
    }

    /**
     * Set the secrets an approval's reason and arguments redact; defaults to a new empty registry.
     * Pass the session's registry.
     *
     * @param secrets the secret registry; non-null
     * @return this builder
     * @throws NullPointerException if {@code secrets} is null
     */
    public Builder withSecretRegistry(SecretRegistry secrets) {
      this.secrets = Objects.requireNonNull(secrets, "secrets must not be null");
      return this;
    }

    /**
     * Build the immutable evaluator.
     *
     * @return a fresh evaluator
     * @throws IllegalArgumentException if a rule for a registered path tool has a glob that no
     *     canonical path can match
     */
    public DefaultPermissionEvaluator build() {
      Stream.of(permission.deny(), permission.allow(), permission.ask())
          .flatMap(List::stream)
          .forEach(this::requireMatchable);
      return new DefaultPermissionEvaluator(permission, tools, approver, secrets);
    }

    /**
     * Refuse a rule whose glob no canonical path can match in a space the rule is matched in: the
     * registered tool's own, or, for a tool not registered, that of every walk that returns the
     * tool's content.
     */
    private void requireMatchable(PermissionRule rule) {
      if (rule.argPattern().isEmpty()) {
        return;
      }
      var glob = rule.argPattern().orElseThrow();
      var refusing = spacesOf(rule.toolName()).filter(space -> !space.admits(glob)).findFirst();
      if (refusing.isPresent()) {
        throw new IllegalArgumentException(
            "rule "
                + rule.toolName()
                + "("
                + glob
                + ") can never match: "
                + rule.toolName()
                + " keys are canonical paths such as "
                + refusing.orElseThrow().example());
      }
    }

    private Stream<PathSpace> spacesOf(String toolName) {
      var own = tools.get(toolName);
      var bindings = own.isPresent() ? own.stream() : tools.bindings().stream();
      return bindings
          .flatMap(binding -> binding.pathArgument().stream())
          .filter(path -> own.isPresent() || path.contentRulesOf().contains(toolName))
          .map(PathArgument::space);
    }
  }
}
