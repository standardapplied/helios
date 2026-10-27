/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.RedactedLogs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
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
 * How a session starts: the execution provider's {@code onSessionStart} is the last step of its
 * construction, waited for within {@link SessionLimits#startTimeout()}; a start it does not accept
 * makes {@code open} throw {@link SessionUnavailableException}, releases what the session acquired
 * and never ends the provider, an {@link Error} propagates from {@code open} as itself, and a start
 * that settles after the session stopped waiting is reported, a late acceptance ended by the
 * session's bounded teardown and a late {@code Error} logged. Every start or end that hangs blocks
 * on {@link #released}, opened after each test; the start timeout and teardown grace under test are
 * {@link #SHORT_START}.
 */
final class SessionStartTest {

  private static final String SID = "sess-start";
  private static final Duration SHORT_START = Duration.ofMillis(50);
  private static final Duration NOT_REACHED = Duration.ofMinutes(10);

  private final CountDownLatch released = new CountDownLatch(1);
  private final List<String> order = new CopyOnWriteArrayList<>();
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

  @AfterEach
  void releaseHangingFixtures() {
    released.countDown();
  }

  @Test
  void anErrorAtStartUpPropagatesFromOpenAsItself() {
    var sentinel = new AssertionError("start failure");
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          throw sentinel;
        };
    var options = withProvider(provider).build();

    assertSame(sentinel, assertThrows(AssertionError.class, () -> AgentSession.open(options)));
  }

  @Test
  void anErrorOnSessionStartJoinsFromAFailedFuturePropagatesAsItself() {
    var sentinel = new AssertionError("start failure");
    var provider = new RecordingProvider(order);
    provider.start = () -> CompletableFuture.<SessionStartOutcome>failedFuture(sentinel).join();
    var options = withProvider(provider).build();

    assertSame(sentinel, assertThrows(AssertionError.class, () -> AgentSession.open(options)));
  }

  @Test
  void aCompletionExceptionWithoutACauseAtStartUpIsAProviderRefusal() {
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          throw new CompletionException("start failed", null);
        };

    assertEquals(
        refusedBy("onSessionStart threw CompletionException: start failed"),
        refusal(withProvider(provider)).getMessage());
  }

  static Stream<Arguments> startUpsNotAccepted() {
    Supplier<SessionStartOutcome> refusing = () -> SessionStartOutcome.refuse("pool full");
    Supplier<SessionStartOutcome> returningNull = () -> null;
    Supplier<SessionStartOutcome> throwing =
        () -> {
          throw new IllegalStateException("auth failed");
        };
    Supplier<SessionStartOutcome> cancelling =
        () -> {
          throw new CancellationException("start cancelled");
        };
    Supplier<SessionStartOutcome> erroring =
        () -> {
          throw new AssertionError("start failure");
        };
    return Stream.of(
        Arguments.of("refusal", refusing),
        Arguments.of("null", returningNull),
        Arguments.of("exception", throwing),
        Arguments.of("cancellation exception", cancelling),
        Arguments.of("error", erroring));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("startUpsNotAccepted")
  void aStartUpNotAcceptedReleasesWhatTheSessionAcquiredAndNeverEndsTheProvider(
      String kind, Supplier<SessionStartOutcome> start) {
    var provider = new RecordingProvider(order);
    provider.start = start;

    var lifecycle = lifecycle(provider, SessionLimits.defaults());
    var events = lifecycle.events().open();

    assertThrows(Throwable.class, () -> lifecycle.open(loop(lifecycle, provider)));

    assertFalse(Await.value("the stream to end", reading(events)), kind + " ends the events");
    assertFalse(lifecycle.runs().isRunning(), kind + " starts no run");
    assertEquals(0, provider.ends.get());
  }

  @Test
  void aStartUpThatNeverReturnsRefusesTheSessionOnceItsTimeoutHasPassed() {
    var provider = new RecordingProvider(order);
    provider.start = acceptingOnceOpened(released);

    assertEquals(
        refusedBy("onSessionStart did not return within PT0.05S"),
        refusal(withProvider(provider).withLimits(shortStart())).getMessage());
  }

  @Test
  void anInterruptedCreationRefusesTheSessionAndKeepsTheInterrupt() {
    var provider = new RecordingProvider(order);
    provider.start = acceptingOnceOpened(released);

    Thread.currentThread().interrupt();
    SessionUnavailableException refused;
    boolean interrupted;
    try {
      refused = refusal(withProvider(provider));
    } finally {
      interrupted = Thread.interrupted();
    }

    assertTrue(interrupted, "open keeps the caller's interrupt");
    assertEquals(refusedBy("interrupted while waiting for onSessionStart"), refused.getMessage());
  }

  @Test
  void aLateAcceptanceIsEndedWithOnSessionEnd() {
    var accepting = new CountDownLatch(1);
    var ended = new CountDownLatch(1);
    var provider = new RecordingProvider(order);
    provider.start = acceptingOnceOpened(accepting);
    provider.end = ended::countDown;

    refusal(withProvider(provider).withLimits(shortStart()));
    accepting.countDown();

    Await.latch("onSessionEnd", ended);
  }

  @Test
  void aLateAcceptanceIsReportedBeforeItsOnSessionEndReturns() {
    lateAcceptanceReporting(
        () -> Uninterruptible.await(released),
        shortStartSlowTeardown(),
        "onSessionStart settled after session "
            + SID
            + " stopped waiting for RecordingProvider; its acceptance is ended by the"
            + " session's teardown");
  }

  @Test
  void aLateAcceptanceWaitsForItsOnSessionEndThoughItsStartWasInterrupted() {
    var accepting = new CountDownLatch(1);
    var ending = new CountDownLatch(1);
    var endEntered = new CountDownLatch(1);
    var starting = new CompletableFuture<Thread>();
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          starting.complete(Thread.currentThread());
          Uninterruptible.await(accepting);
          return SessionStartOutcome.accept();
        };
    provider.end =
        () -> {
          endEntered.countDown();
          Uninterruptible.await(ending);
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      refusal(withProvider(provider).withLimits(shortStartSlowTeardown()));
      accepting.countDown();
      Await.latch("onSessionEnd of the late acceptance", endEntered);
      ending.countDown();
      Await.termination("the start worker", Await.value("the start", starting));
    }

    assertEquals(
        List.of(
            "onSessionStart settled after session "
                + SID
                + " stopped waiting for RecordingProvider; its acceptance is ended by the"
                + " session's teardown"),
        logged.stream()
            .filter(record -> record.getLevel() == Level.WARNING)
            .map(LogRecord::getMessage)
            .toList());
  }

  @Test
  void aLateAcceptancesTeardownRunsWhereTheInterruptThatAbandonedItsStartNeverReaches() {
    var accepting = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var teardownWaited = new CompletableFuture<Boolean>();
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          Uninterruptible.await(accepting);
          return SessionStartOutcome.accept();
        };
    var token = new CancellationToken();
    var start =
        new SessionStart(
            new SessionState(SID, token, Clock.systemUTC()),
            new SessionContext(SID, token, Clock.systemUTC(), new SecretRegistry()),
            provider,
            () -> {
              try {
                proceed.await();
                teardownWaited.complete(true);
              } catch (InterruptedException e) {
                teardownWaited.complete(false);
              }
            });

    assertTrue(start.await(SHORT_START).isPresent(), "a start past its bound is refused");
    accepting.countDown();
    proceed.countDown();

    assertTrue(Await.value("the late teardown", teardownWaited), "the teardown's wait was cut");
  }

  @Test
  void anOnSessionEndThatNeverReturnsForALateAcceptanceIsReportedAsNotDisposed() {
    lateAcceptanceReporting(
        () -> Uninterruptible.await(released),
        shortStart(),
        "onSessionEnd of RecordingProvider did not return within PT0.05S; the session's"
            + " provider resources are not known to be released, session "
            + SID);
  }

  @Test
  void aLateRefusalIsReportedAndNeverEnded() {
    var refusing = new CountDownLatch(1);
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          Uninterruptible.await(refusing);
          return SessionStartOutcome.refuse("too late");
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      refusal(withProvider(provider).withLimits(shortStart()));
      refusing.countDown();

      awaitReported(
          "onSessionStart settled after session " + SID + " stopped waiting for RecordingProvider");
    }
    assertEquals(0, provider.ends.get());
  }

  @Test
  void aStartUpThatThrowsOnceTheSessionGaveUpOnItIsReported() {
    var failure = new IllegalStateException("start interrupted");
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          throw failure;
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      refusal(withProvider(provider).withLimits(shortStart()));

      var reported =
          awaitReported(
              "onSessionStart settled after session "
                  + SID
                  + " stopped waiting for RecordingProvider");
      assertSame(failure, reported.getThrown());
    }
  }

  @Test
  void anErrorAtStartUpOnceTheSessionGaveUpOnItIsLoggedAndTheRefusalStands() {
    var failure = new AssertionError("late start failure");
    var failing = new CountDownLatch(1);
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          Uninterruptible.await(failing);
          throw failure;
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      refusal(withProvider(provider).withLimits(shortStart()));
      failing.countDown();

      var reported = awaitReported("an Error reached session " + SID + " after it ended");
      assertEquals(
          List.of(Level.SEVERE, failure), List.of(reported.getLevel(), reported.getThrown()));
    }
  }

  @Test
  void anOnSessionEndThatThrowsForALateAcceptanceIsReported() {
    var failure = new IllegalStateException("end failure");

    var reported =
        lateAcceptanceReporting(
            () -> {
              throw failure;
            },
            shortStart(),
            "onSessionEnd threw — continuing shutdown");

    RedactedLogs.assertCopyOf(failure, reported.getThrown());
  }

  @Test
  void anErrorOnSessionEndThrowsForALateAcceptanceIsLoggedAsSevere() {
    var failure = new AssertionError("end failure");

    var reported =
        lateAcceptanceReporting(
            () -> {
              throw failure;
            },
            shortStart(),
            "an Error reached session " + SID + " after it ended");

    assertEquals(
        List.of(Level.SEVERE, failure), List.of(reported.getLevel(), reported.getThrown()));
  }

  @Test
  void aNullStartUpOutcomeIsAProviderRefusal() {
    var provider = new RecordingProvider(order);
    provider.start = () -> null;

    assertEquals(
        refusedBy("onSessionStart returned null"), refusal(withProvider(provider)).getMessage());
  }

  @Test
  void aCancellationExceptionAtStartUpIsAProviderRefusal() {
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          throw new CancellationException("start cancelled");
        };

    assertEquals(
        refusedBy("onSessionStart threw CancellationException: start cancelled"),
        refusal(withProvider(provider)).getMessage());
  }

  @Test
  void aSessionWhoseWiringFailsNeverStartsTheProvider() {
    var provider = new RecordingProvider(order);
    var clashing = binding(ExecuteTool.NAME);
    var options = withProvider(provider).withTools(List.of(clashing)).build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> AgentSession.open(options));

    assertEquals("duplicate tool name in registry: Execute", thrown.getMessage());
    assertEquals(0, provider.starts.get());
  }

  @Test
  void aRefusalFailsTheOpenNamingTheProviderAndItsReasonWithNoCause() {
    var provider = new RecordingProvider(order);
    provider.start = () -> SessionStartOutcome.refuse("pool saturated");

    var refused = refusal(withProvider(provider));

    assertEquals(
        "RecordingProvider refused session " + SID + ": pool saturated", refused.getMessage());
    assertEquals(null, refused.getCause());
  }

  @Test
  void aStartUpExceptionIsARefusalNamingItAndCarryingItAsTheCause() {
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          throw new IllegalStateException("auth failed");
        };

    var refused = refusal(withProvider(provider));

    assertEquals(
        refusedBy("onSessionStart threw IllegalStateException: auth failed"), refused.getMessage());
    var cause = assertInstanceOf(IllegalStateException.class, refused.getCause());
    assertEquals("auth failed", cause.getMessage());
  }

  @Test
  void aStartUpExceptionWithoutAMessageIsReportedAsNoMessage() {
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          throw new IllegalStateException();
        };

    assertEquals(
        refusedBy("onSessionStart threw IllegalStateException: (no message)"),
        refusal(withProvider(provider)).getMessage());
  }

  @Test
  void aRefusalWithACauseCarriesItAsTheCause() {
    var root = new IOException("Cannot run program 'java': No such file or directory");
    var wrapper = new RuntimeException("Failed to start JVM sandbox subprocess", root);
    var provider = new RecordingProvider(order);
    provider.start = () -> SessionStartOutcome.refuse("failed to spawn sandbox", wrapper);

    var refused = refusal(withProvider(provider));

    assertEquals(refusedBy("failed to spawn sandbox"), refused.getMessage());
    assertSame(wrapper, refused.getCause());
    assertSame(root, refused.getCause().getCause());
  }

  private static SessionOptions.Builder withProvider(RecordingProvider provider) {
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withTextTurn("done").build())
        .withSessionId(SID)
        .withExecutionProvider(provider);
  }

  private static String refusedBy(String reason) {
    return "RecordingProvider refused session " + SID + ": " + reason;
  }

  /** What {@code open} throws for a session built from {@code options}. */
  private static SessionUnavailableException refusal(SessionOptions.Builder options) {
    var built = options.build();
    return assertThrows(SessionUnavailableException.class, () -> AgentSession.open(built));
  }

  /** A lifecycle of a session of {@code provider}, before its start. */
  private static SessionLifecycle lifecycle(RecordingProvider provider, SessionLimits limits) {
    var token = new CancellationToken();
    var context = new SessionContext(SID, token, Clock.systemUTC(), new SecretRegistry());
    return new SessionLifecycle(
        new SessionState(SID, token, Clock.systemUTC()),
        context,
        Optional.of(provider),
        limits,
        new SteeringQueue(4));
  }

  private static AgentLoop loop(SessionLifecycle lifecycle, RecordingProvider provider) {
    var options = withProvider(provider).build();
    var token = new CancellationToken();
    var context = new SessionContext(SID, token, Clock.systemUTC(), new SecretRegistry());
    var state = new SessionState(SID, token, Clock.systemUTC());
    return SessionWiring.agentLoop(
        options,
        context,
        new PendingQuestions(() -> state, lifecycle.events(), Clock.systemUTC()),
        new SteeringQueue(4),
        lifecycle.events(),
        state.ending());
  }

  /** Whether {@code events} holds an event, read on a thread of its own. */
  private static FutureTask<Boolean> reading(EventStream events) {
    var reading = new FutureTask<>(() -> events.iterator().hasNext());
    Thread.ofVirtual().start(reading);
    return reading;
  }

  private static SessionLimits shortStart() {
    return SessionLimits.newBuilder()
        .withStartTimeout(SHORT_START)
        .withStopGrace(SHORT_START)
        .build();
  }

  private static SessionLimits shortStartSlowTeardown() {
    return SessionLimits.newBuilder()
        .withStartTimeout(SHORT_START)
        .withStopGrace(NOT_REACHED)
        .build();
  }

  private static Supplier<SessionStartOutcome> acceptingOnceOpened(CountDownLatch latch) {
    return () -> {
      Uninterruptible.await(latch);
      return SessionStartOutcome.accept();
    };
  }

  private static ToolBinding binding(String name) {
    return ToolBinding.newBuilder(
            Tool.newBuilder()
                .withName(name)
                .withDescription(name)
                .withExecutor((args, ctx) -> ToolResult.success("x"))
                .build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  /**
   * Start a session whose provider accepts only after {@link #SHORT_START} passed and ends with
   * {@code end}, and wait for {@code warning}.
   */
  private LogRecord lateAcceptanceReporting(Runnable end, SessionLimits limits, String warning) {
    var accepting = new CountDownLatch(1);
    var provider = new RecordingProvider(order);
    provider.start = acceptingOnceOpened(accepting);
    provider.end = end;
    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      refusal(withProvider(provider).withLimits(limits));
      accepting.countDown();
      return awaitReported(warning);
    }
  }

  private LogRecord awaitReported(String warning) {
    Await.until(
        "the warning " + warning,
        () -> logged.stream().anyMatch(record -> warning.equals(record.getMessage())));
    return logged.stream()
        .filter(record -> warning.equals(record.getMessage()))
        .findFirst()
        .orElseThrow();
  }
}
