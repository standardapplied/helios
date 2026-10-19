/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * The Permission system: authorizes each tool call against a {@link Permission} and a {@link
 * ToolRegistry}. The session runs it as a fixed stage of tool dispatch, after every {@code
 * PreToolUse} hook, on the exact call that runs if it is admitted; it is not a hook, so no hook
 * priority, outcome or failure moves, skips or follows it.
 *
 * <p>Evaluation order per spec §12.4:
 *
 * <ol>
 *   <li>Resolve {@code permissionKey} from the tool + input via {@link ToolBinding}.
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
 * <p>{@link #authorize} fails closed: a call naming no registered tool, a permission key that
 * throws, is null, names another tool or carries a secret of its {@link SecretRegistry}, and an ASK
 * with no {@link ToolApprover} are each denied. A key carrying a secret is denied before any rule
 * or approver sees it, so no approval prompt or event shows the secret. An ASK goes to the approver
 * with an {@link ApprovalRequest} for this call, its reason redacted through the registry since a
 * rule's pattern may hold a secret the key does not; see {@link ToolApprover} for how its answer is
 * read.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. Safe to share across sessions when the approver is.
 */
public final class DefaultPermissionEvaluator {

  private final Permission permission;
  private final ToolRegistry tools;
  private final RuleMatcher matcher;
  private final Optional<ToolApprover> approver;
  private final SecretRegistry secrets;

  /**
   * Build an evaluator with no approver, so ASK refuses the call, and an empty secret registry.
   *
   * @param permission the policy to enforce; non-null
   * @param tools the tool registry (for category + permissionKey lookup); non-null
   * @throws NullPointerException if either argument is null
   */
  public DefaultPermissionEvaluator(Permission permission, ToolRegistry tools) {
    this(permission, tools, null, new SecretRegistry());
  }

  private DefaultPermissionEvaluator(
      Permission permission, ToolRegistry tools, ToolApprover approver, SecretRegistry secrets) {
    this.permission = Objects.requireNonNull(permission, "permission must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.matcher = new RuleMatcher();
    this.approver = Optional.ofNullable(approver);
    this.secrets = secrets;
  }

  /**
   * Start building an evaluator with a {@link ToolApprover}:
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
   * Decide whether {@code call}, exactly as given, may run. Never returns {@link
   * PermissionEffect#ASK}: an ASK is resolved through the approver before this returns.
   *
   * @param call the final call; non-null
   * @return an {@link PermissionEffect#ALLOW} or {@link PermissionEffect#DENY} decision
   * @throws NullPointerException if {@code call} is null
   */
  public PermissionDecision authorize(ToolCall call) {
    Objects.requireNonNull(call, "call must not be null");
    var binding = tools.get(call.name());
    if (binding.isEmpty()) {
      return PermissionDecision.deny("no tool named '" + call.name() + "' is registered");
    }
    var category = binding.orElseThrow().category();
    ToolPermissionKey key;
    try {
      key = binding.orElseThrow().permissionKey(call.arguments());
    } catch (RuntimeException e) {
      return PermissionDecision.deny(
          "permission key of " + call.name() + " failed: " + describe(e));
    }
    if (secrets.leaks(describe(key))) {
      return PermissionDecision.deny("permission key of " + call.name() + " carries a secret");
    }
    if (!key.toolName().equals(call.name())) {
      return PermissionDecision.deny(
          "permission key of " + call.name() + " names another tool: " + describe(key));
    }
    var decision = evaluate(key, category);
    if (decision.effect() != PermissionEffect.ASK) {
      return decision;
    }
    var reason = secrets.redactor().redact(decision.reason()).text();
    return approve(new ApprovalRequest(call, key, category, decision.rule(), reason));
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
      return PermissionDecision.deny("approver threw on " + request.reason() + ": " + describe(e));
    }
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

    var denied = matcher.firstMatch(key, permission.deny());
    if (denied.isPresent()) {
      return byRule(denied.orElseThrow(), key);
    }
    if (permission.mode() == PermissionMode.BYPASS_PERMISSIONS) {
      return PermissionDecision.allow("BYPASS_PERMISSIONS allows " + describe(key));
    }
    var allowed = matcher.firstMatch(key, permission.allow());
    if (allowed.isPresent()) {
      return byRule(allowed.orElseThrow(), key);
    }
    var asked = matcher.firstMatch(key, permission.ask());
    if (asked.isPresent()) {
      return byRule(asked.orElseThrow(), key);
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

  private static String describe(RuntimeException e) {
    return e.getClass().getName() + ": " + e.getMessage();
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
     * Set the secrets no permission key may carry and an approval reason redacts; defaults to a new
     * empty registry. Pass the session's registry.
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
     */
    public DefaultPermissionEvaluator build() {
      return new DefaultPermissionEvaluator(permission, tools, approver, secrets);
    }
  }
}
