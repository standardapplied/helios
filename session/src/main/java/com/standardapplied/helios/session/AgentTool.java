/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.loop.ToolApprover;
import com.standardapplied.helios.session.loop.ToolDispatch;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.tools.ToolArgs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * The built-in {@code Agent} tool of a session with subagents: each call runs the named {@link
 * Subagent} as one fresh child session, as {@link SessionOptions.Builder#withSubagent} describes,
 * on the call's worker thread, which belongs to the parent's run ({@link Run#stateOfThisThread}).
 * Its deadline is the subagent's {@code maxWallClock} plus its {@code stopGrace} ({@link
 * #deadline}). The call's token closes the child, which cancels its run; once the child's run
 * ended, its spend is added to the parent run's totals and the child is closed. A child's host
 * {@link Error} is thrown as itself, so it fails the parent too, and a child that cannot start is a
 * failure whose text the child's secret registry redacted.
 */
final class AgentTool {

  /** The stable tool name advertised to the model. */
  static final String NAME = "Agent";

  /** What a child's model reads when its policy asks: a child has no one to ask. */
  static final String NO_ONE_TO_ASK =
      "a subagent has no one to ask; allow the tool in the subagent's permission";

  /** The approver of a child's session, which denies every call its policy asks about. */
  static final ToolApprover DENY_ASKS = request -> PermissionDecision.deny(NO_ONE_TO_ASK);

  /**
   * The run and tool call a child session was opened for, which its run spans name.
   *
   * @param sessionId the parent session's id
   * @param runId the parent run's id
   * @param toolCallId the id of the {@code Agent} call
   */
  record Parent(String sessionId, String runId, String toolCallId) {

    /** The attributes of the child's run spans that name this run and call. */
    Map<String, String> linkage() {
      return RunSpans.parentLinkage(sessionId, runId, toolCallId);
    }
  }

  private final Map<String, Subagent> subagents = new LinkedHashMap<>();
  private final SessionLimits limits;

  /** The tool over {@code subagents}, under the parent session's {@code limits}. */
  AgentTool(List<Subagent> subagents, SessionLimits limits) {
    subagents.forEach(subagent -> this.subagents.put(subagent.name(), subagent));
    this.limits = limits;
  }

  /** The binding the session registers when it has a subagent. */
  ToolBinding binding() {
    var names =
        subagents.values().stream()
            .map(subagent -> subagent.name() + ": " + subagent.description())
            .collect(Collectors.joining("\n"));
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Delegates a task to a subagent, which works on it alone and answers with its"
                    + " result. The subagents:\n"
                    + names)
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("agent")
                        .withType(ParameterType.STRING)
                        .withDescription("The subagent's name, one of:\n" + names)
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("prompt")
                        .withType(ParameterType.STRING)
                        .withDescription("The task, with everything the subagent needs to know.")
                        .withRequired(true)
                        .build()))
            .withExecutor(this::execute)
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.EXECUTION)
        .withPermissionKeyExtractor(
            args -> new ToolPermissionKey(NAME, ToolArgs.stringArg(args, "agent")))
        .build();
  }

  /**
   * The deadline of {@code call}: the named subagent's {@code maxWallClock} plus its {@code
   * stopGrace}, or empty when it names none.
   */
  Optional<Duration> deadline(ToolCall call) {
    if (!NAME.equals(call.name())) {
      return Optional.empty();
    }
    return Optional.ofNullable(subagents.get(ToolArgs.stringArg(call.arguments(), "agent")))
        .map(Subagent::options)
        .map(SessionOptions::limits)
        .map(child -> child.maxWallClock().plus(child.stopGrace()));
  }

  private ToolResult execute(Map<String, Object> args, ToolContext context) {
    var name = ToolArgs.stringArg(args, "agent");
    var subagent = subagents.get(name);
    if (subagent == null) {
      return ToolResult.failure(
          "Agent: no subagent is named \"" + name + "\"; the subagents are " + subagents.keySet());
    }
    var prompt = ToolArgs.stringArg(args, "prompt");
    if (Strings.isBlank(prompt)) {
      return ToolResult.failure("Agent: missing required 'prompt'");
    }
    var run = Run.stateOfThisThread();
    var options = childOptions(subagent, run.totals().cost());
    if (options.isEmpty()) {
      return failure(
          name,
          "did not start: the run has spent "
              + run.totals().cost().microUsd()
              + " of its "
              + limits.maxBudgetMicroUsd().orElseThrow()
              + " micro-USD budget");
    }
    var parent = new Parent(run.sessionId(), run.runId(), ToolDispatch.callOfThisThread().id());
    AgentSessionImpl child;
    try {
      child = new AgentSessionImpl(options.get(), Optional.of(parent));
    } catch (RuntimeException e) {
      var why = String.valueOf(hostError(e).getMessage());
      return failure(
          name, "could not start: " + options.get().secretRegistry().redactor().redact(why).text());
    }
    try {
      var result = child.send(UserMessage.text(prompt));
      var closing = context.cancellation().onCancel(child::close);
      ResultMessage terminal;
      try {
        terminal = result.join();
      } catch (CompletionException e) {
        throw hostError(e);
      } finally {
        closing.remove();
      }
      run.totals().accumulate(terminal.usage(), terminal.cost());
      return answer(name, terminal);
    } finally {
      // An interrupt of this call is no request to cut the child's teardown short.
      Thread.interrupted();
      child.close();
    }
  }

  /**
   * The child's options: the subagent's, under a fresh id and its name, and under the parent's
   * budget the lower of its own and what the parent run has left after {@code spent}; empty when
   * nothing is left.
   */
  private Optional<SessionOptions> childOptions(Subagent subagent, CostEstimate spent) {
    var own = subagent.options();
    var builder =
        own.toBuilder().withSessionId("sess-" + Ids.newId()).withAgentName(subagent.name());
    if (limits.maxBudgetMicroUsd().isEmpty()) {
      return Optional.of(builder.build());
    }
    var left = limits.maxBudgetMicroUsd().getAsLong() - spent.microUsd();
    if (left <= 0) {
      return Optional.empty();
    }
    var budget = Math.min(own.limits().maxBudgetMicroUsd().orElse(Long.MAX_VALUE), left);
    return Optional.of(
        builder.withLimits(own.limits().toBuilder().withMaxBudgetMicroUsd(budget).build()).build());
  }

  /**
   * {@code e}, unless it carries a host {@link Error} as a joined future's cause: that is thrown.
   */
  private static RuntimeException hostError(RuntimeException e) {
    if (e instanceof CompletionException && e.getCause() instanceof Error error) {
      throw error;
    }
    return e;
  }

  private static ToolResult failure(String name, String what) {
    return ToolResult.failure("Agent: subagent \"" + name + "\" " + what);
  }

  /** The call's answer: a child's {@code Success} is its result, any other terminal a failure. */
  private static ToolResult answer(String name, ResultMessage terminal) {
    return switch (terminal) {
      case ResultMessage.Success success -> ToolResult.success(success.result());
      case ResultMessage.ErrorMaxTurns limit ->
          ended(name, limit, "it used its " + limit.turnsUsed() + " turns");
      case ResultMessage.ErrorMaxBudgetUsd limit ->
          ended(name, limit, "it spent " + limit.microUsdSpent() + " micro-USD, past its budget");
      case ResultMessage.ErrorMaxWallClock limit ->
          ended(name, limit, "it ran " + limit.duration());
      case ResultMessage.ErrorDuringExecution failed ->
          ended(name, failed, failed.error().message());
      case ResultMessage.ErrorTransientStream failed ->
          ended(name, failed, failed.error().message());
      case ResultMessage.Refusal refusal -> ended(name, refusal, refusal.refusalText());
      case ResultMessage.Cancelled cancelled -> ended(name, cancelled, cancelled.reason());
    };
  }

  private static ToolResult ended(String name, ResultMessage terminal, String why) {
    return failure(name, "ended " + terminal.getClass().getSimpleName() + ": " + why);
  }
}
