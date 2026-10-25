/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.ask.QuestionGateway;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.LoopCollaborators;
import com.standardapplied.helios.session.loop.SessionEnding;
import com.standardapplied.helios.session.loop.StopClassifier;
import com.standardapplied.helios.session.loop.ToolDispatch;
import com.standardapplied.helios.session.loop.TurnRunner;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.memory.MemoryWriteTool;
import com.standardapplied.helios.session.permissions.DefaultPermissionEvaluator;
import com.standardapplied.helios.session.permissions.ToolApprover;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds one session's agent loop from its options: the tool registry with the built-in tools, the
 * tool dispatch, the hooks, the permission stage, the loop collaborators and the turn runner. The
 * permission stage is the {@link DefaultPermissionEvaluator} of the session's {@link
 * com.standardapplied.helios.session.permissions.Permission}, asking the user through the session's
 * questions, with the rules that decide a key withholding what walking tools return. It acquires
 * nothing, so a failure here leaves only what the caller acquired to release.
 */
final class SessionWiring {

  private SessionWiring() {}

  static AgentLoop agentLoop(
      SessionOptions options,
      SessionContext sessionContext,
      PendingQuestions pendingQuestions,
      SteeringQueue steeringQueue,
      SessionEventPublisher events,
      ScheduledExecutorService scheduler,
      SessionEnding ending) {
    var combinedTools = withBuiltins(options.tools(), options, pendingQuestions);
    var toolDispatch = new ToolDispatch(sessionContext, combinedTools, ending);
    var model = options.model();
    var evaluator = evaluator(options, combinedTools, pendingQuestions, sessionContext.secrets());
    var collaborators =
        LoopCollaborators.newBuilder()
            .withHooks(new HookRegistry(options.hooks(), sessionContext.secrets()))
            .withToolDispatch(toolDispatch)
            .withAuthorization(evaluator::authorize)
            .withRuleDecision(evaluator::ruleDecision)
            .withSteeringQueue(steeringQueue)
            .withEventSink(events::emit)
            .withHookContextFactory(
                s ->
                    new DefaultHookContext(
                        s.sessionId(), s.currentTurnIndex(), s.cancellation(), model))
            .withClock(options.clock())
            .build();
    var turnRunner =
        new TurnRunner(
            collaborators,
            model,
            options.costCalculator(),
            options.outputSchema().orElse(null),
            scheduler);
    return new AgentLoop(
        collaborators,
        turnRunner,
        new StopClassifier(options.secretRegistry()),
        options.tokenCounter(),
        options.contextCompactor());
  }

  /** The evaluator of the session's permission; building it refuses a rule no path can match. */
  private static DefaultPermissionEvaluator evaluator(
      SessionOptions options, ToolRegistry tools, QuestionGateway gateway, SecretRegistry secrets) {
    return DefaultPermissionEvaluator.newBuilder(options.permission(), tools)
        .withApprover(ToolApprover.askingUser(gateway))
        .withSecretRegistry(secrets)
        .build();
  }

  private static ToolRegistry withBuiltins(
      ToolRegistry userTools, SessionOptions options, QuestionGateway gateway) {
    var combined = new ArrayList<ToolBinding>(userTools.bindings().size() + 3);
    combined.addAll(userTools.bindings());
    combined.add(AskUserQuestionTool.binding(gateway));
    options
        .memoryBackend()
        .ifPresent(
            b -> {
              combined.add(MemoryReadTool.binding(b));
              combined.add(MemoryWriteTool.binding(b));
            });
    return new ToolRegistry(combined);
  }
}
