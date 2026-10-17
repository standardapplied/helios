/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.execution.ExecutionCapabilities;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.hooks.HookOutcome;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How a session ends: a host {@link Error} keeps its identity on every path and ends the session,
 * start-up and teardown release what the session acquired in reverse order, and the result settles
 * once, after a bounded teardown that accounts for tool work still running. Every fixture that
 * hangs blocks on {@link #released}, opened after each test; the grace under test is {@link
 * #SHORT_GRACE} and every other limit is beyond the hang guard.
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
  void anErrorAtStartUpFailsTheResultAndEndsTheSession() {
    var sentinel = new AssertionError("start failure");
    var provider = new RecordingProvider();
    provider.start =
        () -> {
          throw sentinel;
        };

    var session = (AgentSessionImpl) AgentSession.create(withProvider(provider).build());

    assertSame(sentinel, Await.failure("the result to fail", session.result()));
    var refused = assertThrows(IllegalStateException.class, () -> session.send("go"));
    assertEquals("session is terminal", refused.getMessage());
    assertTrue(session.isStarted(), "a session that ended at start-up can never start");
  }

  @Test
  void aSessionWhoseResultFailedRefusesAnInterrupt() {
    var provider = new RecordingProvider();
    provider.start =
        () -> {
          throw new AssertionError("start failure");
        };
    var session = AgentSession.create(withProvider(provider).build());
    Await.failure("the result to fail", session.result());

    var refused = assertThrows(IllegalStateException.class, () -> session.interrupt("stop"));

    assertEquals("session is terminal", refused.getMessage());
  }

  // ── start-up unwinds what it acquired ───────────────────────────────────────

  static Stream<Arguments> startUpsNotAccepted() {
    Supplier<SessionStartOutcome> refusing = () -> SessionStartOutcome.refuse("pool full");
    Supplier<SessionStartOutcome> returningNull = () -> null;
    Supplier<SessionStartOutcome> throwing =
        () -> {
          throw new IllegalStateException("auth failed");
        };
    Supplier<SessionStartOutcome> erroring =
        () -> {
          throw new AssertionError("start failure");
        };
    return Stream.of(
        Arguments.of("refusal", refusing),
        Arguments.of("null", returningNull),
        Arguments.of("exception", throwing),
        Arguments.of("error", erroring));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("startUpsNotAccepted")
  void aStartUpNotAcceptedReleasesWhatTheSessionAcquiredAndNeverEndsTheProvider(
      String kind, Supplier<SessionStartOutcome> start) {
    var provider = new RecordingProvider();
    provider.start = start;

    var session = (AgentSessionImpl) AgentSession.create(withProvider(provider).build());

    Await.termination(
        "the publisher executor to end", session.lifecycleForTests().events().executor());
    Await.termination("the scheduler to end", session.lifecycleForTests().scheduler());
    assertTrue(session.result().isDone(), kind + " settles the result");
    session.close();
    assertEquals(0, provider.ends.get());
  }

  @Test
  void aNullStartUpOutcomeIsAProviderRefusal() {
    var provider = new RecordingProvider();
    provider.start = () -> null;

    try (var session = AgentSession.create(withProvider(provider).build())) {
      var refusal =
          assertInstanceOf(
              ResultMessage.ErrorProviderUnavailable.class,
              Await.value("the result", session.result()));
      assertEquals("onSessionStart returned null", refusal.reason());
    }
  }

  @Test
  void aSessionWhoseWiringFailsNeverStartsTheProvider() {
    var provider = new RecordingProvider();
    var clashing = binding(AskUserQuestionTool.NAME, (args, ctx) -> ToolResult.success("x"));
    var options = withProvider(provider).withTools(new ToolRegistry(List.of(clashing))).build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> AgentSession.create(options));

    assertEquals("duplicate tool name in registry: AskUserQuestion", thrown.getMessage());
    assertEquals(0, provider.starts.get());
  }

  // ── a failing or hanging teardown still settles the result ──────────────────

  @Test
  void aTeardownExceptionAfterALoopErrorRidesOnItAsSuppressed() {
    var primary = new AssertionError("hook failure");
    var cleanup = new IllegalStateException("end failure");
    var provider = new RecordingProvider();
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
    var provider = new RecordingProvider();
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
    var provider = new RecordingProvider();
    provider.end = () -> blockIgnoringInterrupts(released);

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
        withProvider(new RecordingProvider())
            .withHook(
                (PreStopHook)
                    (response, ctx) -> {
                      ctx.cancellation().onCancel(() -> blockIgnoringInterrupts(released));
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
    var provider = new RecordingProvider();
    provider.end = () -> blockIgnoringInterrupts(released);
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

  // ── one terminal result and one disposal ────────────────────────────────────

  @Test
  void closingARunningLoopEndsItOnceWithOneDisposal() {
    var entered = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    var provider = new RecordingProvider();

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
    var provider = new RecordingProvider();
    var inTeardown = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    provider.end =
        () -> {
          inTeardown.countDown();
          blockIgnoringInterrupts(proceed);
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
    var provider = new RecordingProvider();
    var inTeardown = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    provider.end =
        () -> {
          inTeardown.countDown();
          blockIgnoringInterrupts(proceed);
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
                    blockIgnoringInterrupts(released);
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
              blockIgnoringInterrupts(released);
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
              blockIgnoringInterrupts(sessionEnded);
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
    var provider = new RecordingProvider();
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

  private static Model throwingModel(AtomicInteger calls, Error failure) {
    return new Model() {
      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        calls.incrementAndGet();
        throw failure;
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

  /** Blocks until {@code latch} opens, as an extension that ignores interruption does. */
  private static void blockIgnoringInterrupts(CountDownLatch latch) {
    var interrupted = false;
    while (latch.getCount() > 0) {
      try {
        latch.await();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Records the session start and end it sees, and does what the test sets for each. */
  private final class RecordingProvider implements ExecutionProvider {

    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger ends = new AtomicInteger();
    Supplier<SessionStartOutcome> start = SessionStartOutcome::accept;
    Runnable end = () -> order.add("onSessionEnd");

    @Override
    public ExecutionCapabilities capabilities() {
      return ExecutionCapabilities.newBuilder().build();
    }

    @Override
    public SessionStartOutcome onSessionStart(SessionContext ctx) {
      starts.incrementAndGet();
      return start.get();
    }

    @Override
    public void onSessionEnd(SessionContext ctx) {
      ends.incrementAndGet();
      end.run();
    }

    @Override
    public CompletionStage<ExecutionResult> execute(
        SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
      throw new UnsupportedOperationException("not used");
    }
  }
}
