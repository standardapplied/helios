/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.assertReported;
import static com.standardapplied.helios.session.EndingSessions.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.ToolDispatch;
import com.standardapplied.helios.session.loop.ToolRegistry;
import com.standardapplied.helios.session.test.LogCapture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The teardown's one deadline, and what it settles the result with when a cancellation callback or
 * {@code onSessionEnd} throws an {@link Error} after its wait ended, while a later step ran: an
 * interleaving a session gives a test no way to force, forced here through a log that holds the
 * teardown at the overrun's {@code WARNING}, the step after the wait, until the {@code Error} was
 * handed on. Every {@code Error} recorded before the settlement seals the session's ending counts.
 */
final class SessionTeardownTest {

  private static final String SID = "sess-teardown";
  private static final Duration SHORT_GRACE = Duration.ofMillis(50);
  private static final Duration NOT_REACHED = Duration.ofMinutes(10);
  private static final String CALLBACK_OVERRUN =
      "a callback of the cancellation of session " + SID + " did not return within PT0.05S";
  private static final String SESSION_END_OVERRUN =
      "onSessionEnd of RecordingProvider did not return within PT0.05S; the session's provider"
          + " resources are not known to be released, session "
          + SID;

  private final SessionContext context = SessionContext.forTesting(SID);
  private final SessionState state =
      new SessionState(context.sessionId(), context.cancellation(), context.clock());
  private final SessionEventPublisher events = new SessionEventPublisher(SID);
  private final CountDownLatch releasing = new CountDownLatch(1);
  private final CountDownLatch observed = new CountDownLatch(1);
  private volatile String holdingAt = "";
  private final List<LogRecord> logged =
      new CopyOnWriteArrayList<>() {
        @Override
        public boolean add(LogRecord logRecord) {
          if (logRecord.getMessage().equals(holdingAt)) {
            releasing.countDown();
            Await.latch("the late Error observed", observed);
          }
          return super.add(logRecord);
        }
      };

  @AfterEach
  void releaseHangingFixtures() {
    releasing.countDown();
    observed.countDown();
  }

  @Test
  void anErrorACancellationCallbackThrowsWhileALaterStepRunsIsWhatTheResultSettlesWith() {
    var sentinel = new AssertionError("callback failure");
    holdingAt = CALLBACK_OVERRUN;
    context
        .cancellation()
        .onCancel(
            () -> {
              observeWhenEnded(Thread.currentThread());
              Await.latch("the teardown releasing the callback", releasing);
              throw sentinel;
            });

    var teardown = newTeardown(SHORT_GRACE);
    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      teardown.end(tools(), BoundedTask.deadlineAfter(SHORT_GRACE));
      assertSame(sentinel, teardown.seal().orElseThrow());
    }
  }

  @Test
  void anErrorOnSessionEndThrowsAfterItsGraceWhileALaterStepRunsIsWhatTheResultSettlesWith() {
    var sentinel = new AssertionError("late onSessionEnd failure");
    holdingAt = SESSION_END_OVERRUN;
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    provider.end =
        () -> {
          observeWhenEnded(Thread.currentThread());
          Await.latch("the teardown releasing onSessionEnd", releasing);
          throw sentinel;
        };

    var teardown = newTeardown(provider, SHORT_GRACE);
    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      teardown.end(tools(), BoundedTask.deadlineAfter(SHORT_GRACE));
      assertSame(sentinel, teardown.seal().orElseThrow());
    }
  }

  /** Open {@link #observed} once {@code thread} has handed its {@link Error} on and ended. */
  private void observeWhenEnded(Thread thread) {
    Thread.ofVirtual()
        .start(
            () -> {
              Await.termination("the throwing thread to end", thread);
              observed.countDown();
            });
  }

  @Test
  void anErrorACancellationCallbackThrowsAfterTheLoopsErrorRidesOnItOnce() {
    var primary = new AssertionError("loop failure");
    var sentinel = new AssertionError("callback failure");
    context
        .cancellation()
        .onCancel(
            () -> {
              throw sentinel;
            });
    observed.countDown();
    var teardown = newTeardown(NOT_REACHED);

    state.ending().fail(primary);

    teardown.end(tools(), BoundedTask.deadlineAfter(NOT_REACHED));
    assertSame(primary, teardown.seal().orElseThrow());
    assertEquals(List.of(sentinel), List.of(primary.getSuppressed()));
  }

  @Test
  void aPassedDeadlineRunsEveryStepAndWaitsForNone() {
    context.cancellation().onCancel(() -> Uninterruptible.await(releasing));
    var toolStarted = new CountDownLatch(1);
    var tools = toolsWith(toolStarted);
    var dispatching = dispatchStuck(tools);
    Await.latch("the tool to start", toolStarted);
    var sessionEndCalled = new CountDownLatch(1);
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    provider.end =
        () -> {
          sessionEndCalled.countDown();
          Uninterruptible.await(releasing);
        };
    var unread = events.open();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      newTeardown(provider, SHORT_GRACE).end(tools, Await.passedDeadline());
    }

    Await.latch("onSessionEnd to be called", sessionEndCalled);
    assertEquals(1, provider.ends.get());
    assertEquals(0, events.openStreams());
    assertFalse(unread.iterator().hasNext(), "the unread stream ended");
    assertReported(logged, CALLBACK_OVERRUN);
    assertReported(
        logged,
        "tool 'stuck' (call c-stuck) still runs PT0.05S after session "
            + SID
            + " ended; its effects may land after the result settled");
    assertReported(logged, SESSION_END_OVERRUN);
    releasing.countDown();
    assertInstanceOf(
        CancellationException.class, Await.failure("the cancelled dispatch", dispatching));
  }

  @Test
  void aPassedDeadlineIsNotExtendedByTheGrace() {
    context.cancellation().onCancel(() -> Uninterruptible.await(releasing));
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    provider.end = () -> Uninterruptible.await(releasing);
    var teardown = newTeardown(provider, NOT_REACHED);
    var ending =
        new FutureTask<Void>(
            () -> {
              teardown.end(tools(), Await.passedDeadline());
              return null;
            });

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      Thread.ofVirtual().start(ending);
      Await.value("the teardown to end by the passed deadline", ending);
    }
  }

  @Test
  void theEventStreamsEndOnlyOnceOnSessionEndReturned() {
    var openAtSessionEnd = new CompletableFuture<Integer>();
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    provider.end = () -> openAtSessionEnd.complete(events.openStreams());
    events.open();

    newTeardown(provider, NOT_REACHED).end(tools());

    assertEquals(1, Await.value("onSessionEnd to run", openAtSessionEnd));
    assertEquals(0, events.openStreams());
  }

  private ToolDispatch toolsWith(CountDownLatch started) {
    var stuck =
        binding(
            "stuck",
            (args, ctx) -> {
              started.countDown();
              Uninterruptible.await(releasing);
              return ToolResult.success("too late");
            });
    return new ToolDispatch(context, new ToolRegistry(List.of(stuck)), state.ending());
  }

  private FutureTask<ToolResult> dispatchStuck(ToolDispatch tools) {
    var dispatching =
        new FutureTask<>(
            () ->
                tools.dispatch(
                    new ToolCall("c-stuck", "stuck", Map.of()),
                    context.cancellation(),
                    NOT_REACHED,
                    NOT_REACHED,
                    key -> false));
    Thread.ofVirtual().start(dispatching);
    return dispatching;
  }

  private SessionTeardown newTeardown(Duration grace) {
    return newTeardown(new RecordingProvider(new CopyOnWriteArrayList<>()), grace);
  }

  private SessionTeardown newTeardown(RecordingProvider provider, Duration grace) {
    return new SessionTeardown(state, context, Optional.of(provider), events, grace);
  }

  private ToolDispatch tools() {
    return new ToolDispatch(context, ToolRegistry.empty(), state.ending());
  }
}
