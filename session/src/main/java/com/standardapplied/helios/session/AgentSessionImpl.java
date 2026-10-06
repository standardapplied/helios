/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.ask.QuestionGateway;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.LoopCollaborators;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.StopClassifier;
import com.standardapplied.helios.session.loop.ToolDispatch;
import com.standardapplied.helios.session.loop.TurnRunner;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.memory.MemoryWriteTool;
import com.standardapplied.helios.session.permissions.DefaultPermissionEvaluator;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete {@link AgentSession} implementation.
 *
 * <p>One instance per session. Builds the loop substrate ({@link SessionState}, {@link
 * SteeringQueue}, {@link HookRegistry}, {@link ToolDispatch}, {@link LoopCollaborators}, {@link
 * TurnRunner}, {@link StopClassifier}, {@link AgentLoop}) in the constructor; its {@link
 * SessionLifecycle} defers starting the agent-loop virtual thread until the first {@link
 * #send(UserMessage)} or {@link #interrupt(String)} call so subscribers attached between
 * construction and first send observe every event. Open {@code AskUserQuestion} questions are its
 * {@link PendingQuestions}.
 *
 * <h2>Event delivery</h2>
 *
 * Events flow through the session's {@link SessionEventPublisher}: live fan-out with a bounded wait
 * on a slow subscriber, and replay of the terminal {@link QueryEvent.LoopEnded} to a subscriber
 * that attaches after the session ended.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Producer threads (HTTP, UI) call {@link #send}/{@link #interrupt}/{@link #close}
 * concurrently; the agent loop runs on a dedicated virtual thread. Atomic flags coordinate
 * lifecycle. The loop is the only writer to {@link SessionState}'s mutable fields aside from {@code
 * close()}'s pre-start terminal write, which is guarded by the same compare-and-set as the loop
 * launch — at most one of the two paths executes.
 */
public final class AgentSessionImpl implements AgentSession {

  private final String sessionId;
  private final SessionState state;
  private final SteeringQueue steeringQueue;
  private final SessionLimits limits;
  private final SessionEventPublisher events;
  private final SessionLifecycle lifecycle;
  private final AgentLoop loop;
  private final CompletableFuture<ResultMessage> resultFuture = new CompletableFuture<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final PendingQuestions pendingQuestions;
  private final RawOutputCapturePolicy rawOutputCapturePolicy;

  /**
   * Build a session from a composition record.
   *
   * @param options the configuration bundle; non-null
   * @throws NullPointerException if {@code options} is null
   */
  public AgentSessionImpl(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    this.sessionId = options.sessionId();
    this.limits = options.limits();
    this.rawOutputCapturePolicy = options.model().rawOutputCapturePolicy();
    var clock = options.clock();
    var concurrency = options.concurrency();
    var cancellation = new CancellationToken();
    this.state = new SessionState(sessionId, cancellation, clock);
    options.systemPrompt().ifPresent(prompt -> this.state.history().append(Message.system(prompt)));
    var sessionContext = new SessionContext(sessionId, cancellation, clock);
    this.steeringQueue = new SteeringQueue(concurrency.maxQueuedUserMessages());
    this.events = new SessionEventPublisher(sessionId);
    this.pendingQuestions = new PendingQuestions(state, events, clock);
    var combinedTools = withBuiltins(options.tools(), options, pendingQuestions);
    var toolDispatch = new ToolDispatch(sessionContext, combinedTools, concurrency);
    this.lifecycle =
        new SessionLifecycle(
            state, sessionContext, options.executionProvider(), events, resultFuture);
    var combinedHooks = new ArrayList<Hook>(options.hooks().size() + 1);
    options
        .permission()
        .ifPresent(
            p ->
                combinedHooks.add(
                    DefaultPermissionEvaluator.newBuilder(p, combinedTools)
                        .withQuestionGateway(pendingQuestions)
                        .build()));
    combinedHooks.addAll(options.hooks());
    var model = options.model();
    var collaborators =
        LoopCollaborators.newBuilder()
            .withHooks(new HookRegistry(combinedHooks))
            .withToolDispatch(toolDispatch)
            .withSteeringQueue(steeringQueue)
            .withEventSink(events::emit)
            .withHookContextFactory(
                s ->
                    new DefaultHookContext(
                        s.sessionId(), s.currentTurnIndex(), s.cancellation(), model))
            .withClock(clock)
            .build();
    var turnRunner =
        new TurnRunner(
            collaborators,
            model,
            options.costCalculator(),
            options.outputSchema().orElse(null),
            lifecycle.scheduler());
    this.loop =
        new AgentLoop(
            collaborators,
            turnRunner,
            new StopClassifier(),
            options.tokenCounter(),
            options.contextCompactor());
  }

  @Override
  public RawOutputCapturePolicy rawOutputCapturePolicy() {
    return rawOutputCapturePolicy;
  }

  @Override
  public void send(UserMessage message) {
    Objects.requireNonNull(message, "message must not be null");
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    if (state.isTerminal()) {
      throw new IllegalStateException("session is terminal");
    }
    if (!steeringQueue.offer(message)) {
      throw new IllegalStateException(
          "steering queue full at capacity " + steeringQueue.capacity());
    }
    lifecycle.startIfNeeded(loop, limits);
  }

  @Override
  public void interrupt(String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (Strings.isBlank(reason)) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    if (state.isTerminal()) {
      throw new IllegalStateException("session is terminal");
    }
    var synthetic = UserMessage.text("[interrupted by user: " + reason + "]");
    if (!steeringQueue.offer(synthetic)) {
      throw new IllegalStateException(
          "steering queue full at capacity "
              + steeringQueue.capacity()
              + " — cannot enqueue interrupt");
    }
    lifecycle.startIfNeeded(loop, limits);
  }

  @Override
  public Flow.Publisher<QueryEvent> events() {
    return events;
  }

  @Override
  public CompletableFuture<ResultMessage> result() {
    return resultFuture;
  }

  @Override
  public String sessionId() {
    return sessionId;
  }

  @Override
  public long currentTurnIndex() {
    return state.currentTurnIndex();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    state.cancellation().cancel("session closed");
    pendingQuestions.cancelAll();
    // If the loop has never started, the lifecycle completes the future so result().get() doesn't
    // hang. If the loop is running, it will observe the cancellation on its next iteration and
    // complete the future itself.
    lifecycle.closeBeforeStart();
  }

  /** Package-private accessor for tests that assert the runtime's executors shut down. */
  SessionLifecycle lifecycleForTests() {
    return lifecycle;
  }

  @Override
  public void answer(String questionId, AskUserQuestionResponse response) {
    Objects.requireNonNull(questionId, "questionId must not be null");
    if (Strings.isBlank(questionId)) {
      throw new IllegalArgumentException("questionId must not be blank");
    }
    Objects.requireNonNull(response, "response must not be null");
    if (!questionId.equals(response.questionId())) {
      throw new IllegalArgumentException(
          "response.questionId() '"
              + response.questionId()
              + "' does not match argument '"
              + questionId
              + "'");
    }
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    pendingQuestions.answer(questionId, response);
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
