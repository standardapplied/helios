/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.hooks.PreStopHook;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A subscriber sees the terminal {@code LoopEnded} exactly once whenever it attaches: live if it
 * attached before the event was recorded, replayed if after. The first group drives the publisher
 * directly; the second drives a real session, where "after" is forced by attaching from inside
 * another subscriber's {@code onNext(LoopEnded)} or after the result has settled.
 */
final class SessionEventPublisherTest {

  private static final String SID = "sess-events";
  private static final Instant AT = Instant.parse("2026-10-02T00:00:00Z");
  private static final QueryEvent TEXT = new QueryEvent.AssistantText(SID, 0, AT, "hello");
  private static final QueryEvent.LoopEnded ENDED =
      new QueryEvent.LoopEnded(
          SID,
          0,
          AT,
          new ResultMessage.Cancelled(
              SID, "done", Usage.of(0, 0), CostEstimate.zero(), Duration.ZERO));

  @Test
  void aSubscriberAttachedBeforeTheTerminalReceivesTheLiveStreamAndIsCompletedByClose() {
    var publisher = new SessionEventPublisher(SID);
    var subscriber = Recorder.requestingAll();
    publisher.subscribe(subscriber);

    publisher.emit(TEXT);
    publisher.emit(ENDED);
    publisher.close();

    assertEquals(List.of(TEXT, ENDED), subscriber.eventsOnceCompleted());
    assertTrue(publisher.executor().isTerminated());
  }

  @Test
  void aSubscriberAttachedAfterTheTerminalReceivesOnlyThatEventThenCompletes() {
    var publisher = new SessionEventPublisher(SID);
    publisher.emit(TEXT);
    publisher.emit(ENDED);
    var beforeClose = Recorder.requestingAll();
    publisher.subscribe(beforeClose);
    publisher.close();
    var afterClose = Recorder.requestingAll();
    publisher.subscribe(afterClose);

    assertSame(ENDED, onlyEvent(beforeClose));
    assertSame(ENDED, onlyEvent(afterClose));
  }

  @Test
  void theReplayWaitsForTheSubscriberToRequest() {
    var publisher = endedPublisher();
    var subscriber = Recorder.requestingNothing();
    publisher.subscribe(subscriber);

    assertEquals(List.of(), subscriber.events);
    assertEquals(0, subscriber.completions.get());

    subscriber.subscription().request(1);
    subscriber.subscription().request(1);

    assertSame(ENDED, onlyEvent(subscriber));
  }

  @Test
  void aCancelledReplayDeliversNothing() {
    var publisher = endedPublisher();
    var subscriber = Recorder.requestingNothing();
    publisher.subscribe(subscriber);

    subscriber.subscription().cancel();
    subscriber.subscription().request(1);

    assertEquals(List.of(), subscriber.events);
    assertEquals(0, subscriber.completions.get());
  }

  @Test
  void aNonPositiveRequestFailsTheReplayOnce() {
    var publisher = endedPublisher();
    var subscriber = Recorder.requestingNothing();
    publisher.subscribe(subscriber);

    subscriber.subscription().request(0);
    subscriber.subscription().request(-1);
    subscriber.subscription().request(1);

    var failure = Await.failure("the replay to reject a non-positive request", subscriber.done);
    assertInstanceOf(IllegalArgumentException.class, failure);
    assertEquals("non-positive subscription request: 0", failure.getMessage());
    assertEquals(List.of(), subscriber.events);
    assertEquals(0, subscriber.completions.get());
  }

  @Test
  void aSubscriberAttachedAfterACloseWithoutATerminalIsCompletedAtOnce() {
    var publisher = new SessionEventPublisher(SID);
    publisher.emit(TEXT);
    publisher.close();
    var subscriber = Recorder.requestingNothing();
    publisher.subscribe(subscriber);

    assertEquals(List.of(), subscriber.eventsOnceCompleted());
  }

  @Test
  void aNullSubscriberIsRejected() {
    var publisher = new SessionEventPublisher(SID);

    assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
  }

  @Test
  void aSubscriberAttachedAfterTheResultSettledReceivesTheLoopEndedTheEarlyOneSaw() {
    try (var session = session()) {
      var early = Recorder.requestingAll();
      session.events().subscribe(early);
      session.send(UserMessage.text("hi"));
      var terminal = Await.value("the session to reach its terminal", session.result());

      var late = Recorder.requestingAll();
      session.events().subscribe(late);

      var replayed = assertInstanceOf(QueryEvent.LoopEnded.class, onlyEvent(late));
      assertSame(early.eventsOnceCompleted().getLast(), replayed);
      assertEquals(terminal, replayed.result());
    }
  }

  @Test
  void aSubscriberAttachedWhileLoopEndedIsBeingDeliveredSeesItExactlyOnce() {
    try (var session = session()) {
      var late = Recorder.requestingAll();
      var early =
          Recorder.requestingAll(
              event -> {
                if (event instanceof QueryEvent.LoopEnded) {
                  session.events().subscribe(late);
                }
              });
      session.events().subscribe(early);
      session.send(UserMessage.text("hi"));
      Await.value("the session to reach its terminal", session.result());

      assertSame(early.eventsOnceCompleted().getLast(), onlyEvent(late));
    }
  }

  @Test
  void aSubscriberAttachedAfterAPreStartCloseIsCompletedWithoutAnEvent() {
    var session = session();
    session.close();
    var subscriber = Recorder.requestingAll();

    session.events().subscribe(subscriber);

    assertEquals(List.of(), subscriber.eventsOnceCompleted());
  }

  @Test
  void aSubscriberAttachedAfterTheLoopEscapedWithAnErrorIsCompletedWithoutAnEvent() {
    PreStopHook erroring =
        (response, ctx) -> {
          throw new AssertionError("simulated unrecoverable error");
        };
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(new OneReplyModel())
                .withSessionId(SID)
                .withHook(erroring)
                .build())) {
      session.send(UserMessage.text("hi"));
      assertInstanceOf(
          AssertionError.class,
          Await.failure("the result to settle exceptionally", session.result()));
      var subscriber = Recorder.requestingAll();

      session.events().subscribe(subscriber);

      assertEquals(List.of(), subscriber.eventsOnceCompleted());
    }
  }

  private static SessionEventPublisher endedPublisher() {
    var publisher = new SessionEventPublisher(SID);
    publisher.emit(ENDED);
    publisher.close();
    return publisher;
  }

  private static AgentSession session() {
    return AgentSession.create(
        SessionOptions.newBuilder().withModel(new OneReplyModel()).withSessionId(SID).build());
  }

  private static QueryEvent onlyEvent(Recorder subscriber) {
    var events = subscriber.eventsOnceCompleted();
    assertEquals(1, events.size(), () -> "expected exactly one event, got " + events);
    return events.getFirst();
  }

  private static final class OneReplyModel implements Model {

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      return Response.newBuilder()
          .withContent("done")
          .withFinishReason(FinishReason.STOP)
          .withUsage(Usage.of(3, 2))
          .build();
    }

    @Override
    public String id() {
      return "test";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  /** Records every signal; {@code done} settles on the first completion or error. */
  private static final class Recorder implements Flow.Subscriber<QueryEvent> {

    final List<QueryEvent> events = new CopyOnWriteArrayList<>();
    final AtomicInteger completions = new AtomicInteger();
    final CompletableFuture<Void> done = new CompletableFuture<>();
    private final CompletableFuture<Flow.Subscription> subscription = new CompletableFuture<>();
    private final long initialRequest;
    private final Consumer<QueryEvent> afterEach;

    private Recorder(long initialRequest, Consumer<QueryEvent> afterEach) {
      this.initialRequest = initialRequest;
      this.afterEach = afterEach;
    }

    static Recorder requestingAll() {
      return requestingAll(event -> {});
    }

    static Recorder requestingAll(Consumer<QueryEvent> afterEach) {
      return new Recorder(Long.MAX_VALUE, afterEach);
    }

    static Recorder requestingNothing() {
      return new Recorder(0, event -> {});
    }

    @Override
    public void onSubscribe(Flow.Subscription subscribed) {
      subscription.complete(subscribed);
      if (initialRequest > 0) {
        subscribed.request(initialRequest);
      }
    }

    @Override
    public void onNext(QueryEvent event) {
      events.add(event);
      afterEach.accept(event);
    }

    @Override
    public void onError(Throwable throwable) {
      done.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      completions.incrementAndGet();
      done.complete(null);
    }

    Flow.Subscription subscription() {
      return Await.value("onSubscribe", subscription);
    }

    List<QueryEvent> eventsOnceCompleted() {
      Await.value("the event stream to complete", done);
      assertEquals(1, completions.get(), "onComplete is signalled exactly once");
      return List.copyOf(events);
    }
  }
}
