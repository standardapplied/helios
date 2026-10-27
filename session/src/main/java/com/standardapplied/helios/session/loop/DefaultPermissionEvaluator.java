/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.tools.PathSpace;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolBinding.PathArgument;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

/**
 * The Permission system: authorizes each tool call against a {@link Permission} and a {@link
 * ToolRegistry}. The session runs it as a fixed stage of tool dispatch, after every {@code
 * PreToolUse} hook, on the exact call that runs if it is admitted; it is not a hook, so no hook
 * priority, outcome or failure moves, skips or follows it.
 *
 * <p>{@link #authorize} decides the canonical call's key, which {@link ToolBinding#canonicalize}
 * computed, with {@link Permission#evaluate} for the registered tool's category: the most
 * restrictive matching rule, else the mode's default for the category. It decides on the key it is
 * handed, never on one recomputed from the call's arguments, and fails closed: a call naming no
 * registered tool and a key that names another tool are each denied. A key carrying a session
 * secret never reaches it: the session's permission stage refuses that call first. An ASK goes to
 * the {@link ToolApprover} with an {@link ApprovalRequest} for this call, its reason redacted
 * through the {@link SecretRegistry} since a rule's pattern may hold a secret the key does not, and
 * its arguments as compact JSON ({@link JsonSnapshot#write}), redacted, escaped and cut after 1024
 * characters (see {@link ApprovalRequest#arguments}); see {@link ToolApprover} for how its answer
 * is read.
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
  private final ToolApprover approver;
  private final SecretRegistry secrets;

  /**
   * Build the evaluator of {@code permission} over {@code tools}.
   *
   * @throws IllegalArgumentException if a rule has a glob that no canonical path of a space it is
   *     matched in can match
   */
  public DefaultPermissionEvaluator(
      Permission permission, ToolRegistry tools, ToolApprover approver, SecretRegistry secrets) {
    Stream.of(permission.deny(), permission.allow(), permission.ask())
        .flatMap(List::stream)
        .forEach(rule -> requireMatchable(tools, rule));
    this.permission = permission;
    this.tools = tools;
    this.approver = approver;
    this.secrets = secrets;
  }

  /**
   * Decide whether the canonical call, exactly as given, may run, on its key. Never returns {@link
   * PermissionEffect#ASK}: an ASK is resolved through the approver, which gets the canonical call
   * and key, before this returns.
   */
  public PermissionDecision authorize(ToolBinding.CanonicalCall canonical) {
    var call = canonical.call();
    var registered = tools.get(call.name());
    if (registered.isEmpty()) {
      return PermissionDecision.deny("no tool named '" + call.name() + "' is registered");
    }
    var category = registered.orElseThrow().category();
    var key = canonical.key();
    if (!key.toolName().equals(call.name())) {
      return PermissionDecision.deny(
          "permission key of " + call.name() + " names another tool: " + key.describe());
    }
    var decision = permission.evaluate(key, category);
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
    try {
      var answer = approver.approve(request);
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
   * Why an approval was cancelled. {@link java.util.concurrent.CompletableFuture#get()} rethrows
   * the cancellation that completed a future as the cause of a new one, so the reason is the
   * cause's when there is one.
   */
  private static String cancellationReason(CancellationException e) {
    var cause = e.getCause();
    return cause == null ? e.getMessage() : cause.getMessage();
  }

  /**
   * Refuse a rule whose glob no canonical path can match in a space the rule is matched in: the
   * registered tool's own, or, for a tool not registered, that of every walk that returns the
   * tool's content.
   */
  private static void requireMatchable(ToolRegistry tools, PermissionRule rule) {
    if (rule.argPattern().isEmpty()) {
      return;
    }
    var glob = rule.argPattern().orElseThrow();
    var refusing =
        spacesOf(tools, rule.toolName()).filter(space -> !space.admits(glob)).findFirst();
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

  private static Stream<PathSpace> spacesOf(ToolRegistry tools, String toolName) {
    var own = tools.get(toolName);
    var bindings = own.isPresent() ? own.stream() : tools.bindings().stream();
    return bindings
        .flatMap(binding -> binding.pathArgument().stream())
        .filter(path -> own.isPresent() || path.contentRulesOf().contains(toolName))
        .map(PathArgument::space);
  }
}
