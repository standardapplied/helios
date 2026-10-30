/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.loop.SessionJson;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.StoredHistory;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete {@link AgentSession} implementation.
 *
 * <p>One instance per session. Builds the session's own {@link SessionState} (its history, its
 * token and its ending), loads the stored conversation into the history when the options name a
 * store ({@link StoredHistory}), and builds the {@link SteeringQueue} and the {@link
 * SessionLifecycle} in the constructor, the loop through {@link SessionWiring}, and notifies the
 * execution provider last. The lifecycle's {@link SessionRuns} run one run at a time, each on a
 * virtual thread of its own with a {@link SessionState} of its own sharing the session's history.
 * Open questions are its {@link PendingQuestions}, each of which belongs to the run that asked it.
 *
 * <h2>Event delivery</h2>
 *
 * Events flow through the session's {@link SessionEventPublisher}: live fan-out into each reader's
 * bounded {@link EventStream}, across every run, until the session is closed.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Producer threads (HTTP, UI) call {@link #send}/{@link #interrupt}/{@link #close}
 * concurrently; {@code send} and a run's decision to end take one lock in {@link SessionRuns}.
 */
public final class AgentSessionImpl implements AgentSession {

  private final String sessionId;
  private final SessionEventPublisher events;

  /** Package-private for tests that assert the runtime's executors shut down. */
  final SessionLifecycle lifecycle;

  private final SessionRuns runs;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /** Package-private for tests that ask a question from a thread that outlived its run. */
  final PendingQuestions pendingQuestions;

  private final RawOutputCapturePolicy rawOutputCapturePolicy;
  private final OutputSchema<?> outputSchema;

  /**
   * Open a session from a composition record.
   *
   * @param options the configuration bundle; non-null
   * @throws NullPointerException if {@code options} is null
   * @throws SessionUnavailableException if the execution provider did not accept the session
   * @throws com.standardapplied.helios.session.store.SessionStoreException if the store cannot load
   *     the session; nothing was acquired
   */
  AgentSessionImpl(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    this.sessionId = options.sessionId();
    this.rawOutputCapturePolicy = options.model().rawOutputCapturePolicy();
    this.outputSchema = options.outputSchema().orElse(null);
    var clock = options.clock();
    var cancellation = new CancellationToken();
    var state = new SessionState(sessionId, cancellation, clock);
    options.systemPrompt().ifPresent(prompt -> state.history().append(Message.system(prompt)));
    var storedHistory =
        options
            .store()
            .map(store -> StoredHistory.open(store, state))
            .orElseGet(StoredHistory::none);
    var sessionContext =
        new SessionContext(sessionId, cancellation, clock, options.secretRegistry());
    var steeringQueue = new SteeringQueue(options.maxQueuedUserMessages());
    this.lifecycle =
        new SessionLifecycle(
            state,
            sessionContext,
            options.executionProvider(),
            options.limits(),
            steeringQueue,
            storedHistory);
    this.events = lifecycle.events();
    this.runs = lifecycle.runs();
    this.pendingQuestions = new PendingQuestions(Run::stateOfThisThread, events, clock);
    SessionWiring wiring;
    try {
      wiring =
          new SessionWiring(
              options,
              sessionContext,
              pendingQuestions,
              steeringQueue,
              events,
              state.ending(),
              storedHistory);
    } catch (RuntimeException | Error e) {
      lifecycle.abandon();
      throw e;
    }
    lifecycle.open(wiring.loop(), wiring.recorder());
  }

  @Override
  public CompletableFuture<ResultMessage> send(UserMessage message) {
    Objects.requireNonNull(message, "message must not be null");
    return runs.send(message);
  }

  @Override
  public void interrupt() {
    runs.interrupt();
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
          "run ended as "
              + terminal.getClass().getSimpleName()
              + "; cannot parse a typed result from a non-Success terminal");
    }
    if (Strings.isBlank(success.result())) {
      throw new IllegalStateException(
          "run ended with Success but produced no result to parse as "
              + schema.type().getSimpleName());
    }
    return StructuredContentParser.parse(
        success.result(), schema, SessionJson.BINDING, rawOutputCapturePolicy);
  }

  @Override
  public EventStream events() {
    return events.open();
  }

  @Override
  public String sessionId() {
    return sessionId;
  }

  @Override
  public long currentTurnIndex() {
    return runs.activeRun().map(run -> run.state().currentTurnIndex()).orElse(0L);
  }

  @Override
  public boolean isRunning() {
    return runs.isRunning();
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
