/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.EventStream;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.SteeringQueueFullException;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link AgentSession} with no agent loop and one run: messages go into a real {@link
 * SteeringQueue} that nothing drains and each {@code send} returns that run's {@link #result},
 * event streams are fed by hand and end at {@link #completeEvents()}, and the run completes through
 * {@link #terminate()}, standing for the end of the loop. A closed session refuses a message, as a
 * real one does, while a run already in progress keeps running until terminated. It counts its
 * closes and interrupts, runs {@link #onSend} on each accepted message and {@link #onClose} on each
 * close, and counts the event streams its readers closed.
 */
final class StubSession implements AgentSession {

  /** The future of the run in progress; a test replaces it to start another run. */
  volatile CompletableFuture<ResultMessage> result = new CompletableFuture<>();

  final SteeringQueue queue;
  final List<FedEventStream> streams = new CopyOnWriteArrayList<>();
  final AtomicInteger closes = new AtomicInteger();
  final AtomicInteger interrupts = new AtomicInteger();
  volatile Runnable onSend = () -> {};
  volatile Runnable onClose = () -> {};

  private final String sessionId;
  private volatile boolean started;
  private volatile boolean closed;

  StubSession(String sessionId) {
    this(sessionId, 256);
  }

  StubSession(String sessionId, int queueCapacity) {
    this.sessionId = sessionId;
    this.queue = new SteeringQueue(queueCapacity);
  }

  /** Complete the result with a {@link ResultMessage.Success}. */
  void terminate() {
    result.complete(success());
  }

  /** The {@link ResultMessage.Success} a run of this session ends with. */
  ResultMessage success() {
    return new ResultMessage.Success(
        sessionId,
        "run-1",
        "ok",
        Response.Usage.of(0, 0),
        CostEstimate.zero(),
        Duration.ZERO,
        List.of());
  }

  /** End every event stream opened so far. */
  void completeEvents() {
    streams.forEach(FedEventStream::end);
  }

  /** The number of event streams their readers closed. */
  long closedStreams() {
    return streams.stream().filter(FedEventStream::isClosed).count();
  }

  @Override
  public CompletableFuture<ResultMessage> send(UserMessage message) {
    if (closed) {
      throw new IllegalStateException("session is closed");
    }
    if (!queue.offer(message)) {
      throw new SteeringQueueFullException("steering queue full");
    }
    started = true;
    var run = result;
    onSend.run();
    return run;
  }

  @Override
  public void interrupt() {
    interrupts.incrementAndGet();
  }

  @Override
  public EventStream events() {
    var stream = new FedEventStream();
    streams.add(stream);
    return stream;
  }

  @Override
  public String sessionId() {
    return sessionId;
  }

  @Override
  public long currentTurnIndex() {
    return 0;
  }

  @Override
  public boolean isRunning() {
    return started && !result.isDone();
  }

  @Override
  public void close() {
    closes.incrementAndGet();
    closed = true;
    onClose.run();
  }

  @Override
  public void answer(String questionId, AskUserQuestionResponse response) {}

  @Override
  public List<AskUserQuestionRequest> pendingQuestions() {
    return List.of();
  }

  @Override
  public <T> T runBlocking(UserMessage message, OutputSchema<T> schema) {
    throw new UnsupportedOperationException("the runtime never runs a session blocking");
  }
}
