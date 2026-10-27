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
import static com.standardapplied.helios.session.EndingSessions.stream;
import static com.standardapplied.helios.session.EndingSessions.withProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A host {@link Error} caught on any thread the session runs work on — the loop, the model stream's
 * signals and its cancel, a tool's worker, a cancellation callback, {@code onSessionEnd} — ends the
 * session, and the run in progress fails with the first one recorded, the rest suppressed on it,
 * never with another model or tool call after it; one that reaches a session no run carries it on,
 * while it closes or after it closed, is logged. Every fixture that hangs blocks on {@link
 * #released}, opened after each test.
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

    try (var session = AgentSession.open(options(model).build())) {
      var future = session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", future));
    }
    assertEquals(1, model.calls().size());
  }

  @Test
  void anErrorTheModelCallThrowsFailsTheResultAfterTheOnlyModelCall() {
    var sentinel = new AssertionError("model call failure");
    var calls = new AtomicInteger();
    var model = throwingModel(calls, sentinel);

    try (var session = AgentSession.open(options(model).build())) {
      var future = session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", future));
    }
    assertEquals(1, calls.get());
  }

  @Test
  void anErrorTheStreamsFirstReadThrowsClosesTheStreamAndFailsTheResult() {
    var sentinel = new AssertionError("read failure");
    var closed = new AtomicBoolean();

    var failure =
        failureOf(
            model(
                () ->
                    stream(
                        () -> {
                          throw sentinel;
                        },
                        () -> closed.set(true))));

    assertSame(sentinel, failure);
    assertTrue(closed.get());
  }

  @Test
  void anErrorClosingAStreamThatFailedTransientlyFailsTheResultWithoutARetry() {
    var sentinel = new AssertionError("close failure");
    var transientFailure = new TransientStreamException("connection reset", null, "test");
    var calls = new AtomicInteger();
    var model =
        model(
            () -> {
              calls.incrementAndGet();
              return stream(
                  () -> {
                    throw transientFailure;
                  },
                  () -> {
                    throw sentinel;
                  });
            });

    assertSame(sentinel, failureOf(model));
    assertEquals(1, calls.get());
  }

  @Test
  void anErrorClosingAStreamThatEndedWithAnErrorIsSuppressedOnThatError() {
    var streamed = new AssertionError("stream failure");
    var sentinel = new AssertionError("close failure");
    var model =
        model(
            () ->
                stream(
                    () -> {
                      throw streamed;
                    },
                    () -> {
                      throw sentinel;
                    }));

    assertSame(streamed, failureOf(model));
    assertEquals(List.of(sentinel), List.of(streamed.getSuppressed()));
  }

  @Test
  void anErrorAStreamEventHookThrowsFailsTheResultAsItself() {
    var sentinel = new AssertionError("hook failure");
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.of(
                    new ModelChunk.TextDelta("hi"),
                    new ModelChunk.MessageStop("STOP", Response.Usage.of(1, 1), Map.of())))
            .build();
    OnStreamEventHook failing =
        (event, ctx) -> {
          if (event instanceof QueryEvent.AssistantText) {
            throw sentinel;
          }
        };

    assertSame(sentinel, failureOf(options(model).withHook(failing)));
  }

  @Test
  void anErrorABlockingModelThrowsAfterCloseCancelledItsStreamFailsTheResult() {
    var sentinel = new AssertionError("model failure");
    var calling = new CountDownLatch(1);
    var session =
        AgentSession.open(
            withProvider(
                    new RecordingProvider(order),
                    model(
                        () ->
                            BlockingModel.streamOf(
                                () -> {
                                  calling.countDown();
                                  try {
                                    new CountDownLatch(1).await();
                                  } catch (InterruptedException closedTheStream) {
                                    throw sentinel;
                                  }
                                  throw new AssertionError("the call was released");
                                })))
                .withLimits(slowGrace())
                .build());
    var future = session.send("go");
    Await.latch("the model call to start", calling);

    session.close();

    assertSame(sentinel, Await.failure("the result to fail", future));
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
        AgentSession.open(
            callingTool(new RecordingProvider(order), tool).withLimits(slowGrace()).build())) {
      var future = session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      assertSame(sentinel, Await.failure("the result to fail", future));
    }
  }

  @Test
  void anErrorACancellationCallbackThrowsOnCloseIsLoggedAndCloseReturns() {
    var sentinel = new AssertionError("callback failure");
    var provider = new RecordingProvider(order);
    var session = AgentSession.open(withProvider(provider).build());
    provider
        .context
        .cancellation()
        .onCancel(
            () -> {
              throw sentinel;
            });

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      session.close();
    }

    assertSame(sentinel, closingError().getThrown());
    assertEquals(1, provider.ends.get());
  }

  @Test
  void anErrorACancellationCallbackThrewBeforeALaterOneThatNeverReturnsIsLogged() {
    var sentinel = new AssertionError("callback failure");
    var provider = new RecordingProvider(order);
    var session = AgentSession.open(withProvider(provider).withLimits(grace()).build());
    var cancellation = provider.context.cancellation();
    cancellation.onCancel(
        () -> {
          throw sentinel;
        });
    cancellation.onCancel(() -> Uninterruptible.await(released));

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      session.close();
    }

    assertSame(sentinel, closingError().getThrown());
  }

  @Test
  void anErrorOnSessionEndThrowsIsLoggedAndLeavesTheRunsResult() {
    var sentinel = new AssertionError("end failure");
    var provider = new RecordingProvider(order);
    provider.end =
        () -> {
          throw sentinel;
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      var session = AgentSession.open(withProvider(provider).withLimits(slowGrace()).build());
      var future = session.send("go");
      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
      session.close();
    }

    assertSame(sentinel, closingError().getThrown());
  }

  @Test
  void anErrorOnSessionEndJoinsFromAFailedFutureIsLoggedAsItself() {
    var sentinel = new AssertionError("end failure");
    var provider = new RecordingProvider(order);
    provider.end = () -> CompletableFuture.failedFuture(sentinel).join();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      AgentSession.open(withProvider(provider).build()).close();
    }

    assertSame(sentinel, closingError().getThrown());
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
  void aModelStreamErrorLeavesTheRunAsAToolErrorDoesWithoutARunEnded() {
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
            AgentSession.open(
                failingAtStop(withProvider(provider), primary).withLimits(slowGrace()).build())) {
      var future = session.send("go");

      assertSame(primary, Await.failure("the result to fail", future));
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

    try (var session = AgentSession.open(failingAtStop(withProvider(provider), primary).build())) {
      var future = session.send("go");

      assertSame(primary, Await.failure("the result to fail", future));
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
              Uninterruptible.await(lateTool);
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
            .withStopGrace(SHORT_GRACE)
            .withMaxWallClock(NOT_REACHED)
            .build();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.open(options(model).withTools(List.of(tool)).withLimits(limits).build())) {
      var future = session.send("go");
      Await.latch("the second model turn to wait on its stalled stream", secondTurn);
      lateTool.countDown();

      assertSame(sentinel, Await.failure("the result to fail", future));
    }
    assertEquals(2, model.calls().size());
  }

  @Test
  void anErrorAToolThrowsAfterTheSessionClosedIsLoggedAndChangesNothing() {
    var sentinel = new AssertionError("tool failure after the result");
    var started = new CountDownLatch(1);
    var throwing = new CountDownLatch(1);
    var tool =
        binding(
            "stuck",
            (args, ctx) -> {
              started.countDown();
              Uninterruptible.await(throwing);
              throw sentinel;
            });
    var limits =
        SessionLimits.newBuilder()
            .withToolTimeoutDefault(SHORT_GRACE)
            .withStopGrace(SHORT_GRACE)
            .build();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.open(
                callingTool(new RecordingProvider(order), tool).withLimits(limits).build())) {
      var future = session.send("go");
      Await.latch("the tool to start", started);
      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
      Await.until("the session to leave the run", () -> !session.isRunning());
      session.close();

      throwing.countDown();

      var severe = "an Error reached session " + SID + " after it ended";
      Await.until(
          "the late Error to be logged",
          () -> logged.stream().anyMatch(record -> severe.equals(record.getMessage())));
      var reported =
          logged.stream().filter(record -> severe.equals(record.getMessage())).findFirst();
      assertEquals(
          List.of(Level.SEVERE, sentinel),
          List.of(reported.orElseThrow().getLevel(), reported.orElseThrow().getThrown()));
      assertInstanceOf(ResultMessage.Success.class, future.join());
    }
  }

  @Test
  void aSessionARunsErrorFailedRefusesASendNamingTheError() {
    var provider = new RecordingProvider(order);
    var sentinel = new AssertionError("hook failure");
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    try (var session =
        AgentSession.open(failingAtStop(withProvider(provider, model), sentinel).build())) {
      assertSame(sentinel, Await.failure("the result to fail", session.send("go")));
      Await.until("the session to end its provider", () -> provider.ends.get() == 1);

      var refused = assertThrows(IllegalStateException.class, () -> session.send("again"));

      assertEquals("session failed: java.lang.AssertionError", refused.getMessage());
    }
    assertEquals(1, provider.ends.get());
  }

  @Test
  void anErrorACancellationCallbackThrowsWhenTheWallClockRunsOutFailsTheResult() {
    var sentinel = new AssertionError("callback failure");
    var model = ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(() -> {})).build();
    var provider = new RecordingProvider(order);
    var limits = SessionLimits.newBuilder().withMaxWallClock(SHORT_GRACE).build();
    PreModelTurnHook throwingOnCancel =
        (history, ctx) -> {
          ctx.cancellation()
              .onCancel(
                  () -> {
                    throw sentinel;
                  });
          return HookOutcome.cont();
        };

    try (var session =
        AgentSession.open(
            withProvider(provider, model).withLimits(limits).withHook(throwingOnCancel).build())) {
      var future = session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", future));
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
          if (thread.getName().equals("helios-agent-run-" + sessionId)) {
            uncaught.complete(error);
          }
        });
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();

    try (var session =
        AgentSession.open(
            failingAtStop(options(model), sentinel).withSessionId(sessionId).build())) {
      session.send("go");

      assertSame(sentinel, Await.value("the loop thread's uncaught Error", uncaught));
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous);
    }
  }

  /** The {@code SEVERE} record of an {@link Error} that reached the session while it closed. */
  private LogRecord closingError() {
    var severe = "an Error reached session " + SID + " while it closed";
    return logged.stream()
        .filter(record -> record.getLevel() == Level.SEVERE && severe.equals(record.getMessage()))
        .findFirst()
        .orElseThrow();
  }

  /** What a run's result fails with, and the {@code RunEnded} events it emitted. */
  private static Map.Entry<Throwable, List<QueryEvent.RunEnded>> endedRun(
      SessionOptions.Builder options) {
    var events = new EventCollector();
    try (var session = AgentSession.open(options.build())) {
      events.read(session.events());
      var future = session.send("go");
      var failure = Await.failure("the result to fail", future);
      session.close();
      events.awaitDone();
      return Map.entry(failure, events.eventsOf(QueryEvent.RunEnded.class));
    }
  }

  private static Model throwingModel(AtomicInteger calls, Error failure) {
    return model(
        () -> {
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

  @Test
  void anErrorAToolThrowsLeavesItsRoundWithoutAToolResult() {
    var sentinel = new AssertionError("tool failure");
    var tool =
        binding(
            "boom",
            (args, ctx) -> {
              throw sentinel;
            });
    var events = new EventCollector();

    try (var session = AgentSession.open(callingTool(new RecordingProvider(order), tool).build())) {
      events.read(session.events());
      var future = session.send("go");

      assertSame(sentinel, Await.failure("the result to fail", future));
    }
    events.awaitDone();
    assertEquals(List.of(), events.eventsOf(QueryEvent.ToolResult.class));
  }
}
