/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.ask.QuestionGateway;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.DefaultPermissionEvaluator;
import com.standardapplied.helios.session.loop.HookRegistry;
import com.standardapplied.helios.session.loop.LoopCollaborators;
import com.standardapplied.helios.session.loop.SessionEnding;
import com.standardapplied.helios.session.loop.SessionHookContext;
import com.standardapplied.helios.session.loop.StopClassifier;
import com.standardapplied.helios.session.loop.StoredHistory;
import com.standardapplied.helios.session.loop.ToolApprover;
import com.standardapplied.helios.session.loop.ToolDispatch;
import com.standardapplied.helios.session.loop.ToolRegistry;
import com.standardapplied.helios.session.loop.TurnRunner;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.memory.MemoryWriteTool;
import com.standardapplied.helios.session.tools.ToolBinding;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds one session's agent loop from its options: the tool registry, the options' tools followed
 * by the built-in tools the options switch on (the memory tools for a memory backend, {@code
 * Execute} for an execution provider, {@code AskUserQuestion} when asked for), refusing a hand
 * {@code Execute} binding without a provider, whose start and end would never run; the tool
 * dispatch, the hooks, the permission stage, the loop collaborators and the turn runner. The
 * permission stage is the {@link DefaultPermissionEvaluator} of the session's {@link
 * com.standardapplied.helios.session.permissions.Permission}, asking the user through the session's
 * questions, with the rules that decide a key withholding what walking tools return. With a trace
 * listener, the loop's events reach the session's {@link SessionTraceRecorder} before its
 * publisher; without one, no recorder is built. It acquires nothing, so a failure here leaves only
 * what the caller acquired to release.
 */
final class SessionWiring {

  private final AgentLoop loop;
  private final Optional<SessionTraceRecorder> recorder;

  SessionWiring(
      SessionOptions options,
      SessionContext sessionContext,
      PendingQuestions pendingQuestions,
      SteeringQueue steeringQueue,
      SessionEventPublisher events,
      SessionEnding ending,
      StoredHistory storedHistory) {
    this.recorder =
        options
            .traceListener()
            .map(listener -> new SessionTraceRecorder(options, listener, ending));
    var tools = registry(options, pendingQuestions);
    var model = options.model();
    var evaluator =
        new DefaultPermissionEvaluator(
            options.permission(),
            tools,
            ToolApprover.askingUser(pendingQuestions),
            sessionContext.secrets());
    var collaborators =
        new LoopCollaborators(
            new HookRegistry(options.hooks(), sessionContext.secrets()),
            new ToolDispatch(sessionContext, tools, ending),
            evaluator::authorize,
            options.permission(),
            steeringQueue,
            recorder
                .<Consumer<QueryEvent>>map(traces -> recordedBy(traces, events))
                .orElse(events::emit),
            state -> SessionHookContext.of(state, model),
            options.clock());
    var turnRunner =
        new TurnRunner(
            collaborators, model, options.costCalculator(), options.outputSchema().orElse(null));
    this.loop =
        new AgentLoop(
            collaborators,
            turnRunner,
            new StopClassifier(options.secretRegistry()),
            options.tokenCounter(),
            options.contextCompactor(),
            storedHistory);
  }

  AgentLoop loop() {
    return loop;
  }

  /** The session's trace recorder; empty when the options name no trace listener. */
  Optional<SessionTraceRecorder> recorder() {
    return recorder;
  }

  private static Consumer<QueryEvent> recordedBy(
      SessionTraceRecorder recorder, SessionEventPublisher events) {
    return event -> {
      recorder.accept(event);
      events.emit(event);
    };
  }

  private static ToolRegistry registry(SessionOptions options, QuestionGateway gateway) {
    if (options.executionProvider().isEmpty()
        && options.tools().stream().anyMatch(tool -> ExecuteTool.NAME.equals(tool.name()))) {
      throw new IllegalArgumentException(
          "Execute requires withExecutionProvider(provider); remove the hand Execute binding");
    }
    var tools = new ArrayList<ToolBinding>(options.tools());
    options
        .memoryBackend()
        .ifPresent(
            backend -> {
              tools.add(MemoryReadTool.binding(backend));
              tools.add(MemoryWriteTool.binding(backend));
            });
    options.executionProvider().ifPresent(provider -> tools.add(ExecuteTool.binding(provider)));
    if (options.askUserQuestion()) {
      tools.add(AskUserQuestionTool.binding(gateway));
    }
    return new ToolRegistry(tools);
  }
}
