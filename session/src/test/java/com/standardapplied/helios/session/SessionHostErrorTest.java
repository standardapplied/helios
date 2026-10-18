/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.NOT_REACHED;
import static com.standardapplied.helios.session.EndingSessions.SHORT_GRACE;
import static com.standardapplied.helios.session.EndingSessions.SID;
import static com.standardapplied.helios.session.EndingSessions.binding;
import static com.standardapplied.helios.session.EndingSessions.callingTool;
import static com.standardapplied.helios.session.EndingSessions.failureOf;
import static com.standardapplied.helios.session.EndingSessions.grace;
import static com.standardapplied.helios.session.EndingSessions.model;
import static com.standardapplied.helios.session.EndingSessions.options;
import static com.standardapplied.helios.session.EndingSessions.slowGrace;
import static com.standardapplied.helios.session.EndingSessions.subscription;
import static com.standardapplied.helios.session.EndingSessions.withProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A host {@link Error} caught on any thread the session runs work on — the loop, the model stream's
 * signals and its cancel, a tool's worker, a cancellation callback, {@code onSessionEnd} — ends the
 * session, and the result fails with the first one recorded, the rest suppressed on it, never with
 * another model or tool call after it; one that arrives after the result settled is logged. Every
 * fixture that hangs blocks on {@link #released}, opened after each test.
 */
final class SessionHostErrorTest {

  private final CountDownLatch released = new CountDownLatch(1);
  private final List<String> order = new CopyOnWriteArrayList<>();
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

  @AfterEach
  void releaseHangingFixtures() {
    released.countDown();
  }

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
  void anErrorSubscribeThrowsAfterAcceptingASubscriptionCancelsItAndFailsTheResult() {
    var sentinel = new AssertionError("subscribe failure");
    var cancels = new AtomicInteger();
    var model =
        model(
            token ->
                subscriber -> {
                  subscriber.onSubscribe(subscription(() -> {}, cancels::incrementAndGet));
                  throw sentinel;
                });

    assertSame(sentinel, failureOf(model));
    assertEquals(1, cancels.get());
  }

  @Test
  void anErrorCancellingASubscriptionThatFailedTransientlyFailsTheResultWithoutARetry() {
    var sentinel = new AssertionError("cancel failure");
    var transientFailure = new TransientStreamException("connection reset", null, "test");
    var calls = new AtomicInteger();
    var model =
        model(
            token -> {
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
            token -> {
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
  void anErrorRequestingAfterTheStreamEndedWithAnErrorIsSuppressedOnThatError() {
    var streamed = new AssertionError("stream failure");
    var sentinel = new AssertionError("request failure");
    var model =
        model(
            token ->
                requesting(
                    subscriber -> {
                      subscriber.onError(streamed);
                      throw sentinel;
                    }));

    assertSame(streamed, failureOf(model));
    assertEquals(List.of(sentinel), List.of(streamed.getSuppressed()));
  }

  @Test
  void anErrorABlockingModelThrowsAfterCloseCancelledItsStreamFailsTheResult() {
    var sentinel = new AssertionError("model failure");
    var calling = new CompletableFuture<Thread>();
    var provider = new RecordingProvider(order);
    provider.end =
        () -> {
          released.countDown();
          Await.termination("the model call to throw", Await.value("the model call", calling));
        };
    var session =
        AgentSession.create(
            withProvider(
                    provider,
                    model(
                        token ->
                            BlockingModel.publisherOf(
                                () -> {
                                  calling.complete(Thread.currentThread());
                                  RecordingProvider.blockIgnoringInterrupts(released);
                                  throw sentinel;
                                },
                                token)))
                .withLimits(slowGrace())
                .build());
    session.send("go");
    Await.value("the model call to start", calling);

    session.close();

    assertSame(sentinel, Await.failure("the result to fail", session.result()));
  }

  @Test
  void anErrorACallbackOnAToolsTokenThrowsOnCloseFailsTheResult() {
    var sentinel = new AssertionError("tool callback failure");
    var started = new CountDownLatch(1);
    var tool =
        binding(
            "wait",
            (args, ctx) -> {
              ctx.cancellation()
                  .onCancel(
                      () -> {
                        throw sentinel;
                      });
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return ToolResult.failure("interrupted");
            });

    try (var session =
        AgentSession.create(
            callingTool(new RecordingProvider(order), tool).withLimits(slowGrace()).build())) {
      session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
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
  void anErrorOnSessionEndJoinsFromAFailedFutureFailsTheResultAsItself() {
    var sentinel = new AssertionError("end failure");
    var provider = new RecordingProvider(order);
    provider.end = () -> CompletableFuture.failedFuture(sentinel).join();

    try (var session = AgentSession.create(withProvider(provider).build())) {
      session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
  }

  @Test
  void anErrorATokenCounterJoinsFromAFailedFutureFailsTheResultAsItself() {
    var sentinel = new AssertionError("counter failure");

    var failure =
        failureOf(
            options(ScriptedModel.newBuilder().withTextTurn("done").build())
                .withTokenCounter(
                    messages -> CompletableFuture.<Long>failedFuture(sentinel).join()));

    assertSame(sentinel, failure);
  }

  @Test
  void anErrorACompactorJoinsFromAFailedFutureFailsTheResultAsItself() {
    var sentinel = new AssertionError("compactor failure");

    var failure =
        failureOf(
            options(ScriptedModel.newBuilder().withTextTurn("done").build())
                .withLimits(SessionLimits.newBuilder().withMaxContextTokens(1_000).build())
                .withTokenCounter(messages -> 1_000_000L)
                .withContextCompactor(
                    (history, state) ->
                        CompletableFuture.<CompactionResult>failedFuture(sentinel).join()));

    assertSame(sentinel, failure);
  }

  @Test
  void aModelStreamErrorLeavesTheLoopAsAToolErrorDoesWithoutALoopEnded() {
    var modelFailure = new AssertionError("model failure");
    var toolFailure = new AssertionError("tool failure");

    var model =
        endedRun(
            options(
                ScriptedModel.newBuilder()
                    .withStreamTurn(ModelStreams.failing(modelFailure))
                    .build()));
    var tool =
        endedRun(
            callingTool(
                new RecordingProvider(order),
                binding(
                    "fails",
                    (args, ctx) -> {
                      throw toolFailure;
                    })));

    assertEquals(
        List.of(Map.entry(modelFailure, List.of()), Map.entry(toolFailure, List.of())),
        List.of(model, tool));
  }

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
  void anErrorRecordedWhileTheLoopWaitsEndsTheSessionWithoutWaitingForItsNaturalEnd() {
    var sentinel = new AssertionError("late tool failure");
    var lateTool = new CountDownLatch(1);
    var secondTurn = new CountDownLatch(1);
    var tool =
        binding(
            "late",
            (args, ctx) -> {
              RecordingProvider.blockIgnoringInterrupts(lateTool);
              throw sentinel;
            });
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c", "late", Map.of()))
            .withStreamTurn(ModelStreams.stalled(secondTurn::countDown))
            .build();
    var limits =
        SessionLimits.newBuilder()
            .withToolTimeoutDefault(SHORT_GRACE)
            .withToolStopGrace(SHORT_GRACE)
            .withStreamIdleTimeout(NOT_REACHED)
            .withMaxWallClock(NOT_REACHED)
            .withTeardownGrace(NOT_REACHED)
            .build();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.create(
                options(model)
                    .withTools(new ToolRegistry(List.of(tool)))
                    .withLimits(limits)
                    .build())) {
      session.send("go");
      Await.latch("the second model turn to wait on its stalled stream", secondTurn);
      lateTool.countDown();

      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
    assertEquals(2, model.calls().size());
  }

  @Test
  void anErrorAToolThrowsAfterTheResultSettledIsLoggedAndChangesNothing() {
    var sentinel = new AssertionError("tool failure after the result");
    var started = new CountDownLatch(1);
    var throwing = new CountDownLatch(1);
    var tool =
        binding(
            "stuck",
            (args, ctx) -> {
              started.countDown();
              RecordingProvider.blockIgnoringInterrupts(throwing);
              throw sentinel;
            });

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.create(
                callingTool(new RecordingProvider(order), tool).withLimits(grace()).build())) {
      session.send("go");
      Await.latch("the tool to start", started);
      session.close();
      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));

      throwing.countDown();

      var severe = "an Error reached session " + SID + " after its result settled";
      Await.until(
          "the late Error to be logged",
          () -> logged.stream().anyMatch(record -> severe.equals(record.getMessage())));
      var reported =
          logged.stream().filter(record -> severe.equals(record.getMessage())).findFirst();
      assertEquals(
          List.of(Level.SEVERE, sentinel),
          List.of(reported.orElseThrow().getLevel(), reported.orElseThrow().getThrown()));
      assertInstanceOf(ResultMessage.Cancelled.class, session.result().join());
    }
  }

  @Test
  void aSessionWhoseLoopEndedWithAnErrorRefusesASend() {
    try (var session = failedByAHook()) {
      var refused = assertThrows(IllegalStateException.class, () -> session.send("again"));

      assertEquals("session is terminal", refused.getMessage());
    }
  }

  @Test
  void aSessionWhoseLoopEndedWithAnErrorRefusesAnInterrupt() {
    try (var session = failedByAHook()) {
      var refused = assertThrows(IllegalStateException.class, () -> session.interrupt("stop"));

      assertEquals("session is terminal", refused.getMessage());
    }
  }

  @Test
  void anErrorACancellationCallbackThrowsWhenTheWallClockRunsOutFailsTheResult() {
    var sentinel = new AssertionError("callback failure");
    var model = ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(() -> {})).build();
    var provider = new RecordingProvider(order);
    var limits =
        SessionLimits.newBuilder()
            .withMaxWallClock(SHORT_GRACE)
            .withStreamIdleTimeout(NOT_REACHED)
            .build();

    try (var session =
        AgentSession.create(withProvider(provider, model).withLimits(limits).build())) {
      provider
          .context
          .cancellation()
          .onCancel(
              () -> {
                throw sentinel;
              });
      session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
  }

  @Test
  void theLoopThreadThrowsTheResultsErrorToItsUncaughtExceptionHandler() {
    var sentinel = new AssertionError("hook failure");
    var sessionId = "sess-uncaught";
    var uncaught = new CompletableFuture<Throwable>();
    var previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          if (thread.getName().equals("helios-agent-loop-" + sessionId)) {
            uncaught.complete(error);
          }
        });
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();

    try (var session =
        AgentSession.create(
            failingAtStop(options(model), sentinel).withSessionId(sessionId).build())) {
      session.send("go");

      assertSame(sentinel, Await.value("the loop thread's uncaught Error", uncaught));
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous);
    }
  }

  private static AgentSession failedByAHook() {
    var sentinel = new AssertionError("hook failure");
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    var session = AgentSession.create(failingAtStop(options(model), sentinel).build());
    session.send("go");
    assertSame(sentinel, Await.failure("the result to fail", session.result()));
    return session;
  }

  /** What a session's result fails with, and the {@code LoopEnded} events it emitted. */
  private static Map.Entry<Throwable, List<QueryEvent.LoopEnded>> endedRun(
      SessionOptions.Builder options) {
    var events = new CollectingSubscriber();
    try (var session = AgentSession.create(options.build())) {
      session.events().subscribe(events);
      session.send("go");
      var failure = Await.failure("the result to fail", session.result());
      events.awaitDone();
      return Map.entry(failure, events.eventsOf(QueryEvent.LoopEnded.class));
    }
  }

  private static Model subscriptionFailing(AssertionError requestFailure, Runnable cancel) {
    return model(
        token ->
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

  private static Model throwingModel(AtomicInteger calls, Error failure) {
    return model(
        token -> {
          calls.incrementAndGet();
          throw failure;
        });
  }

  private static SessionOptions.Builder failingAtStop(
      SessionOptions.Builder options, AssertionError failure) {
    PreStopHook failing =
        (response, ctx) -> {
          throw failure;
        };
    return options.withHook(failing);
  }
}
