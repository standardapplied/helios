/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Theme G regression test for the SSE-backpressure wedge. The agent loop emits events via {@code
 * AgentSessionImpl#safeEmit}, which offers each event to the session's {@link
 * java.util.concurrent.SubmissionPublisher} with a bounded wait and drops it on overflow. Before
 * the fix, {@code publisher.submit(...)} blocked the loop indefinitely when any subscriber filled
 * its 256-item buffer — a slow SSE client could pin the producer forever.
 *
 * <p>The tests drive a real session whose model streams a burst of text chunks on the loop thread.
 * A subscriber that stalls in its first {@code onNext} holds one event and lets the buffer fill, so
 * the next event can only be dropped; a subscriber that never stalls receives a burst that fits the
 * buffer whole.
 */
final class SafeEmitBackpressureTest {

  private static final int SUBSCRIBER_BUFFER = 256;

  @Test
  void offerWithTimeoutDoesNotWedgeOnSlowSubscriber() {
    var stalled = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var backlogDelivered = new CountDownLatch(1 + SUBSCRIBER_BUFFER);
    var overflowEmitted = new CountDownLatch(1);
    var subscriber =
        new RecordingSubscriber(
            event -> {
              if (event instanceof QueryEvent.UserMessageReceived) {
                stalled.countDown();
                block(release);
              }
              backlogDelivered.countDown();
            });
    var model =
        burstModel(
            SUBSCRIBER_BUFFER + 1,
            () -> block(stalled),
            () -> {
              overflowEmitted.countDown();
              block(backlogDelivered);
            });

    try (var session = session("sess-backpressure-slow", model)) {
      session.events().subscribe(subscriber);
      session.send(UserMessage.text("go"));

      Await.latch("the loop to emit past the stalled subscriber's full buffer", overflowEmitted);
      release.countDown();
      var terminal = Await.value("the session to finish", session.result());
      subscriber.awaitCompletion();

      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals(
          String.join("", chunks(SUBSCRIBER_BUFFER + 1)),
          success.result(),
          "the dropped event must not cost the turn its content");
      assertEquals(
          chunks(SUBSCRIBER_BUFFER),
          subscriber.texts(),
          "exactly the one event offered to the full buffer is dropped");
      var last = assertInstanceOf(QueryEvent.LoopEnded.class, subscriber.events.getLast());
      assertEquals(terminal, last.result());
    }
  }

  @Test
  void offerSucceedsForFastSubscriber() {
    var burst = 100;
    var subscriber = new RecordingSubscriber(event -> {});

    try (var session = session("sess-backpressure-fast", burstModel(burst, () -> {}, () -> {}))) {
      session.events().subscribe(subscriber);
      session.send(UserMessage.text("go"));

      assertInstanceOf(
          ResultMessage.Success.class, Await.value("the session to finish", session.result()));
      subscriber.awaitCompletion();

      assertEquals(chunks(burst), subscriber.texts(), "fast subscriber should not see drops");
    }
  }

  private static AgentSession session(String sessionId, Model model) {
    return AgentSession.create(
        SessionOptions.newBuilder().withModel(model).withSessionId(sessionId).build());
  }

  private static List<String> chunks(int count) {
    return IntStream.range(0, count).mapToObj(i -> "c" + i + " ").toList();
  }

  private static void block(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while blocked on a test latch", e);
    }
  }

  /**
   * Model whose stream emits {@code count} text chunks on the caller's thread — the agent loop —
   * running {@code beforeBurst} ahead of the first chunk and {@code afterBurst} behind the last.
   */
  private static Model burstModel(int count, Runnable beforeBurst, Runnable afterBurst) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new UnsupportedOperationException("chat() not used by the streaming loop");
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        return subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    beforeBurst.run();
                    chunks(count)
                        .forEach(text -> subscriber.onNext(new ModelChunk.TextDelta(text)));
                    afterBurst.run();
                    subscriber.onNext(
                        new ModelChunk.MessageStop(
                            FinishReason.STOP.name(), Usage.of(1, 1), Map.of()));
                    subscriber.onComplete();
                  }

                  @Override
                  public void cancel() {}
                });
      }

      @Override
      public String id() {
        return "test-burst";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  /** Records every event, running {@code afterEach} on the delivery thread once it has. */
  private static final class RecordingSubscriber implements Flow.Subscriber<QueryEvent> {

    final List<QueryEvent> events = new ArrayList<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Consumer<QueryEvent> afterEach;

    RecordingSubscriber(Consumer<QueryEvent> afterEach) {
      this.afterEach = afterEach;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(QueryEvent event) {
      events.add(event);
      afterEach.accept(event);
    }

    @Override
    public void onError(Throwable throwable) {
      failure.set(throwable);
      finished.countDown();
    }

    @Override
    public void onComplete() {
      finished.countDown();
    }

    void awaitCompletion() {
      Await.latch("the event stream to complete", finished);
      assertNull(failure.get(), "the event stream must complete without an error");
    }

    List<String> texts() {
      return events.stream()
          .filter(QueryEvent.AssistantText.class::isInstance)
          .map(event -> ((QueryEvent.AssistantText) event).text())
          .toList();
    }
  }
}
