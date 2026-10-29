/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.NOT_REACHED;
import static com.standardapplied.helios.session.EndingSessions.SHORT_GRACE;
import static com.standardapplied.helios.session.EndingSessions.SID;
import static com.standardapplied.helios.session.EndingSessions.assertReported;
import static com.standardapplied.helios.session.EndingSessions.binding;
import static com.standardapplied.helios.session.EndingSessions.callingTool;
import static com.standardapplied.helios.session.EndingSessions.grace;
import static com.standardapplied.helios.session.EndingSessions.model;
import static com.standardapplied.helios.session.EndingSessions.options;
import static com.standardapplied.helios.session.EndingSessions.slowGrace;
import static com.standardapplied.helios.session.EndingSessions.stream;
import static com.standardapplied.helios.session.EndingSessions.withProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.core.trace.Trace;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.loop.TurnRunner;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.RedactedLogs;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
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
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * How runs and sessions end: a run's result settles once, after its bounded end accounts for the
 * callbacks and tool work it leaves running; the close's teardown releases what the session
 * acquired in reverse order; and nothing that hangs, a subscriber included, keeps either from
 * ending. Every fixture that hangs blocks on {@link #released}, opened after each test; the grace
 * under test is {@link EndingSessions#SHORT_GRACE} and every other limit is beyond the hang guard.
 */
final class SessionFinalizationTest {

  private final CountDownLatch released = new CountDownLatch(1);
  private final CountDownLatch streamClosed = new CountDownLatch(1);
  private final List<String> order = new CopyOnWriteArrayList<>();
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

  @AfterEach
  void releaseHangingFixtures() {
    released.countDown();
  }

  @Test
  void aModelStreamThatIgnoresItsCloseCannotKeepAClosedSessionFromSettling() {
    var reader = new CompletableFuture<Thread>();
    var model =
        model(
            () ->
                stream(
                    () -> {
                      reader.complete(Thread.currentThread());
                      Uninterruptible.await(released);
                      return false;
                    },
                    () -> {}));
    var events = new EventCollector();
    var session = AgentSession.open(options(model).withLimits(grace()).build());
    events.read(session.events());
    var future = session.send("go");
    var reading = Await.value("the stream read", reader);

    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", future));
    }
    events.awaitDone();
    var emitted = events.events();
    released.countDown();
    Await.termination("the stream's reader", reading);

    assertEquals(
        List.of(
            "the model stream of session "
                + SID
                + " did not end within PT0.05S of its close; it is not known to be closed"),
        logged.stream().map(LogRecord::getMessage).toList());
    assertEquals(emitted, events.events());
  }

  @Test
  void aCancellationExceptionOnSessionEndThrowsIsLoggedRedactedAndTheResultStillSettles() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", "sk-test-0123456789abcdef");
    var cleanup = new CancellationException("end cancelled for sk-test-0123456789abcdef");
    var provider = new RecordingProvider(order);
    provider.end =
        () -> {
          throw cleanup;
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.open(withProvider(provider).withSecretRegistry(secrets).build())) {
      var future = session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
    }
    var warning = logged.getFirst();
    assertEquals(
        List.of(Level.WARNING, "onSessionEnd threw — continuing shutdown"),
        List.of(warning.getLevel(), warning.getMessage()));
    RedactedLogs.assertCopyOf(
        cleanup,
        warning.getThrown(),
        List.of(
            "java.util.concurrent.CancellationException: end cancelled for <redacted:API_KEY>"));
    assertFalse(RedactedLogs.text(warning).contains("sk-test-0123456789abcdef"));
  }

  @Test
  void aCloseReturnsOnlyOnceTheProviderEndedAndARunsEndNeverEndsIt() {
    var provider = new RecordingProvider(order);
    var session = AgentSession.open(withProvider(provider).build());

    assertInstanceOf(ResultMessage.Success.class, Await.value("the run", session.send("go")));
    assertEquals(0, provider.ends.get());
    session.close();

    assertEquals(1, provider.ends.get());
  }

  @Test
  void anOnSessionEndThatNeverReturnsIsReportedAndTheResultStillSettles() {
    var provider = new RecordingProvider(order);
    provider.end = () -> Uninterruptible.await(released);

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.open(withProvider(provider).withLimits(grace()).build())) {
      var future = session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
    }
    assertReported(
        logged,
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
                      ctx.cancellation().onCancel(() -> Uninterruptible.await(released));
                      return HookOutcome.cont();
                    })
            .withLimits(grace());

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.open(options.build())) {
      var future = session.send("go");

      var run = Await.value("the result", future).runId();
      assertReported(
          logged,
          "a callback of the cancellation of run "
              + run
              + " of session "
              + SID
              + " did not return within PT0.05S");
    }
  }

  @Test
  void anInterruptedCloseStopsWaitingForTheTeardownAndKeepsTheInterrupt() {
    var provider = new RecordingProvider(order);
    provider.end = () -> Uninterruptible.await(released);
    var session = AgentSession.open(withProvider(provider).withLimits(slowGrace()).build());

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      Thread.currentThread().interrupt();
      session.close();
      assertTrue(Thread.interrupted(), "close keeps the caller's interrupt");
    }

    assertReported(
        logged,
        "onSessionEnd of RecordingProvider did not return within PT10M; the session's"
            + " provider resources are not known to be released, session "
            + SID);
  }

  @Test
  void aCancellationCallbackThatNeverReturnsCannotKeepAClosedModelStreamOpen() {
    var streaming = streamingBehindACallbackThatNeverReturns(new RecordingProvider(order));

    streaming.session().close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", streaming.run()));
    Await.latch("the stalled stream closed", streamClosed);
  }

  @Test
  void aHostsOwnCancelOfTheSessionsTokenEndsTheModelStreamWhileACallbackNeverReturns() {
    var provider = new RecordingProvider(order);
    var streaming = streamingBehindACallbackThatNeverReturns(provider);

    Thread.ofVirtual().start(() -> provider.context.cancellation().cancel("host cancelled"));

    var cancelled =
        assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", streaming.run()));
    assertEquals("host cancelled", cancelled.reason());
    streaming.session().close();
  }

  /** A session whose run streams from a stalled model, and that run's future. */
  private record Streaming(AgentSession session, CompletableFuture<ResultMessage> run) {}

  private Streaming streamingBehindACallbackThatNeverReturns(RecordingProvider provider) {
    var entered = new CountDownLatch(1);
    var stalled = ModelStreams.stalled(entered::countDown);
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                stream(
                    stalled::hasNext,
                    () -> {
                      stalled.close();
                      streamClosed.countDown();
                    }))
            .build();
    var session = AgentSession.open(withProvider(provider, model).withLimits(grace()).build());
    var token = provider.context.cancellation();
    token.onCancel(() -> Uninterruptible.await(released));
    var armed = token.activeCallbackCountForTests();
    var run = session.send("go");
    Await.latch("the model call to start", entered);
    Await.until(
        "the run to follow the session's token",
        () -> token.activeCallbackCountForTests() == armed + 1);
    return new Streaming(session, run);
  }

  @Test
  void aCancellationCallbackThatNeverReturnsCannotKeepAClosedSessionsQuestionPending() {
    var question =
        new ToolCall(
            "c-ask",
            AskUserQuestionTool.NAME,
            Map.of(
                "question",
                "Proceed?",
                "options",
                List.of(
                    Map.of("label", "Yes", "description", "go on"),
                    Map.of("label", "No", "description", "stop"))));
    var model = ScriptedModel.newBuilder().withToolCallsTurn(question).withTextTurn("done").build();
    var provider = new RecordingProvider(order);
    var limits =
        SessionLimits.newBuilder()
            .withStopGrace(SHORT_GRACE)
            .withToolTimeoutDefault(NOT_REACHED)
            .build();
    var session =
        AgentSession.open(
            withProvider(provider, model).withLimits(limits).withAskUserQuestion().build());
    provider.context.cancellation().onCancel(() -> Uninterruptible.await(released));
    var asked = opensOn(session, QueryEvent.QuestionAsked.class);
    var future = session.send("go");
    Await.latch("the question to be asked", asked);

    session.close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", future));
  }

  @Test
  void aCancellationCallbackThatNeverReturnsCannotKeepAClosedSessionsBackOffAsleep() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(new TransientStreamException("reset", null, "test")))
            .withTextTurn("done")
            .build();
    var provider = new RecordingProvider(order);
    var limits =
        SessionLimits.newBuilder()
            .withStopGrace(SHORT_GRACE)
            .withStreamRetryPolicy(
                new StreamRetryPolicy(2, Backoff.fixed(Duration.ofHours(1)), 0.0))
            .build();
    var session = AgentSession.open(withProvider(provider, model).withLimits(limits).build());
    provider.context.cancellation().onCancel(() -> Uninterruptible.await(released));
    var retried = opensOn(session, QueryEvent.TurnRetried.class);
    var future = session.send("go");
    Await.latch("the turn to be retried", retried);

    session.close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", future));
    assertEquals(1, model.calls().size());
  }

  @Test
  void aCancellationCallbackThatNeverReturnsOnCloseIsReportedAndTheResultStillSettles() {
    var provider = new RecordingProvider(order);
    var session = AgentSession.open(withProvider(provider).withLimits(grace()).build());
    provider.context.cancellation().onCancel(() -> Uninterruptible.await(released));

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      session.close();
    }

    assertReported(
        logged,
        "a callback of the cancellation of session " + SID + " did not return within PT0.05S");
  }

  @Test
  void closingARunningLoopEndsItOnceWithOneDisposal() {
    var entered = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    var provider = new RecordingProvider(order);

    try (var session = AgentSession.open(withProvider(provider, model).build())) {
      var future = session.send("go");
      Await.latch("the model call to start", entered);
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", future));
      session.close();
    }
    assertEquals(1, provider.ends.get());
  }

  @Test
  void closingWhileARunEndsKeepsItsResultAndOneDisposal() {
    var provider = new RecordingProvider(order);
    var ending = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var options =
        withProvider(provider)
            .withHook(
                (PreStopHook)
                    (response, ctx) -> {
                      ctx.cancellation()
                          .onCancel(
                              () -> {
                                ending.countDown();
                                Uninterruptible.await(proceed);
                              });
                      return HookOutcome.cont();
                    })
            .withLimits(slowGrace());

    try (var session = AgentSession.open(options.build())) {
      var future = session.send("go");
      Await.latch("the run's end to cancel its token", ending);
      var closing = Thread.ofVirtual().start(session::close);
      proceed.countDown();

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
      Await.termination("the close", closing);
    }
    assertEquals(1, provider.ends.get());
  }

  @Test
  void aSubscriberThatNeverReturnsCannotKeepTheResultFromSettling() {
    var entered = new CountDownLatch(1);
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();

    try (var session = AgentSession.open(options(model).withLimits(grace()).build())) {
      new EventCollector(
              event -> {
                entered.countDown();
                Uninterruptible.await(released);
              })
          .read(session.events());
      var future = session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
    }
    Await.latch("the subscriber to have been stuck in an event", entered);
  }

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
      var events = new EventCollector(event -> threads.add(Thread.currentThread()));
      var options = options(model).withTools(List.of(tool)).withHook(recordingLoop).build();

      try (var session = (AgentSessionImpl) AgentSession.open(options)) {
        events.read(session.events());
        var future = session.send("go");

        assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
        events.awaitRunEnded();
        session.close();
        events.awaitDone();
        assertEquals(Optional.empty(), events.error());
        assertEquals(0, session.lifecycle.events().openStreams());
      }
    }
    threads.forEach(thread -> Await.termination("thread " + thread.getName() + " to end", thread));
  }

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

    try (var session =
        AgentSession.open(
            callingTool(new RecordingProvider(order), tool).withLimits(slowGrace()).build())) {
      var future = session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", future));
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
              Uninterruptible.await(released);
              return ToolResult.success("too late");
            });

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.open(
                callingTool(new RecordingProvider(order), tool).withLimits(grace()).build())) {
      var future = session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      var run = Await.value("the result", future);
      assertInstanceOf(ResultMessage.Cancelled.class, run);
      assertReported(
          logged,
          "tool 'stuck' (call c-stuck) still runs PT0.05S after run "
              + run.runId()
              + " of session "
              + SID
              + " ended; its effects may land after the result settled");
    }
  }

  @Test
  void aToolThatEndsOnlyOnceItsProviderEndedIsReportedBeforeOnSessionEndRuns() {
    var started = new CountDownLatch(1);
    var providerEnded = new CountDownLatch(1);
    var provider = new RecordingProvider(order);
    provider.end = providerEnded::countDown;
    var tool =
        binding(
            "needs-provider",
            (args, ctx) -> {
              started.countDown();
              Uninterruptible.await(providerEnded);
              return ToolResult.success("late");
            });

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.open(callingTool(provider, tool).withLimits(grace()).build())) {
      var future = session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", future));
      var warning =
          "tool 'needs-provider' (call c-needs-provider) still runs PT0.05S after session "
              + SID
              + " ended; its effects may land after the result settled";
      Await.until(
          "the teardown to report the tool",
          () -> logged.stream().anyMatch(record -> warning.equals(record.getMessage())));
    }
  }

  @Test
  void aRunThatEndsOnItsOwnCancelsAnUnknownCall() {
    var toolEnded = new CountDownLatch(1);
    var tool =
        binding(
            "lingers",
            (args, ctx) -> {
              var sessionEnded = new CountDownLatch(1);
              ctx.sessionContext().cancellation().onCancel(sessionEnded::countDown);
              Uninterruptible.await(sessionEnded);
              toolEnded.countDown();
              return ToolResult.success("late");
            });
    var limits =
        SessionLimits.newBuilder()
            .withToolTimeoutDefault(SHORT_GRACE)
            .withStopGrace(SHORT_GRACE)
            .build();
    var events = new EventCollector();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.open(
                callingTool(new RecordingProvider(order), tool).withLimits(limits).build())) {
      events.read(session.events());
      var future = session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
    }
    var answered = events.eventsOf(QueryEvent.ToolResult.class).getFirst().result();
    assertInstanceOf(ToolOutcome.Unknown.class, answered.outcome());
    Await.latch("the session's end to reach the unknown call", toolEnded);
  }

  @Test
  void aRuntimeExceptionTheLoopCannotTurnIntoATerminalFailsTheResultAsItself() {
    var failure = new Unprintable();
    var model =
        model(
            () -> {
              throw failure;
            });

    var traces = new CopyOnWriteArrayList<Trace>();

    try (var session = AgentSession.open(options(model).withTraceListener(traces::add).build())) {
      var future = session.send("go");

      assertSame(failure, Await.failure("the result to fail", future));
    }
    var trace = traces.getFirst();
    assertEquals(Unprintable.class.getName(), trace.error());
    assertEquals(Unprintable.class.getName(), trace.attributes().get("error.type"));
  }

  @Test
  void anInterruptAHookLeavesOnTheRunThreadDoesNotCutTheRunsWaitForItsCallbacksShort() {
    var ending = new CountDownLatch(1);
    var runThread = new CompletableFuture<Thread>();
    var options =
        options(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withHook(
                (PreStopHook)
                    (response, ctx) -> {
                      runThread.complete(Thread.currentThread());
                      ctx.cancellation()
                          .onCancel(
                              () -> {
                                ending.countDown();
                                Uninterruptible.await(released);
                              });
                      Thread.currentThread().interrupt();
                      return HookOutcome.cont();
                    })
            .withLimits(slowGrace());

    try (var session = AgentSession.open(options.build())) {
      var future = session.send("go");
      Await.latch("the run's end to cancel its token", ending);
      var run = Await.value("the run thread", runThread);
      Await.until(
          "the result to settle or the run to wait for its callbacks",
          () -> future.isDone() || run.getState() == Thread.State.TIMED_WAITING);

      assertFalse(future.isDone(), "the result settled while a callback of the run still runs");
      released.countDown();
      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
    }
  }

  @Test
  void anOnSessionEndThatThrowsAnUndeclaredCheckedExceptionLetsTheResultSettleAtOnce() {
    var provider = new RecordingProvider(order);
    provider.end = () -> throwUndeclared(new IOException("undeclared"));

    try (var session = AgentSession.open(withProvider(provider).withLimits(slowGrace()).build())) {
      var future = session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", future));
    }
  }

  /** A latch that opens on the first event of {@code type} that {@code session} publishes. */
  private static CountDownLatch opensOn(AgentSession session, Class<? extends QueryEvent> type) {
    var seen = new CountDownLatch(1);
    new EventCollector(
            event -> {
              if (type.isInstance(event)) {
                seen.countDown();
              }
            })
        .read(session.events());
    return seen;
  }

  /** An exception whose message cannot be read, so the loop cannot serialise it. */
  private static final class Unprintable extends RuntimeException {
    @Override
    public String getMessage() {
      throw this;
    }
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void throwUndeclared(Throwable t) throws E {
    throw (E) t;
  }
}
