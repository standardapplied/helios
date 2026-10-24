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
import static com.standardapplied.helios.session.EndingSessions.subscription;
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
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.loop.TurnRunner;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.RedactedLogs;
import com.standardapplied.helios.session.tools.ToolRegistry;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * How a session ends: the teardown releases what the session acquired in reverse order, the result
 * settles once, after a bounded teardown that accounts for tool work still running, and nothing
 * that hangs, a subscriber included, keeps it from settling. Every fixture that hangs blocks on
 * {@link #released}, opened after each test; the grace under test is {@link
 * EndingSessions#SHORT_GRACE} and every other limit is beyond the hang guard.
 */
final class SessionFinalizationTest {

  private final CountDownLatch released = new CountDownLatch(1);
  private final List<String> order = new CopyOnWriteArrayList<>();
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

  @AfterEach
  void releaseHangingFixtures() {
    released.countDown();
  }

  @Test
  void aStreamCancelThatNeverReturnsCannotKeepAClosedSessionFromSettling() {
    var requested = new CountDownLatch(1);
    var model =
        model(
            token ->
                subscriber ->
                    subscriber.onSubscribe(
                        subscription(requested::countDown, () -> Uninterruptible.await(released))));
    var session = AgentSession.create(options(model).withLimits(grace()).build());
    session.send("go");
    Await.latch("the stream requested", requested);

    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    }
    assertEquals(
        List.of(
            "cancelling the model stream of session "
                + SID
                + " did not return within PT0.05S; the stream is not known to be closed"),
        logged.stream().map(LogRecord::getMessage).toList());
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
            AgentSession.create(withProvider(provider).withSecretRegistry(secrets).build())) {
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
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
  void aSessionClosedBeforeItStartsSettlesItsResultOnlyAfterTheProviderEnded() {
    var provider = new RecordingProvider(order);
    var session = AgentSession.create(withProvider(provider).build());
    var settledDuringEnd = new AtomicBoolean(true);
    provider.end = () -> settledDuringEnd.set(session.result().isDone());

    session.close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    assertEquals(1, provider.ends.get());
    assertFalse(settledDuringEnd.get(), "the result settled while the provider still held it");
  }

  @Test
  void anOnSessionEndThatNeverReturnsIsReportedAndTheResultStillSettles() {
    var provider = new RecordingProvider(order);
    provider.end = () -> Uninterruptible.await(released);

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.create(withProvider(provider).withLimits(grace()).build())) {
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
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
        var session = AgentSession.create(options.build())) {
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
    }
    assertReported(
        logged,
        "a callback of the cancellation of session " + SID + " did not return within PT0.05S");
  }

  @Test
  void anInterruptedCloseStopsWaitingForTheTeardownAndKeepsTheInterrupt() {
    var provider = new RecordingProvider(order);
    provider.end = () -> Uninterruptible.await(released);
    var session = AgentSession.create(withProvider(provider).withLimits(slowGrace()).build());

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      Thread.currentThread().interrupt();
      session.close();
      assertTrue(Thread.interrupted(), "close keeps the caller's interrupt");
    }

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    assertReported(
        logged,
        "onSessionEnd of RecordingProvider did not return within PT10M; the session's"
            + " provider resources are not known to be released, session "
            + SID);
  }

  @Test
  void aCancellationCallbackThatNeverReturnsCannotKeepAClosedModelStreamOpen() {
    var entered = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    var provider = new RecordingProvider(order);
    var session = AgentSession.create(withProvider(provider, model).withLimits(grace()).build());
    provider.context.cancellation().onCancel(() -> Uninterruptible.await(released));
    session.send("go");
    Await.latch("the model call to start", entered);

    session.close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
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
    var session = AgentSession.create(withProvider(provider, model).withLimits(limits).build());
    provider.context.cancellation().onCancel(() -> Uninterruptible.await(released));
    var asked = opensOn(session, QueryEvent.QuestionAsked.class);
    session.send("go");
    Await.latch("the question to be asked", asked);

    session.close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
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
    var session = AgentSession.create(withProvider(provider, model).withLimits(limits).build());
    provider.context.cancellation().onCancel(() -> Uninterruptible.await(released));
    var retried = opensOn(session, QueryEvent.TurnRetried.class);
    session.send("go");
    Await.latch("the turn to be retried", retried);

    session.close();

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    assertEquals(1, model.calls().size());
  }

  @Test
  void aCancellationCallbackThatNeverReturnsOnCloseIsReportedAndTheResultStillSettles() {
    var provider = new RecordingProvider(order);
    var session = AgentSession.create(withProvider(provider).withLimits(grace()).build());
    provider.context.cancellation().onCancel(() -> Uninterruptible.await(released));

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      session.close();
    }

    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
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
          Uninterruptible.await(proceed);
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
          Uninterruptible.await(proceed);
        };
    var limits =
        SessionLimits.newBuilder()
            .withMaxWallClock(SHORT_GRACE)
            .withStreamIdleTimeout(NOT_REACHED)
            .withStopGrace(NOT_REACHED)
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
                    Uninterruptible.await(released);
                  }));
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
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
        AgentSession.create(
            callingTool(new RecordingProvider(order), tool).withLimits(slowGrace()).build())) {
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
              Uninterruptible.await(released);
              return ToolResult.success("too late");
            });

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.create(
                callingTool(new RecordingProvider(order), tool).withLimits(grace()).build())) {
      session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    }
    assertReported(
        logged,
        "tool 'stuck' (call c-stuck) still runs PT0.05S after session "
            + SID
            + " ended; its effects may land after the result settled");
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
        var session =
            AgentSession.create(callingTool(provider, tool).withLimits(grace()).build())) {
      session.send("go");
      Await.latch("the tool to start", started);
      session.close();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the result", session.result()));
    }
    assertReported(
        logged,
        "tool 'needs-provider' (call c-needs-provider) still runs PT0.05S after session "
            + SID
            + " ended; its effects may land after the result settled");
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
    var events = new CollectingSubscriber();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session =
            AgentSession.create(
                callingTool(new RecordingProvider(order), tool).withLimits(limits).build())) {
      session.events().subscribe(events);
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
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
            token -> {
              throw failure;
            });

    try (var session = AgentSession.create(options(model).build())) {
      session.send("go");

      assertSame(failure, Await.failure("the result to fail", session.result()));
    }
  }

  @Test
  void anInterruptAHookLeavesOnTheLoopThreadDoesNotCutTheWaitForOnSessionEndShort() {
    var provider = new RecordingProvider(order);
    var ending = new CountDownLatch(1);
    provider.end =
        () -> {
          ending.countDown();
          Uninterruptible.await(released);
        };
    var loopThread = new CompletableFuture<Thread>();
    var options =
        withProvider(provider)
            .withHook(
                (PreStopHook)
                    (response, ctx) -> {
                      loopThread.complete(Thread.currentThread());
                      Thread.currentThread().interrupt();
                      return HookOutcome.cont();
                    })
            .withLimits(slowGrace());

    try (var session = AgentSession.create(options.build())) {
      session.send("go");
      Await.latch("onSessionEnd to start", ending);
      var loop = Await.value("the loop thread", loopThread);
      Await.until(
          "the result to settle or the loop to wait for onSessionEnd",
          () -> session.result().isDone() || loop.getState() == Thread.State.TIMED_WAITING);

      assertFalse(session.result().isDone(), "the result settled while onSessionEnd runs");
    }
  }

  @Test
  void anOnSessionEndThatThrowsAnUndeclaredCheckedExceptionLetsTheResultSettleAtOnce() {
    var provider = new RecordingProvider(order);
    provider.end = () -> throwUndeclared(new IOException("undeclared"));

    try (var session =
        AgentSession.create(withProvider(provider).withLimits(slowGrace()).build())) {
      session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
    }
  }

  /** A latch that opens on the first event of {@code type} that {@code session} publishes. */
  private static CountDownLatch opensOn(AgentSession session, Class<? extends QueryEvent> type) {
    var seen = new CountDownLatch(1);
    session
        .events()
        .subscribe(
            new CollectingSubscriber(
                event -> {
                  if (type.isInstance(event)) {
                    seen.countDown();
                  }
                }));
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
