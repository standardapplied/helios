/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * How a session ends: a host {@link Error} keeps its identity on every path and ends the session,
 * the teardown releases what the session acquired in reverse order, and the result settles once,
 * after a bounded teardown that accounts for tool work still running. Every fixture that hangs
 * blocks on {@link #released}, opened after each test; the grace under test is {@link #SHORT_GRACE}
 * and every other limit is beyond the hang guard.
 */
final class SessionFinalizationTest {

  private static final String SID = "sess-final";
  private static final Duration SHORT_GRACE = Duration.ofMillis(50);
  private static final Duration NOT_REACHED = Duration.ofMinutes(10);

  private final CountDownLatch released = new CountDownLatch(1);
  private final List<String> order = new CopyOnWriteArrayList<>();
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

  @AfterEach
  void releaseHangingFixtures() {
    released.countDown();
  }

  // ── a host Error keeps its identity and ends the session ────────────────────

  @Test
  void anErrorTheModelStreamEndsWithFailsTheResultAfterTheOnlyModelCall() {
    var sentinel = new AssertionError("model failure");
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(ModelStreams.failing(sentinel))
            .withTextTurn("never")
            .build();

    try (var session = AgentSession.create(options(model).build())) {
      session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
    assertEquals(1, model.calls().size());
  }

  @Test
  void anErrorTheModelCallThrowsFailsTheResultAfterTheOnlyModelCall() {
    var sentinel = new AssertionError("model call failure");
    var calls = new AtomicInteger();
    var model = throwingModel(calls, sentinel);

    try (var session = AgentSession.create(options(model).build())) {
      session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
    assertEquals(1, calls.get());
  }

  @Test
  void anErrorSubscribingToTheModelStreamCancelsItsSubscriptionAndFailsTheResult() {
    var sentinel = new AssertionError("request failure");
    var cancels = new AtomicInteger();

    var failure = failureOf(subscriptionFailing(sentinel, cancels::incrementAndGet));

    assertSame(sentinel, failure);
    assertEquals(1, cancels.get());
  }

  @Test
  void aCancelThatThrowsAfterAnErrorSubscribingRidesOnItAsSuppressed() {
    var sentinel = new AssertionError("request failure");
    var cleanup = new IllegalStateException("close failed");

    var failure =
        failureOf(
            subscriptionFailing(
                sentinel,
                () -> {
                  throw cleanup;
                }));

    assertSame(sentinel, failure);
    assertEquals(List.of(cleanup), List.of(sentinel.getSuppressed()));
  }

  @Test
  void aCancelThatRethrowsTheErrorSubscribingAddsNoSuppressed() {
    var sentinel = new AssertionError("stream failure");

    var failure =
        failureOf(
            subscriptionFailing(
                sentinel,
                () -> {
                  throw sentinel;
                }));

    assertSame(sentinel, failure);
    assertEquals(0, sentinel.getSuppressed().length);
  }

  @Test
  void anErrorCancellingASubscriptionThatFailedTransientlyFailsTheResultWithoutARetry() {
    var sentinel = new AssertionError("cancel failure");
    var transientFailure = new TransientStreamException("connection reset", null, "test");
    var calls = new AtomicInteger();
    var model =
        model(
            () -> {
              calls.incrementAndGet();
              return subscriber ->
                  subscriber.onSubscribe(
                      subscription(
                          () -> {
                            throw transientFailure;
                          },
                          () -> {
                            throw sentinel;
                          }));
            });

    assertSame(sentinel, failureOf(model));
    assertEquals(1, calls.get());
  }

  @Test
  void anErrorRequestingAfterTheStreamFailedTransientlyFailsTheResultWithoutARetry() {
    var sentinel = new AssertionError("request failure");
    var calls = new AtomicInteger();
    var model =
        model(
            () -> {
              calls.incrementAndGet();
              return requesting(
                  subscriber -> {
                    subscriber.onError(new TransientStreamException("reset", null, "test"));
                    throw sentinel;
                  });
            });

    assertSame(sentinel, failureOf(model));
    assertEquals(1, calls.get());
  }

  @Test
  void anErrorRequestingAfterTheStreamCompletedFailsTheResult() {
    var sentinel = new AssertionError("request failure");
    var model =
        model(
            () ->
                requesting(
                    subscriber -> {
                      subscriber.onNext(
                          new ModelChunk.MessageStop("STOP", Response.Usage.of(1, 1), Map.of()));
                      subscriber.onComplete();
                      throw sentinel;
                    }));

    assertSame(sentinel, failureOf(model));
  }

  @Test
  void anErrorRequestingAfterTheStreamEndedWithAnErrorRidesOnThatErrorAsSuppressed() {
    var streamed = new AssertionError("stream failure");
    var sentinel = new AssertionError("request failure");
    var model =
        model(
            () ->
                requesting(
                    subscriber -> {
                      subscriber.onError(streamed);
                      throw sentinel;
                    }));

    assertSame(streamed, failureOf(model));
    assertEquals(List.of(sentinel), List.of(streamed.getSuppressed()));
  }

  @Test
  void anErrorRequestingFromASubscriptionDeliveredOnAnotherThreadFailsTheResult() {
    var sentinel = new AssertionError("request failure");
    var calls = new AtomicInteger();
    var model =
        model(
            () -> {
              calls.incrementAndGet();
              return subscriber ->
                  CompletableFuture.runAsync(
                      () ->
                          subscriber.onSubscribe(
                              subscription(
                                  () -> {
                                    throw sentinel;
                                  },
                                  () -> {})));
            });

    assertSame(sentinel, failureOf(model));
    assertEquals(1, calls.get());
  }

  @Test
  void anErrorAStreamEventHookThrowsAfterCloseCancelledTheStreamFailsTheResult() {
    var sentinel = new AssertionError("hook failure");
    var emitting = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var model =
        model(
            () ->
                subscriber ->
                    subscriber.onSubscribe(
                        subscription(
                            () ->
                                CompletableFuture.runAsync(
                                    () -> subscriber.onNext(new ModelChunk.TextDelta("Hel"))),
                            cancelled::countDown)));
    var session =
        AgentSession.create(
            options(model)
                .withHook(
                    (OnStreamEventHook)
                        (event, ctx) -> {
                          if (event instanceof QueryEvent.AssistantText) {
                            emitting.countDown();
                            Await.latch("the stream cancelled", cancelled);
                            throw sentinel;
                          }
                        })
                .build());
    session.send("go");
    Await.latch("the hook running", emitting);

    session.close();

    assertSame(sentinel, Await.failure("the result to fail", session.result()));
    assertInstanceOf(CancellationException.class, sentinel.getSuppressed()[0]);
  }

  @Test
  void anErrorCancellingAStalledStreamFailsTheResultCarryingTheTimeout() {
    var sentinel = new AssertionError("cancel failure");
    var model =
        model(
            () ->
                subscriber ->
                    subscriber.onSubscribe(
                        subscription(
                            () -> subscriber.onNext(new ModelChunk.KeepAlive()),
                            () -> {
                              throw sentinel;
                            })));
    var limits = SessionLimits.newBuilder().withStreamIdleTimeout(SHORT_GRACE).build();

    try (var session = AgentSession.create(options(model).withLimits(limits).build())) {
      session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
    assertInstanceOf(TimeoutException.class, sentinel.getSuppressed()[0]);
  }

  @Test
  void aStreamCancelThatNeverReturnsCannotKeepAClosedSessionFromSettling() {
    var requested = new CountDownLatch(1);
    var model =
        model(
            () ->
                subscriber ->
                    subscriber.onSubscribe(
                        subscription(
                            requested::countDown,
                            () -> RecordingProvider.blockIgnoringInterrupts(released))));
    var session = AgentSession.create(options(model).withLimits(grace()).build());
    session.send("go");
    Await.latch("the stream requested", requested);

    session.close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
  }

  // ── a failing or hanging teardown still settles the result ──────────────────

  @Test
  void aTeardownExceptionAfterALoopErrorRidesOnItAsSuppressed() {
    var primary = new AssertionError("hook failure");
    var cleanup = new IllegalStateException("end failure");
    var provider = new RecordingProvider(order);
    provider.end =
        () -> {
          throw cleanup;
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.create(
                failingAtStop(withProvider(provider), primary).withLimits(slowGrace()).build())) {
      session.send("go");

      assertSame(primary, Await.failure("the result to fail", session.result()));
    }
    assertEquals(List.of(cleanup), List.of(primary.getSuppressed()));
  }

  @Test
  void aTeardownErrorAfterALoopErrorRidesOnItAsSuppressed() {
    var primary = new AssertionError("hook failure");
    var cleanup = new AssertionError("end failure");
    var provider = new RecordingProvider(order);
    provider.end =
        () -> {
          throw cleanup;
        };

    try (var session =
        AgentSession.create(failingAtStop(withProvider(provider), primary).build())) {
      session.send("go");

      assertSame(primary, Await.failure("the result to fail", session.result()));
    }
    assertEquals(List.of(cleanup), List.of(primary.getSuppressed()));
  }

  @Test
  void anOnSessionEndThatNeverReturnsIsReportedAndTheResultStillSettles() {
    var provider = new RecordingProvider(order);
    provider.end = () -> RecordingProvider.blockIgnoringInterrupts(released);

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.create(withProvider(provider).withLimits(grace()).build())) {
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
    }
    assertReported(
        "onSessionEnd of RecordingProvider did not return within PT0.05S; the session's"
            + " provider resources are not known to be released, session "
            + SID);
  }

  @Test
  void aCancellationCallbackThatNeverReturnsIsReportedAndTheResultStillSettles() {
    var options =
        withProvider(new RecordingProvider(order))
            .withHook(
                (PreStopHook)
                    (response, ctx) -> {
                      ctx.cancellation()
                          .onCancel(() -> RecordingProvider.blockIgnoringInterrupts(released));
                      return HookOutcome.cont();
                    })
            .withLimits(grace());

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.create(options.build())) {
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
    }
    assertReported(
        "a callback of the cancellation of session " + SID + " did not return within PT0.05S");
  }

  @Test
  void anInterruptedCloseStopsWaitingForTheTeardownAndKeepsTheInterrupt() {
    var provider = new RecordingProvider(order);
    provider.end = () -> RecordingProvider.blockIgnoringInterrupts(released);
    var session = AgentSession.create(withProvider(provider).withLimits(slowGrace()).build());

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      Thread.currentThread().interrupt();
      session.close();
      assertTrue(Thread.interrupted(), "close keeps the caller's interrupt");
    }

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    assertReported(
        "onSessionEnd of RecordingProvider did not return within PT10M; the session's"
            + " provider resources are not known to be released, session "
            + SID);
  }

  @Test
  void anErrorACancellationCallbackThrowsOnCloseFailsTheResultAndCloseReturns() {
    var sentinel = new AssertionError("callback failure");
    var provider = new RecordingProvider(order);
    var session = AgentSession.create(withProvider(provider).build());
    provider
        .context
        .cancellation()
        .onCancel(
            () -> {
              throw sentinel;
            });

    session.close();

    assertSame(sentinel, Await.failure("the result to fail", session.result()));
    assertEquals(1, provider.ends.get());
  }

  @Test
  void anErrorACancellationCallbackThrewBeforeALaterOneThatNeverReturnsFailsTheResult() {
    var sentinel = new AssertionError("callback failure");
    var provider = new RecordingProvider(order);
    var session = AgentSession.create(withProvider(provider).withLimits(grace()).build());
    var cancellation = provider.context.cancellation();
    cancellation.onCancel(
        () -> {
          throw sentinel;
        });
    cancellation.onCancel(() -> RecordingProvider.blockIgnoringInterrupts(released));

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      session.close();
      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
  }

  @Test
  void aCancellationCallbackThatNeverReturnsCannotKeepAClosedModelStreamOpen() {
    var entered = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    var provider = new RecordingProvider(order);
    var session = AgentSession.create(withProvider(provider, model).withLimits(grace()).build());
    provider
        .context
        .cancellation()
        .onCancel(() -> RecordingProvider.blockIgnoringInterrupts(released));
    session.send("go");
    Await.latch("the model call to start", entered);

    session.close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
  }

  @Test
  void aCancellationCallbackThatNeverReturnsOnCloseIsReportedAndTheResultStillSettles() {
    var provider = new RecordingProvider(order);
    var session = AgentSession.create(withProvider(provider).withLimits(grace()).build());
    provider
        .context
        .cancellation()
        .onCancel(() -> RecordingProvider.blockIgnoringInterrupts(released));

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      session.close();
    }

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    assertReported(
        "a callback of the cancellation of session " + SID + " did not return within PT0.05S");
  }

  // ── one terminal result and one disposal ────────────────────────────────────

  @Test
  void closingARunningLoopEndsItOnceWithOneDisposal() {
    var entered = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    var provider = new RecordingProvider(order);

    try (var session = AgentSession.create(withProvider(provider, model).build())) {
      session.send("go");
      Await.latch("the model call to start", entered);
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
      session.close();
    }
    assertEquals(1, provider.ends.get());
  }

  @Test
  void closingDuringTheTeardownOfANaturalEndKeepsItsResultAndOneDisposal() {
    var provider = new RecordingProvider(order);
    var inTeardown = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    provider.end =
        () -> {
          inTeardown.countDown();
          RecordingProvider.blockIgnoringInterrupts(proceed);
        };

    try (var session =
        AgentSession.create(withProvider(provider).withLimits(slowGrace()).build())) {
      session.send("go");
      Await.latch("the teardown to reach onSessionEnd", inTeardown);
      session.close();
      proceed.countDown();

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
    }
    assertEquals(1, provider.ends.get());
  }

  @Test
  void closingDuringTheTeardownOfAWallClockEndKeepsItsResultAndOneDisposal() {
    var model = ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(() -> {})).build();
    var provider = new RecordingProvider(order);
    var inTeardown = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    provider.end =
        () -> {
          inTeardown.countDown();
          RecordingProvider.blockIgnoringInterrupts(proceed);
        };
    var limits =
        SessionLimits.newBuilder()
            .withMaxWallClock(SHORT_GRACE)
            .withStreamIdleTimeout(NOT_REACHED)
            .withTeardownGrace(NOT_REACHED)
            .build();

    try (var session =
        AgentSession.create(withProvider(provider, model).withLimits(limits).build())) {
      session.send("go");
      Await.latch("the teardown to reach onSessionEnd", inTeardown);
      session.close();
      proceed.countDown();

      assertInstanceOf(
          ResultMessage.ErrorMaxWallClock.class, Await.value("the result", session.result()));
    }
    assertEquals(1, provider.ends.get());
  }

  // ── subscribers cannot hold the result ──────────────────────────────────────

  @Test
  void aSubscriberThatNeverReturnsCannotKeepTheResultFromSettling() {
    var entered = new CountDownLatch(1);
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();

    try (var session = AgentSession.create(options(model).withLimits(grace()).build())) {
      session
          .events()
          .subscribe(
              new CollectingSubscriber(
                  event -> {
                    entered.countDown();
                    RecordingProvider.blockIgnoringInterrupts(released);
                  }));
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
    }
    Await.latch("the subscriber to have been stuck in an event", entered);
  }

  // ── repeated sessions return to baseline ────────────────────────────────────

  @Test
  void repeatedSessionsLeaveNoThreadExecutorOrSubscriptionBehind() {
    Set<Thread> threads = ConcurrentHashMap.newKeySet();
    for (var i = 0; i < 20; i++) {
      var model =
          ScriptedModel.newBuilder()
              .withToolCallsTurn(new ToolCall("c" + i, "t", Map.of()))
              .withTextTurn("done")
              .build();
      var tool =
          binding(
              "t",
              (args, ctx) -> {
                threads.add(Thread.currentThread());
                return ToolResult.success("ok");
              });
      PreStopHook recordingLoop =
          (response, ctx) -> {
            threads.add(Thread.currentThread());
            return HookOutcome.cont();
          };
      var events = new CollectingSubscriber(event -> threads.add(Thread.currentThread()));
      var options =
          options(model).withTools(new ToolRegistry(List.of(tool))).withHook(recordingLoop).build();

      try (var session = (AgentSessionImpl) AgentSession.create(options)) {
        session.events().subscribe(events);
        session.send("go");

        assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
        events.awaitDone();
        assertEquals(Optional.empty(), events.error());
        Await.termination(
            "the publisher executor to end", session.lifecycleForTests().events().executor());
        Await.termination("the scheduler to end", session.lifecycleForTests().scheduler());
      }
    }
    threads.forEach(thread -> Await.termination("thread " + thread.getName() + " to end", thread));
  }

  // ── tool work at session end ────────────────────────────────────────────────

  @Test
  void aToolThatStopsOnInterruptionHasEndedBeforeOnSessionEnd() {
    var started = new CountDownLatch(1);
    var tool =
        binding(
            "wait",
            (args, ctx) -> {
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                order.add("tool ended");
              }
              return ToolResult.failure("interrupted");
            });

    try (var session = AgentSession.create(callingTool(tool).withLimits(slowGrace()).build())) {
      session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    }
    assertEquals(List.of("tool ended", "onSessionEnd"), order);
  }

  @Test
  void aToolThatIgnoresItsTokenAndInterruptionIsReportedAndTheResultStillSettles() {
    var started = new CountDownLatch(1);
    var tool =
        binding(
            "stuck",
            (args, ctx) -> {
              started.countDown();
              RecordingProvider.blockIgnoringInterrupts(released);
              return ToolResult.success("too late");
            });

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.create(callingTool(tool).withLimits(grace()).build())) {
      session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    }
    assertReported(
        "tool 'stuck' (call c-stuck) still runs PT0.05S after session "
            + SID
            + " ended; its effects may land after the result settled");
  }

  @Test
  void aRunThatEndsOnItsOwnEndsAnUnknownCallBeforeOnSessionEnd() {
    var tool =
        binding(
            "lingers",
            (args, ctx) -> {
              var sessionEnded = new CountDownLatch(1);
              ctx.sessionContext().cancellation().onCancel(sessionEnded::countDown);
              RecordingProvider.blockIgnoringInterrupts(sessionEnded);
              order.add("tool ended");
              return ToolResult.success("late");
            });
    var limits =
        SessionLimits.newBuilder()
            .withToolTimeoutDefault(SHORT_GRACE)
            .withToolStopGrace(SHORT_GRACE)
            .withTeardownGrace(NOT_REACHED)
            .build();
    var events = new CollectingSubscriber();

    try (var session = AgentSession.create(callingTool(tool).withLimits(limits).build())) {
      session.events().subscribe(events);
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
    }
    var answered = events.eventsOf(QueryEvent.ToolResult.class).getFirst().result();
    assertInstanceOf(ToolOutcome.Unknown.class, answered.outcome());
    assertEquals(List.of("tool ended", "onSessionEnd"), order);
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static SessionOptions.Builder options(Model model) {
    return SessionOptions.newBuilder().withModel(model).withSessionId(SID);
  }

  private static SessionOptions.Builder withProvider(RecordingProvider provider) {
    return withProvider(provider, ScriptedModel.newBuilder().withTextTurn("done").build());
  }

  private static SessionOptions.Builder withProvider(RecordingProvider provider, Model model) {
    return options(model).withExecutionProvider(provider);
  }

  private SessionOptions.Builder callingTool(ToolBinding tool) {
    var provider = new RecordingProvider(order);
    var call = new ToolCall("c-" + tool.name(), tool.name(), Map.of());
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call).withTextTurn("done").build();
    return withProvider(provider, model).withTools(new ToolRegistry(List.of(tool)));
  }

  private static SessionOptions.Builder failingAtStop(
      SessionOptions.Builder options, AssertionError failure) {
    PreStopHook failing =
        (response, ctx) -> {
          throw failure;
        };
    return options.withHook(failing);
  }

  private static SessionLimits grace() {
    return SessionLimits.newBuilder().withTeardownGrace(SHORT_GRACE).build();
  }

  private static SessionLimits slowGrace() {
    return SessionLimits.newBuilder().withTeardownGrace(NOT_REACHED).build();
  }

  private static ToolBinding binding(String name, ToolExecutor executor) {
    return ToolBinding.newBuilder(
            Tool.newBuilder().withName(name).withDescription(name).withExecutor(executor).build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  private static Throwable failureOf(Model model) {
    try (var session = AgentSession.create(options(model).build())) {
      session.send("go");
      return Await.failure("the result to fail", session.result());
    }
  }

  private static Model subscriptionFailing(AssertionError requestFailure, Runnable cancel) {
    return model(
        () ->
            subscriber ->
                subscriber.onSubscribe(
                    subscription(
                        () -> {
                          throw requestFailure;
                        },
                        cancel)));
  }

  private static Flow.Publisher<ModelChunk> requesting(
      Consumer<Flow.Subscriber<? super ModelChunk>> request) {
    return subscriber ->
        subscriber.onSubscribe(subscription(() -> request.accept(subscriber), () -> {}));
  }

  private static Flow.Subscription subscription(Runnable request, Runnable cancel) {
    return new Flow.Subscription() {
      @Override
      public void request(long n) {
        request.run();
      }

      @Override
      public void cancel() {
        cancel.run();
      }
    };
  }

  private static Model throwingModel(AtomicInteger calls, Error failure) {
    return model(
        () -> {
          calls.incrementAndGet();
          throw failure;
        });
  }

  private static Model model(Supplier<Flow.Publisher<ModelChunk>> stream) {
    return new Model() {
      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        return stream.get();
      }

      @Override
      public String id() {
        return "throwing";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private void assertReported(String warning) {
    var warnings =
        logged.stream()
            .filter(record -> record.getLevel() == Level.WARNING)
            .map(LogRecord::getMessage)
            .toList();
    assertTrue(warnings.contains(warning), () -> "logged warnings: " + warnings);
  }
}
