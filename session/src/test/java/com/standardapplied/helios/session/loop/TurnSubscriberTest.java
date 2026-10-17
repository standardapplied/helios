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
import com.standardapplied.helios.session.test.LogCapture;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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
  private static final Duration NOT_REACHED = Duration.ofHours(1);
  private static final Duration SHORT_GRACE = Duration.ofMillis(50);

  private final ManualScheduler scheduler = new ManualScheduler();
  private final SessionState state = new SessionState("sess", new CancellationToken(), CLOCK);
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private final TurnSubscriber subscriber = newSubscriber();
  private final CountingSubscription subscription = new CountingSubscription();

  private final CountDownLatch released = new CountDownLatch(1);

  @AfterEach
  void stopScheduler() {
    released.countDown();
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
  void anEarlierCancellationCallbackThatBlocksCannotKeepTheStreamOpen() {
    var token = new CancellationToken();
    token.onCancel(
        () -> {
          try {
            released.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    subscriber.onSubscribe(subscription);
    var waiting = CompletableFuture.supplyAsync(() -> subscriber.awaitDone(token));
    Await.until("the subscriber's registration", () -> token.activeCallbackCountForTests() == 2);

    token.cancelAsync("session closed", error -> {});

    assertInstanceOf(CancellationException.class, Await.value("the turn", waiting).error());
  }

  @Test
  void anExceptionTheCancelThrowsRidesOnTheTurnsFailureAsSuppressed() {
    var cleanup = new IllegalStateException("close failed");
    subscriber.onSubscribe(cancelling(throwing(cleanup)));
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));

    scheduler.fireLatest();
    var turn = subscriber.awaitDone(new CancellationToken());

    assertInstanceOf(TimeoutException.class, turn.error());
    assertEquals(List.of(cleanup), List.of(turn.error().getSuppressed()));
  }

  @Test
  void anErrorTheCancelOfAStalledStreamThrowsBecomesTheTurnsFailureCarryingTheTimeout() {
    var cleanup = new AssertionError("close failed");
    subscriber.onSubscribe(cancelling(throwing(cleanup)));
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));

    scheduler.fireLatest();
    var turn = subscriber.awaitDone(new CancellationToken());

    assertSame(cleanup, turn.error());
    assertInstanceOf(TimeoutException.class, cleanup.getSuppressed()[0]);
  }

  @Test
  void anErrorTheCancelThrowsRidesOnAnErrorTheTurnEndedWith() {
    var failure = new AssertionError("request failed");
    var cleanup = new AssertionError("close failed");
    subscriber.onSubscribe(cancelling(throwing(cleanup)));

    subscriber.end(failure, true);
    var turn = subscriber.awaitDone(new CancellationToken());

    assertSame(failure, turn.error());
    assertEquals(List.of(cleanup), List.of(failure.getSuppressed()));
  }

  @Test
  void aCancelThatDoesNotReturnWithinItsGraceIsInterruptedAndReportedAndTheTurnStillEnds() {
    var interrupted = new CountDownLatch(1);
    var token = new CancellationToken();
    token.cancel("session closed");
    var stalled = newSubscriber(events::add, SHORT_GRACE);
    stalled.onSubscribe(cancelling(untilInterrupted(interrupted)));

    StreamedTurn turn;
    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      turn =
          Await.value(
              "the turn released", CompletableFuture.supplyAsync(() -> stalled.awaitDone(token)));
    }

    assertInstanceOf(CancellationException.class, turn.error());
    Await.latch("the cancel interrupted", interrupted);
    assertEquals(
        List.of(
            "cancelling the model stream of session sess did not return within PT0.05S; the"
                + " stream is not known to be closed"),
        logged.stream().map(LogRecord::getMessage).toList());
  }

  @Test
  void anErrorACancelThrowsAfterItsGraceIsTheTurnsFailureCarryingTheEnding() {
    var sentinel = new AssertionError("late close failure");
    var cancelling = new CompletableFuture<Thread>();
    var stalled = newSubscriber(events::add, SHORT_GRACE);
    stalled.onSubscribe(
        cancelling(
            () -> {
              cancelling.complete(Thread.currentThread());
              DispatchFixture.awaitThroughInterrupts(released);
              throw sentinel;
            }));
    var ending = new CancellationException("session closed");
    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      stalled.end(ending, true);
    }
    released.countDown();
    Await.termination("the cancel to throw", Await.value("the cancel to start", cancelling));

    var turn = stalled.awaitDone(new CancellationToken());

    assertSame(sentinel, turn.error());
    assertEquals(List.of(ending), List.of(sentinel.getSuppressed()));
  }

  @Test
  void anErrorACancelThrowsAfterTheTurnWasReturnedIsAStrayError() {
    var sentinel = new AssertionError("close failure after the turn");
    var cancelling = new CompletableFuture<Thread>();
    var stalled = newSubscriber(events::add, SHORT_GRACE);
    stalled.onSubscribe(
        cancelling(
            () -> {
              cancelling.complete(Thread.currentThread());
              DispatchFixture.awaitThroughInterrupts(released);
              throw sentinel;
            }));
    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      stalled.end(new CancellationException("session closed"), true);
    }
    stalled.awaitDone(new CancellationToken());

    released.countDown();
    Await.termination("the cancel to throw", Await.value("the cancel to start", cancelling));

    assertEquals(List.of(sentinel), state.strayErrors().all());
  }

  @Test
  void anInterruptWhileWaitingForTheCancelEndsTheWaitInterruptsTheCancelAndStaysSet() {
    var interrupted = new CountDownLatch(1);
    subscriber.onSubscribe(cancelling(untilInterrupted(interrupted)));
    Thread.currentThread().interrupt();

    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      subscriber.end(new IllegalStateException("request failed"), true);
    }

    assertTrue(Thread.interrupted());
    Await.latch("the cancel interrupted", interrupted);
  }

  @Test
  void anErrorCancellingARefusedSubscriptionIsThrown() {
    var cleanup = new AssertionError("close failed");
    subscriber.onSubscribe(subscription);

    var thrown =
        assertThrows(
            AssertionError.class, () -> subscriber.onSubscribe(cancelling(throwing(cleanup))));

    assertSame(cleanup, thrown);
  }

  @Test
  void anErrorCancellingARefusedSubscriptionEndsTheTurnWithItAndCancelsTheAcceptedOne() {
    var cleanup = new AssertionError("close failed");
    subscriber.onSubscribe(subscription);
    assertThrows(AssertionError.class, () -> subscriber.onSubscribe(cancelling(throwing(cleanup))));

    var turn = subscriber.awaitDone(new CancellationToken());

    assertEquals(List.of(cleanup, 1), List.of(turn.error(), subscription.cancels));
  }

  @Test
  void anExceptionCancellingARefusedSubscriptionIsLogged() {
    var cleanup = new IllegalStateException("close failed");
    subscriber.onSubscribe(subscription);

    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      subscriber.onSubscribe(cancelling(throwing(cleanup)));
    }

    assertEquals(
        List.of(Level.WARNING, cleanup),
        List.of(logged.getFirst().getLevel(), logged.getFirst().getThrown()));
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
  void anInterruptWhileAnotherThreadCancelsTheStreamWaitsForThatOutcomeAndStaysSet() {
    var cancelling = new CountDownLatch(1);
    var cleanup = new AssertionError("close failed");
    subscriber.onSubscribe(
        cancelling(
            () -> {
              cancelling.countDown();
              untilInterrupted(new CountDownLatch(1)).run();
              throw cleanup;
            }));
    subscriber.onNext(new ModelChunk.TextDelta("Hel"));
    var turn =
        new FutureTask<>(
            () -> Map.entry(subscriber.awaitDone(new CancellationToken()), Thread.interrupted()));
    var waiter = Thread.ofVirtual().start(turn);
    CompletableFuture.runAsync(scheduler::fireLatest);
    Await.latch("the idle deadline to be cancelling the stream", cancelling);

    waiter.interrupt();
    Await.until("the waiter to take the interrupt", () -> !waiter.isInterrupted());
    released.countDown();

    assertEquals(Map.entry(cleanup, true), errorAndInterrupt(Await.value("the turn", turn)));
  }

  @Test
  void aRequestThatThrowsEndsTheTurnWithItCancelsTheSubscriptionAndIsRethrown() {
    var failure = new AssertionError("request failed");
    var cancels = new AtomicInteger();
    var failing =
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            throw failure;
          }

          @Override
          public void cancel() {
            cancels.incrementAndGet();
          }
        };

    assertSame(failure, assertThrows(AssertionError.class, () -> subscriber.onSubscribe(failing)));

    var turn = CompletableFuture.supplyAsync(() -> subscriber.awaitDone(new CancellationToken()));
    assertSame(failure, Await.value("the turn", turn).error());
    assertEquals(1, cancels.get());
  }

  @Test
  void anErrorARequestThrowsAfterAnotherThreadEndedTheTurnIsTheTurnsFailureCarryingThatEnding() {
    var requesting = new CountDownLatch(1);
    var throwing = new CountDownLatch(1);
    var failure = new AssertionError("request failed");
    var delivery =
        CompletableFuture.runAsync(
            () ->
                subscriber.onSubscribe(
                    requesting(
                        () -> {
                          requesting.countDown();
                          Await.latch("the turn ended", throwing);
                          throw failure;
                        })));
    Await.latch("the request running", requesting);
    subscriber.end(new CancellationException("closed"), true);
    var turn = new FutureTask<>(() -> subscriber.awaitDone(new CancellationToken()));
    var waiter = Thread.ofVirtual().start(turn);
    Await.until(
        "the waiter to wait for the request",
        () -> waiter.getState() == Thread.State.TIMED_WAITING);

    throwing.countDown();

    assertSame(failure, Await.failure("the subscription delivered", delivery));
    assertSame(failure, Await.value("the turn", turn).error());
    assertInstanceOf(CancellationException.class, failure.getSuppressed()[0]);
  }

  @Test
  void anErrorTheStreamEndsWithAfterAnotherEndingIsTheTurnsFailureCarryingThatEnding() {
    var failure = new AssertionError("stream failed");
    subscriber.onSubscribe(subscription);
    subscriber.end(new CancellationException("closed"), true);

    subscriber.onError(failure);

    assertSame(failure, subscriber.awaitDone(new CancellationToken()).error());
    assertInstanceOf(CancellationException.class, failure.getSuppressed()[0]);
  }

  @Test
  void aRequestStillRunningPastTheGraceLeavesTheTurnItsEndingAndIsLogged() {
    var requesting = new CountDownLatch(1);
    var blocked = newSubscriber(events::add, SHORT_GRACE);
    CompletableFuture.runAsync(
        () ->
            blocked.onSubscribe(
                requesting(
                    () -> {
                      requesting.countDown();
                      Await.latch("the test released", released);
                    })));
    Await.latch("the request running", requesting);
    blocked.end(new CancellationException("closed"), false);

    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      var turn = blocked.awaitDone(new CancellationToken());

      assertInstanceOf(CancellationException.class, turn.error());
    }
    assertEquals(
        List.of(
            "requesting from the model stream of session sess did not return within PT0.05S"
                + " after the turn ended"),
        logged.stream().map(LogRecord::getMessage).toList());
  }

  @Test
  void anInterruptWhileARequestIsAwaitedEndsTheWaitAndStaysSet() {
    var requesting = new CountDownLatch(1);
    CompletableFuture.runAsync(
        () ->
            subscriber.onSubscribe(
                requesting(
                    () -> {
                      requesting.countDown();
                      Await.latch("the test released", released);
                    })));
    Await.latch("the request running", requesting);
    subscriber.end(new CancellationException("closed"), false);

    var turn =
        CompletableFuture.supplyAsync(
            () -> {
              Thread.currentThread().interrupt();
              return Map.entry(subscriber.awaitDone(new CancellationToken()), Thread.interrupted());
            });

    var ended = Await.value("the turn", turn);
    assertInstanceOf(CancellationException.class, ended.getKey().error());
    assertTrue(ended.getValue(), "the interrupt stays set");
  }

  @Test
  void anErrorAChunksEventThrowsEndsTheTurnWithItCancelsTheStreamAndIsRethrown() {
    var failure = new AssertionError("hook failed");
    var failing =
        newSubscriber(
            event -> {
              throw failure;
            });
    failing.onSubscribe(subscription);

    assertSame(
        failure,
        assertThrows(AssertionError.class, () -> failing.onNext(new ModelChunk.TextDelta("Hel"))));

    var turn = failing.awaitDone(new CancellationToken());
    assertSame(failure, turn.error());
    assertEquals(List.of(), List.of(failure.getSuppressed()));
    assertEquals(1, subscription.cancels);
  }

  @Test
  void anErrorAChunksEventThrowsAfterAnotherEndingIsTheTurnsFailureCarryingThatEnding() {
    var emitting = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var failure = new AssertionError("hook failed");
    var stalled =
        newSubscriber(
            event -> {
              emitting.countDown();
              Await.latch("the sink released", release);
              throw failure;
            });
    stalled.onSubscribe(subscription);
    var delivery =
        CompletableFuture.runAsync(() -> stalled.onNext(new ModelChunk.TextDelta("Hel")));
    Await.latch("the chunk's event being emitted", emitting);
    Await.value("the idle deadline", CompletableFuture.runAsync(scheduler::fireLatest));

    release.countDown();

    assertSame(failure, Await.failure("the chunk delivered", delivery));
    assertSame(failure, stalled.awaitDone(new CancellationToken()).error());
    assertInstanceOf(TimeoutException.class, failure.getSuppressed()[0]);
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
    return newSubscriber(sink, NOT_REACHED);
  }

  private TurnSubscriber newSubscriber(Consumer<QueryEvent> sink, Duration cancelGrace) {
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
    return new TurnSubscriber(state, emitter, CLOCK, scheduler, NOT_REACHED, cancelGrace);
  }

  private static Map.Entry<Throwable, Boolean> errorAndInterrupt(
      Map.Entry<StreamedTurn, Boolean> ended) {
    return Map.entry(ended.getKey().error(), ended.getValue());
  }

  private Runnable untilInterrupted(CountDownLatch interrupted) {
    return () -> {
      try {
        released.await();
      } catch (InterruptedException e) {
        interrupted.countDown();
      }
    };
  }

  private static Runnable throwing(RuntimeException failure) {
    return () -> {
      throw failure;
    };
  }

  private static Runnable throwing(Error failure) {
    return () -> {
      throw failure;
    };
  }

  private static Flow.Subscription cancelling(Runnable cancel) {
    return new Flow.Subscription() {
      @Override
      public void request(long n) {}

      @Override
      public void cancel() {
        cancel.run();
      }
    };
  }

  private static Flow.Subscription requesting(Runnable request) {
    return new Flow.Subscription() {
      @Override
      public void request(long n) {
        request.run();
      }

      @Override
      public void cancel() {}
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
