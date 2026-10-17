/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
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
 * releases what the session acquired and never ends the provider, an {@link Error} fails the result
 * as itself, and a start that settles after the session stopped waiting is reported, a late
 * acceptance ended by the session's bounded teardown. Every start or end that hangs blocks on
 * {@link #released}, opened after each test; the start timeout and teardown grace under test are
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
  void anErrorAtStartUpFailsTheResultAndEndsTheSession() {
    var sentinel = new AssertionError("start failure");
    var provider = new RecordingProvider(order);
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
    var provider = new RecordingProvider(order);
    provider.start =
        () -> {
          throw new AssertionError("start failure");
        };
    var session = AgentSession.create(withProvider(provider).build());
    Await.failure("the result to fail", session.result());

    var refused = assertThrows(IllegalStateException.class, () -> session.interrupt("stop"));

    assertEquals("session is terminal", refused.getMessage());
  }

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
    var provider = new RecordingProvider(order);
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
  void aStartUpThatNeverReturnsRefusesTheSessionOnceItsTimeoutHasPassed() {
    var provider = new RecordingProvider(order);
    provider.start = acceptingOnceOpened(released);

    var session =
        (AgentSessionImpl)
            AgentSession.create(withProvider(provider).withLimits(shortStart()).build());

    var refusal =
        assertInstanceOf(
            ResultMessage.ErrorProviderUnavailable.class,
            Await.value("the result", session.result()));
    assertEquals("onSessionStart did not return within PT0.05S", refusal.reason());
    Await.termination(
        "the publisher executor to end", session.lifecycleForTests().events().executor());
    Await.termination("the scheduler to end", session.lifecycleForTests().scheduler());
  }

  @Test
  void anInterruptedCreationRefusesTheSessionAndKeepsTheInterrupt() {
    var provider = new RecordingProvider(order);
    provider.start = acceptingOnceOpened(released);

    Thread.currentThread().interrupt();
    AgentSession session;
    boolean interrupted;
    try {
      session = AgentSession.create(withProvider(provider).build());
    } finally {
      interrupted = Thread.interrupted();
    }

    assertTrue(interrupted, "create keeps the caller's interrupt");
    var refusal =
        assertInstanceOf(
            ResultMessage.ErrorProviderUnavailable.class,
            Await.value("the result", session.result()));
    assertEquals("interrupted while waiting for onSessionStart", refusal.reason());
  }

  @Test
  void aLateAcceptanceIsEndedWithOnSessionEnd() {
    var accepting = new CountDownLatch(1);
    var ended = new CountDownLatch(1);
    var provider = new RecordingProvider(order);
    provider.start = acceptingOnceOpened(accepting);
    provider.end = ended::countDown;

    var session = AgentSession.create(withProvider(provider).withLimits(shortStart()).build());
    Await.value("the refusal", session.result());
    accepting.countDown();

    Await.latch("onSessionEnd", ended);
  }

  @Test
  void aLateAcceptanceIsReportedBeforeItsOnSessionEndReturns() {
    lateAcceptanceReporting(
        () -> RecordingProvider.blockIgnoringInterrupts(released),
        shortStartSlowTeardown(),
        "onSessionStart returned after session "
            + SID
            + " stopped waiting for RecordingProvider; its acceptance is ended by the"
            + " session's teardown");
  }

  @Test
  void anOnSessionEndThatNeverReturnsForALateAcceptanceIsReportedAsNotDisposed() {
    lateAcceptanceReporting(
        () -> RecordingProvider.blockIgnoringInterrupts(released),
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
          RecordingProvider.blockIgnoringInterrupts(refusing);
          return SessionStartOutcome.refuse("too late");
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      var session = AgentSession.create(withProvider(provider).withLimits(shortStart()).build());
      Await.value("the refusal", session.result());
      refusing.countDown();

      awaitReported(
          "onSessionStart returned after session "
              + SID
              + " stopped waiting for RecordingProvider");
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
      var session = AgentSession.create(withProvider(provider).withLimits(shortStart()).build());
      Await.value("the refusal", session.result());

      var reported =
          awaitReported(
              "onSessionStart threw after session "
                  + SID
                  + " stopped waiting for RecordingProvider");
      assertSame(failure, reported.getThrown());
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

    assertSame(failure, reported.getThrown());
  }

  @Test
  void anErrorOnSessionEndThrowsForALateAcceptanceIsReported() {
    var failure = new AssertionError("end failure");

    var reported =
        lateAcceptanceReporting(
            () -> {
              throw failure;
            },
            shortStart(),
            "the teardown of a late acceptance threw after session "
                + SID
                + " stopped waiting for RecordingProvider");

    assertSame(failure, reported.getThrown());
  }

  @Test
  void aNullStartUpOutcomeIsAProviderRefusal() {
    var provider = new RecordingProvider(order);
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
    var provider = new RecordingProvider(order);
    var clashing = binding(AskUserQuestionTool.NAME);
    var options = withProvider(provider).withTools(new ToolRegistry(List.of(clashing))).build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> AgentSession.create(options));

    assertEquals("duplicate tool name in registry: AskUserQuestion", thrown.getMessage());
    assertEquals(0, provider.starts.get());
  }

  private static SessionOptions.Builder withProvider(RecordingProvider provider) {
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withTextTurn("done").build())
        .withSessionId(SID)
        .withExecutionProvider(provider);
  }

  private static SessionLimits shortStart() {
    return SessionLimits.newBuilder()
        .withStartTimeout(SHORT_START)
        .withTeardownGrace(SHORT_START)
        .build();
  }

  private static SessionLimits shortStartSlowTeardown() {
    return SessionLimits.newBuilder()
        .withStartTimeout(SHORT_START)
        .withTeardownGrace(NOT_REACHED)
        .build();
  }

  private static Supplier<SessionStartOutcome> acceptingOnceOpened(CountDownLatch latch) {
    return () -> {
      RecordingProvider.blockIgnoringInterrupts(latch);
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
      var session = AgentSession.create(withProvider(provider).withLimits(limits).build());
      Await.value("the refusal", session.result());
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
