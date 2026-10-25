/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
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
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link AgentSession} with no agent loop: messages go into a real {@link SteeringQueue} that
 * nothing drains, event subscribers are held until {@link #completeEvents()}, and the result
 * completes through {@link #terminate()}, standing for the end of the loop. Like a real session,
 * closing it before the first message completes the result at once, while a started one keeps
 * running until terminated. It counts its closes and runs {@link #onClose} on each, and counts the
 * cancels of its event subscriptions.
 */
final class StubSession implements AgentSession {

  final CompletableFuture<ResultMessage> result = new CompletableFuture<>();
  final SteeringQueue queue;
  final List<Flow.Subscriber<? super QueryEvent>> subscribers = new CopyOnWriteArrayList<>();
  final AtomicInteger closes = new AtomicInteger();
  final AtomicInteger cancels = new AtomicInteger();
  volatile Runnable onClose = () -> {};

  /** Thrown by {@link #result()} once set, standing for a session that fails to answer. */
  volatile RuntimeException resultFailure;

  private final String sessionId;
  private volatile boolean started;

  StubSession(String sessionId) {
    this(sessionId, 256);
  }

  StubSession(String sessionId, int queueCapacity) {
    this.sessionId = sessionId;
    this.queue = new SteeringQueue(queueCapacity);
  }

  /** Complete the result with a {@link ResultMessage.Success}. */
  void terminate() {
    result.complete(
        new ResultMessage.Success(
            sessionId,
            "ok",
            Response.Usage.of(0, 0),
            CostEstimate.zero(),
            Duration.ZERO,
            List.of()));
  }

  /** End every event stream subscribed so far. */
  void completeEvents() {
    subscribers.forEach(Flow.Subscriber::onComplete);
  }

  @Override
  public void send(UserMessage message) {
    if (!queue.offer(message)) {
      throw new SteeringQueueFullException("steering queue full");
    }
    started = true;
  }

  @Override
  public void interrupt(String reason) {
    send(UserMessage.text(reason));
  }

  @Override
  public Flow.Publisher<QueryEvent> events() {
    return subscriber -> {
      subscribers.add(subscriber);
      subscriber.onSubscribe(
          new Flow.Subscription() {
            @Override
            public void request(long n) {}

            @Override
            public void cancel() {
              cancels.incrementAndGet();
            }
          });
    };
  }

  @Override
  public CompletableFuture<ResultMessage> result() {
    if (resultFailure != null) {
      throw resultFailure;
    }
    return result;
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
  public boolean isStarted() {
    return started;
  }

  @Override
  public void close() {
    closes.incrementAndGet();
    if (!started) {
      started = true;
      result.complete(
          new ResultMessage.Cancelled(
              sessionId,
              "session closed",
              Response.Usage.of(0, 0),
              CostEstimate.zero(),
              Duration.ZERO));
    }
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
