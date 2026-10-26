/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.LoopCollaborators;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.StopClassifier;
import com.standardapplied.helios.session.loop.ToolDispatch;
import com.standardapplied.helios.session.loop.TurnRunner;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete {@link AgentSession} implementation.
 *
 * <p>One instance per session. Builds the loop substrate ({@link SessionState}, {@link
 * SteeringQueue}, {@link HookRegistry}, {@link ToolDispatch}, {@link LoopCollaborators}, {@link
 * TurnRunner}, {@link StopClassifier}, {@link AgentLoop}) in the constructor, the loop through
 * {@link SessionWiring}, and notifies the execution provider last; its {@link SessionLifecycle}
 * defers starting the agent-loop virtual thread until the first {@link #send(UserMessage)} or
 * {@link #interrupt(String)} call so subscribers attached between construction and first send
 * observe every event. Open {@code AskUserQuestion} questions are its {@link PendingQuestions}.
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
  private final SessionEventPublisher events;

  /** Package-private for tests that assert the runtime's executors shut down. */
  final SessionLifecycle lifecycle;

  private final AgentLoop loop;
  private final CompletableFuture<ResultMessage> resultFuture = new CompletableFuture<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final PendingQuestions pendingQuestions;
  private final RawOutputCapturePolicy rawOutputCapturePolicy;
  private final OutputSchema<?> outputSchema;

  /**
   * Build a session from a composition record.
   *
   * @param options the configuration bundle; non-null
   * @throws NullPointerException if {@code options} is null
   */
  public AgentSessionImpl(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    this.sessionId = options.sessionId();
    this.rawOutputCapturePolicy = options.model().rawOutputCapturePolicy();
    this.outputSchema = options.outputSchema().orElse(null);
    var clock = options.clock();
    var cancellation = new CancellationToken();
    this.state = new SessionState(sessionId, cancellation, clock);
    options.systemPrompt().ifPresent(prompt -> this.state.history().append(Message.system(prompt)));
    var sessionContext =
        new SessionContext(sessionId, cancellation, clock, options.secretRegistry());
    this.steeringQueue = new SteeringQueue(options.maxQueuedUserMessages());
    this.lifecycle =
        new SessionLifecycle(
            state, sessionContext, options.executionProvider(), options.limits(), resultFuture);
    this.events = lifecycle.events();
    this.pendingQuestions = new PendingQuestions(state, events, clock);
    try {
      this.loop =
          SessionWiring.agentLoop(
              options, sessionContext, pendingQuestions, steeringQueue, events, state.ending());
    } catch (RuntimeException | Error e) {
      lifecycle.abandon();
      throw e;
    }
    lifecycle.open(loop);
  }

  @Override
  public void send(UserMessage message) {
    Objects.requireNonNull(message, "message must not be null");
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    if (state.isTerminal() || resultFuture.isDone()) {
      throw new IllegalStateException("session is terminal");
    }
    if (!steeringQueue.offer(message)) {
      throw new SteeringQueueFullException(
          "steering queue full at capacity " + steeringQueue.capacity());
    }
    lifecycle.startIfNeeded();
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
    if (state.isTerminal() || resultFuture.isDone()) {
      throw new IllegalStateException("session is terminal");
    }
    var synthetic = UserMessage.text("[interrupted by user: " + reason + "]");
    if (!steeringQueue.offer(synthetic)) {
      throw new SteeringQueueFullException(
          "steering queue full at capacity "
              + steeringQueue.capacity()
              + " — cannot enqueue interrupt");
    }
    lifecycle.startIfNeeded();
  }

  @Override
  public <T> T runBlocking(UserMessage message, OutputSchema<T> schema) {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    if (outputSchema != null
        && (!outputSchema.type().equals(schema.type())
            || !outputSchema.schema().equals(schema.schema()))) {
      throw new IllegalArgumentException(
          "runBlocking schema "
              + schema.type().getName()
              + " differs from the session's output schema "
              + outputSchema.type().getName());
    }
    var terminal = runBlocking(message);
    if (!(terminal instanceof ResultMessage.Success success)) {
      throw new IllegalStateException(
          "session terminated as "
              + terminal.getClass().getSimpleName()
              + "; cannot parse a typed result from a non-Success terminal");
    }
    if (Strings.isBlank(success.result())) {
      throw new IllegalStateException(
          "session ended with Success but produced no result to parse as "
              + schema.type().getSimpleName());
    }
    return StructuredContentParser.parse(
        success.result(), schema, SessionJson.STRUCTURED, rawOutputCapturePolicy);
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
  public boolean isStarted() {
    return lifecycle.isStarted();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    lifecycle.close();
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

  @Override
  public List<AskUserQuestionRequest> pendingQuestions() {
    return pendingQuestions.requests();
  }
}
