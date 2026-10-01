/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import ai.singlr.core.common.Ids;
import ai.singlr.core.model.ToolCall;
import ai.singlr.session.ask.AskUserQuestionOption;
import ai.singlr.session.ask.AskUserQuestionRequest;
import ai.singlr.session.ask.QuestionGateway;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.PreToolUseHook;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * The Permission system, packaged as a {@link PreToolUseHook} at priority {@code 50} so it fires
 * before user-supplied hooks (default priority {@code 100}). Translates a {@link Permission} +
 * {@link ToolRegistry} into the per-call decision and surfaces it as a {@link HookOutcome}.
 *
 * <p>Evaluation order per spec §12.4:
 *
 * <ol>
 *   <li>Resolve {@code permissionKey} from the tool + input via {@link ToolBinding}.
 *   <li>If any {@code deny} rule matches → {@link HookOutcome.Block}.
 *   <li>If {@link PermissionMode#BYPASS_PERMISSIONS} → {@link HookOutcome.Continue}.
 *   <li>If any {@code allow} rule matches → {@link HookOutcome.Continue}.
 *   <li>If any {@code ask} rule matches: when a {@link QuestionGateway} is wired, emit an {@code
 *       AskUserQuestion} and route the user's choice back into the loop as {@link
 *       HookOutcome.Continue} or {@link HookOutcome.Block}. When no gateway is wired (a degenerate
 *       configuration — the agent loop always wires one), ASK falls back to Block with a clear
 *       reason.
 *   <li>Default-by-category: {@link ToolCategory#READ} / {@link ToolCategory#SEARCH} → Continue (or
 *       Block under {@link PermissionMode#PLAN}); {@link ToolCategory#WRITE} / {@link
 *       ToolCategory#EXECUTION} / {@link ToolCategory#NETWORK} → Ask; {@link ToolCategory#CONTROL}
 *       / {@link ToolCategory#DELEGATION} → Continue.
 * </ol>
 *
 * <p>If the tool is not registered in the {@link ToolRegistry}, the evaluator returns {@link
 * HookOutcome.Continue} — the tool dispatch path will surface its own "tool not found" failure, and
 * the permission hook should not block on an unknown name.
 *
 * <h2>Question format</h2>
 *
 * The ASK path emits a two-option {@code AskUserQuestion} with header {@code "Permission"}, a
 * question body of the form {@code "Allow <toolName>(<canonicalArgs>)?"}, and options {@code
 * "Allow"} / {@code "Deny"}. The user's choice is matched case-insensitively against {@code
 * "allow"} to route as Continue; anything else is Block.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. Safe to share across sessions.
 */
public final class DefaultPermissionEvaluator implements PreToolUseHook {

  static final String ALLOW_LABEL = "Allow";
  static final String DENY_LABEL = "Deny";

  private final Permission permission;
  private final ToolRegistry tools;
  private final RuleMatcher matcher;
  private final int priority;
  private final Optional<QuestionGateway> questionGateway;

  /**
   * Build an evaluator with the spec's default priority of 50 and no question gateway. ASK
   * decisions fall back to Block — useful for tests and read-only deployments. Production sessions
   * that need to prompt the user wire a real gateway via {@link #newBuilder(Permission,
   * ToolRegistry)}.
   *
   * @param permission the policy to enforce; non-null
   * @param tools the tool registry (for category + permissionKey lookup); non-null
   * @throws NullPointerException if either argument is null
   */
  public DefaultPermissionEvaluator(Permission permission, ToolRegistry tools) {
    this(permission, tools, 50, null);
  }

  private DefaultPermissionEvaluator(
      Permission permission, ToolRegistry tools, int priority, QuestionGateway questionGateway) {
    this.permission = Objects.requireNonNull(permission, "permission must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    if (priority < 0) {
      throw new IllegalArgumentException("priority must be non-negative, got " + priority);
    }
    this.priority = priority;
    this.matcher = new RuleMatcher();
    this.questionGateway = Optional.ofNullable(questionGateway);
  }

  /**
   * Start building an evaluator with a custom priority and/or a {@link QuestionGateway}. The
   * required {@code permission} and {@code tools} arguments are supplied up-front; optional knobs
   * default sensibly and override fluently:
   *
   * <pre>{@code
   * var eval = DefaultPermissionEvaluator.newBuilder(perm, tools)
   *     .withQuestionGateway(gateway)
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

  @Override
  public int priority() {
    return priority;
  }

  @Override
  public String name() {
    return "DefaultPermissionEvaluator";
  }

  @Override
  public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");

    var bindingOpt = tools.get(call.name());
    if (bindingOpt.isEmpty()) {
      return HookOutcome.cont();
    }
    var binding = bindingOpt.orElseThrow();
    var key = binding.permissionKey(call.arguments());
    var decision = evaluate(key, binding.category());
    return switch (decision.effect()) {
      case ALLOW -> HookOutcome.cont();
      case ASK -> handleAsk(key, decision);
      case DENY -> HookOutcome.block("permission: " + decision.reason());
    };
  }

  /**
   * Route an ASK decision through the session's {@link QuestionGateway}, blocking the agent-loop's
   * virtual thread until the host calls {@link ai.singlr.session.AgentSession#answer(String,
   * ai.singlr.session.ask.AskUserQuestionResponse) AgentSession.answer}. Returns {@link
   * HookOutcome.Continue} only when the user's selection matches {@link #ALLOW_LABEL}
   * (case-insensitive); anything else is Block.
   */
  private HookOutcome handleAsk(
      ai.singlr.session.tools.ToolPermissionKey key, PermissionDecision decision) {
    if (questionGateway.isEmpty()) {
      return HookOutcome.block(
          "permission: ASK rule without handler — no QuestionGateway wired. " + decision.reason());
    }
    var argsSuffix = key.canonicalArgs().isEmpty() ? "" : "(" + key.canonicalArgs() + ")";
    var request =
        new AskUserQuestionRequest(
            "perm-" + Ids.newId(),
            "Permission",
            "Allow " + key.toolName() + argsSuffix + "?",
            List.of(
                new AskUserQuestionOption(ALLOW_LABEL, decision.reason()),
                new AskUserQuestionOption(DENY_LABEL, "Refuse this tool call")),
            false);
    try {
      var response = questionGateway.orElseThrow().ask(request);
      var picked = response.selectedLabels().stream().findFirst().orElse("").trim();
      if (picked.equalsIgnoreCase(ALLOW_LABEL)) {
        return HookOutcome.cont();
      }
      return HookOutcome.block("permission: user denied (" + decision.reason() + ")");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return HookOutcome.block("permission: interrupted while waiting for user");
    } catch (CancellationException e) {
      return HookOutcome.block("permission: cancelled while waiting for user");
    }
  }

  /**
   * Evaluate a tool call's permission key against the policy. Visible for testing.
   *
   * @param key the canonical permission key; non-null
   * @param category the tool's category; non-null
   * @return the resolved decision
   */
  public PermissionDecision evaluate(
      ai.singlr.session.tools.ToolPermissionKey key, ToolCategory category) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(category, "category must not be null");

    var denied = matcher.firstMatch(key, permission.deny());
    if (denied.isPresent()) {
      return PermissionDecision.deny("deny rule for " + key.toolName());
    }
    if (permission.mode() == PermissionMode.BYPASS_PERMISSIONS) {
      return PermissionDecision.allow("BYPASS_PERMISSIONS");
    }
    var allowed = matcher.firstMatch(key, permission.allow());
    if (allowed.isPresent()) {
      return PermissionDecision.allow("allow rule for " + key.toolName());
    }
    var asked = matcher.firstMatch(key, permission.ask());
    if (asked.isPresent()) {
      return PermissionDecision.ask("ask rule for " + key.toolName());
    }
    return defaultForCategory(category, permission.mode(), key.toolName());
  }

  private static PermissionDecision defaultForCategory(
      ToolCategory category, PermissionMode mode, String toolName) {
    if (mode == PermissionMode.LOCKED_DOWN) {
      return PermissionDecision.deny(
          "LOCKED_DOWN mode forbids "
              + category
              + " by default ("
              + toolName
              + ") — add an explicit allow rule to open this tool");
    }
    return switch (category) {
      case READ, SEARCH ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.allow("default-allow under PLAN (" + toolName + ")")
              : PermissionDecision.allow("default-allow READ/SEARCH (" + toolName + ")");
      case WRITE ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.deny("PLAN mode forbids writes (" + toolName + ")")
              : mode == PermissionMode.ACCEPT_EDITS
                  ? PermissionDecision.allow("ACCEPT_EDITS allows writes (" + toolName + ")")
                  : PermissionDecision.ask("default-ask on WRITE (" + toolName + ")");
      case EXECUTION ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.deny("PLAN mode forbids execution (" + toolName + ")")
              : PermissionDecision.ask("default-ask on EXECUTION (" + toolName + ")");
      case NETWORK -> PermissionDecision.ask("default-ask on NETWORK (" + toolName + ")");
      case CONTROL -> PermissionDecision.allow("default-allow CONTROL (" + toolName + ")");
      case DELEGATION -> PermissionDecision.allow("default-allow DELEGATION (" + toolName + ")");
    };
  }

  /**
   * Fluent builder for {@link DefaultPermissionEvaluator}. Construct via {@link
   * DefaultPermissionEvaluator#newBuilder(Permission, ToolRegistry)}.
   */
  public static final class Builder {

    private final Permission permission;
    private final ToolRegistry tools;
    private int priority = 50;
    private QuestionGateway questionGateway;

    private Builder(Permission permission, ToolRegistry tools) {
      this.permission = Objects.requireNonNull(permission, "permission must not be null");
      this.tools = Objects.requireNonNull(tools, "tools must not be null");
    }

    /**
     * Override the priority at which this evaluator runs in the {@code PreToolUseHook} chain. Lower
     * numbers fire earlier; the spec's default is 50.
     *
     * @param priority non-negative priority
     * @return this builder
     * @throws IllegalArgumentException if {@code priority} is negative
     */
    public Builder withPriority(int priority) {
      if (priority < 0) {
        throw new IllegalArgumentException("priority must be non-negative, got " + priority);
      }
      this.priority = priority;
      return this;
    }

    /**
     * Wire a {@link QuestionGateway} so ASK decisions surface as {@code AskUserQuestion} prompts
     * rather than silently falling back to Block.
     *
     * @param questionGateway the gateway; non-null
     * @return this builder
     * @throws NullPointerException if {@code questionGateway} is null
     */
    public Builder withQuestionGateway(QuestionGateway questionGateway) {
      this.questionGateway =
          Objects.requireNonNull(questionGateway, "questionGateway must not be null");
      return this;
    }

    /**
     * Build the immutable evaluator.
     *
     * @return a fresh evaluator
     */
    public DefaultPermissionEvaluator build() {
      return new DefaultPermissionEvaluator(permission, tools, priority, questionGateway);
    }
  }
}
