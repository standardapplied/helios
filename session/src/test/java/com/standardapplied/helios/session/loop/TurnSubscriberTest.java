/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The turn's one terminal transition: whichever ending comes first — completion, the stream's
 * error, the idle deadline, the session's cancellation, an interrupt or a protocol violation —
 * snapshots the stream, cancels the subscription when the turn ended it, and makes every later
 * signal a no-op that emits nothing.
 */
final class TurnSubscriberTest {

  private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
  private static final Response.Usage USAGE = Response.Usage.of(7, 3);

  private final ManualScheduler scheduler = new ManualScheduler();
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final TurnSubscriber subscriber = newSubscriber();
  private final CountingSubscription subscription = new CountingSubscription();

  @AfterEach
  void stopScheduler() {
    scheduler.shutdownNow();
  }

  @Test
  void noIdleDeadlineIsArmedBeforeTheFirstChunk() {
    subscriber.onSubscribe(subscription);

    assertEquals(List.of(), scheduler.cancelled());
  }

  @Test
  void theFirstChunkArmsTheIdleDeadline() {
    subscriber.onSubscribe(subscription);

    subscriber.onNext(new ModelChunk.TextDelta("Hel"));

    assertEquals(List.of(false), scheduler.cancelled());
  }

  @Test
  void aKeepAliveReArmsTheIdleDeadline() {
    subscriber.onSubscribe(subscription);
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));

    subscriber.onNext(new ModelChunk.KeepAlive());

    assertEquals(List.of(true, false), scheduler.cancelled());
  }

  @Test
  void aKeepAliveAddsNothingToTheTurnAndEmitsNothing() {
    subscriber.onSubscribe(subscription);
    subscriber.onNext(new ModelChunk.KeepAlive());
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));
    subscriber.onNext(new ModelChunk.KeepAlive());
    subscriber.onNext(new ModelChunk.MessageStop("STOP", USAGE, Map.of()));

    subscriber.onComplete();
    var turn = subscriber.awaitDone(new CancellationToken());

    assertEquals(
        new StreamedTurn("Hel", List.of(), List.of(), FinishReason.STOP, USAGE, Map.of(), null),
        turn);
    assertEquals(List.of("Hel"), assistantTexts());
    assertEquals(1, events.size());
  }

  @Test
  void aStreamSilentAfterItsFirstChunkTimesOutCancelsOnceAndLateSignalsChangeNothing() {
    var call = ToolCall.newBuilder().withId("c1").withName("lookup").build();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));

    scheduler.fireLatest();
    scheduler.fireLatest();
    subscriber.onNext(new ModelChunk.TextDelta("lo"));
    subscriber.onNext(new ModelChunk.ToolUseStop(call));
    subscriber.onNext(new ModelChunk.MessageStop("STOP", USAGE, Map.of()));
    subscriber.onComplete();
    subscriber.onError(new IllegalStateException("late"));
    var turn = subscriber.awaitDone(new CancellationToken());

    assertEquals(1, subscription.cancels);
    assertEquals(List.of(Long.MAX_VALUE), subscription.requests);
    assertInstanceOf(TimeoutException.class, turn.error());
    assertEquals(FinishReason.ERROR, turn.finishReason());
    assertEquals("Hel", turn.content());
    assertEquals(List.of(), turn.toolCalls());
    assertEquals(Response.Usage.of(0, 0), turn.usage());
    assertEquals(List.of("Hel"), assistantTexts());
  }

  @Test
  void theSessionsCancellationCancelsTheSubscriptionOnce() {
    var token = new CancellationToken();
    subscriber.onSubscribe(subscription);
    token.cancel("session closed");

    var turn = subscriber.awaitDone(token);
    subscriber.onComplete();

    assertInstanceOf(CancellationException.class, turn.error());
    assertEquals(1, subscription.cancels);
    assertEquals(0, token.activeCallbackCountForTests());
  }

  @Test
  void aCancelThatThrowsStillReleasesTheWaiter() {
    subscriber.onSubscribe(throwingOnCancel(new IllegalStateException("close failed")));
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));

    var failure = assertThrows(IllegalStateException.class, scheduler::fireLatest);
    var turn =
        Await.value(
            "the turn released",
            CompletableFuture.supplyAsync(() -> subscriber.awaitDone(new CancellationToken())));

    assertEquals("close failed", failure.getMessage());
    assertInstanceOf(TimeoutException.class, turn.error());
  }

  @Test
  void anInterruptWhileWaitingCancelsTheSubscriptionAndStaysSet() {
    subscriber.onSubscribe(subscription);
    Thread.currentThread().interrupt();

    var turn = subscriber.awaitDone(new CancellationToken());

    assertTrue(Thread.interrupted());
    assertInstanceOf(InterruptedException.class, turn.error());
    assertEquals(1, subscription.cancels);
  }

  @Test
  void anEndingThatCancelsTheStreamCancelsTheSubscriptionHeld() {
    var failure = new AssertionError("request failed");
    subscriber.onSubscribe(subscription);

    subscriber.end(failure, true);
    var turn = subscriber.awaitDone(new CancellationToken());

    assertSame(failure, turn.error());
    assertEquals(1, subscription.cancels);
  }

  @Test
  void anEndingThatCancelsTheStreamBeforeAnySubscriptionEndsTheTurn() {
    var failure = new IllegalStateException("chatStream failed");

    subscriber.end(failure, true);

    assertSame(failure, subscriber.awaitDone(new CancellationToken()).error());
  }

  @Test
  void aSecondOnSubscribeIsCancelledAndTheFirstKept() {
    var second = new CountingSubscription();
    subscriber.onSubscribe(subscription);

    subscriber.onSubscribe(second);
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));
    scheduler.fireLatest();

    assertEquals(1, second.cancels);
    assertEquals(List.of(), second.requests);
    assertEquals(1, subscription.cancels);
  }

  @Test
  void aChunkBeforeOnSubscribeEndsTheTurnWithAnIllegalState() {
    subscriber.onNext(new ModelChunk.TextDelta("early"));

    var turn = subscriber.awaitDone(new CancellationToken());

    assertInstanceOf(IllegalStateException.class, turn.error());
    assertEquals("", turn.content());
    assertEquals(List.of(), events);
  }

  @Test
  void aCompletionWithoutAMessageStopEndsTheTurnWithAnIllegalState() {
    subscriber.onSubscribe(subscription);
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));

    subscriber.onComplete();
    var turn = subscriber.awaitDone(new CancellationToken());

    assertInstanceOf(IllegalStateException.class, turn.error());
    assertEquals(FinishReason.ERROR, turn.finishReason());
    assertEquals("Hel", turn.content());
  }

  @Test
  void aSubscriptionArrivingAfterTheTurnEndedIsCancelledUnrequested() {
    var token = new CancellationToken();
    token.cancel("closed before the stream subscribed");
    subscriber.awaitDone(token);

    subscriber.onSubscribe(subscription);

    assertEquals(1, subscription.cancels);
    assertEquals(List.of(), subscription.requests);
  }

  @Test
  void aCompletedStreamIsSnapshottedWithEveryEventEmittedAndNothingCancelled() {
    var call = ToolCall.newBuilder().withId("c1").withName("lookup").build();
    var cite = Citation.of("https://example.com", "snippet");
    subscriber.onSubscribe(subscription);

    subscriber.onNext(new ModelChunk.ThinkingDelta("Hm."));
    subscriber.onNext(new ModelChunk.TextDelta("Checking."));
    subscriber.onNext(new ModelChunk.ToolUseStart("c1", "lookup"));
    subscriber.onNext(new ModelChunk.ToolUseDelta("c1", "{}"));
    subscriber.onNext(new ModelChunk.ToolUseStop(call));
    subscriber.onNext(new ModelChunk.UsageDelta(USAGE));
    subscriber.onNext(
        new ModelChunk.MessageStop("TOOL_CALLS", USAGE, Map.of("k", "v"), List.of(cite)));
    subscriber.onComplete();
    var turn = subscriber.awaitDone(new CancellationToken());

    assertNull(turn.error());
    assertEquals("Checking.", turn.content());
    assertEquals(List.of(call), turn.toolCalls());
    assertEquals(FinishReason.TOOL_CALLS, turn.finishReason());
    assertEquals(USAGE, turn.usage());
    assertEquals(Map.of("k", "v"), turn.metadata());
    assertEquals(List.of(cite), turn.citations());
    assertEquals(
        List.of(
            QueryEvent.AssistantThinking.class,
            QueryEvent.AssistantText.class,
            QueryEvent.AssistantCitations.class),
        events.stream().map(Object::getClass).toList());
    assertEquals(0, subscription.cancels);
  }

  @Test
  void theStreamsOwnErrorEndsTheTurnWithoutCancellingIt() {
    var refused = new IllegalStateException("refused");
    subscriber.onSubscribe(subscription);
    subscriber.onNext(new ModelChunk.MessageStop("STOP", USAGE, Map.of()));

    subscriber.onError(refused);
    var turn = subscriber.awaitDone(new CancellationToken());

    assertSame(refused, turn.error());
    assertEquals(FinishReason.ERROR, turn.finishReason());
    assertEquals(USAGE, turn.usage());
    assertEquals(0, subscription.cancels);
  }

  @Test
  void anEndingWhileAChunksEventIsEmittedReleasesTheRunnerOnlyAfterThatEvent() {
    var emitting = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stalled =
        newSubscriber(
            event -> {
              emitting.countDown();
              Await.latch("the sink released", release);
            });
    stalled.onSubscribe(subscription);
    var delivery =
        CompletableFuture.runAsync(() -> stalled.onNext(new ModelChunk.TextDelta("Hel")));
    Await.latch("the chunk's event being emitted", emitting);

    Await.value("the idle deadline", CompletableFuture.runAsync(scheduler::fireLatest));
    var releasedWhileEmitting = stalled.released();
    release.countDown();
    Await.value("the chunk delivered", delivery);

    assertEquals(List.of(false, true), List.of(releasedWhileEmitting, stalled.released()));
    assertEquals(1, subscription.cancels);
  }

  private List<String> assistantTexts() {
    return events.stream()
        .filter(QueryEvent.AssistantText.class::isInstance)
        .map(event -> ((QueryEvent.AssistantText) event).text())
        .toList();
  }

  private TurnSubscriber newSubscriber() {
    return newSubscriber(events::add);
  }

  private TurnSubscriber newSubscriber(Consumer<QueryEvent> sink) {
    var state = new SessionState("sess", new CancellationToken(), CLOCK);
    var model =
        new BlockingModel() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("not called");
          }

          @Override
          public String id() {
            return "stub";
          }

          @Override
          public String provider() {
            return "stub";
          }
        };
    var emitter =
        new EventEmitter(
            sink,
            HookRegistry.empty(),
            s ->
                new DefaultHookContext(
                    s.sessionId(), s.currentTurnIndex(), s.cancellation(), model),
            CLOCK);
    return new TurnSubscriber(state, emitter, CLOCK, scheduler, Duration.ofHours(1));
  }

  private static Flow.Subscription throwingOnCancel(RuntimeException failure) {
    return new Flow.Subscription() {
      @Override
      public void request(long n) {}

      @Override
      public void cancel() {
        throw failure;
      }
    };
  }

  /** A subscription that counts every request and cancel it receives. */
  private static final class CountingSubscription implements Flow.Subscription {
    private final List<Long> requests = new ArrayList<>();
    private int cancels;

    @Override
    public synchronized void request(long n) {
      requests.add(n);
    }

    @Override
    public synchronized void cancel() {
      cancels++;
    }
  }
}
