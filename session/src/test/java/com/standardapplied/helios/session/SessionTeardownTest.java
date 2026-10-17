/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.ToolDispatch;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the teardown settles the result with when a cancellation callback or {@code onSessionEnd}
 * throws an {@link Error}, the one the cancellation wait collected or one thrown after its wait
 * ended, while a later step ran: an interleaving a session gives a test no way to force, forced
 * here through a scheduler whose shutdown, a later step, blocks.
 */
final class SessionTeardownTest {

  private static final Duration SHORT_GRACE = Duration.ofMillis(50);
  private static final Duration NOT_REACHED = Duration.ofMinutes(10);

  private final SessionContext context = SessionContext.forTesting("sess-teardown");
  private final CountDownLatch releasing = new CountDownLatch(1);
  private final CountDownLatch observed = new CountDownLatch(1);
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private final ScheduledThreadPoolExecutor scheduler =
      new ScheduledThreadPoolExecutor(1) {
        @Override
        public List<Runnable> shutdownNow() {
          releasing.countDown();
          Await.latch("the cancellation callback's Error observed", observed);
          return super.shutdownNow();
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
    context
        .cancellation()
        .onCancel(
            () -> {
              Await.latch("the teardown releasing the scheduler", releasing);
              throw sentinel;
            });
    context.cancellation().onCancel(observed::countDown);

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      assertSame(sentinel, newTeardown(SHORT_GRACE).run(tools(), null));
    }
  }

  @Test
  void anErrorOnSessionEndThrowsAfterItsGraceWhileALaterStepRunsIsWhatTheResultSettlesWith() {
    var sentinel = new AssertionError("late onSessionEnd failure");
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    provider.end =
        () -> {
          var ending = Thread.currentThread();
          Thread.ofVirtual()
              .start(
                  () -> {
                    Await.termination("onSessionEnd's thread to end", ending);
                    observed.countDown();
                  });
          Await.latch("the teardown releasing the scheduler", releasing);
          throw sentinel;
        };

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      assertSame(sentinel, newTeardown(provider, SHORT_GRACE).run(tools(), null));
    }
  }

  @Test
  void anErrorTheCancellationWaitCollectedRidesOnTheLoopsErrorOnce() {
    var primary = new AssertionError("loop failure");
    var sentinel = new AssertionError("callback failure");
    context
        .cancellation()
        .onCancel(
            () -> {
              throw sentinel;
            });
    observed.countDown();

    assertSame(primary, newTeardown(NOT_REACHED).run(tools(), primary));
    assertEquals(List.of(sentinel), List.of(primary.getSuppressed()));
  }

  private SessionTeardown newTeardown(Duration grace) {
    return newTeardown(new RecordingProvider(new CopyOnWriteArrayList<>()), grace);
  }

  private SessionTeardown newTeardown(RecordingProvider provider, Duration grace) {
    return new SessionTeardown(
        new SessionState(context.sessionId(), context.cancellation(), context.clock()),
        context,
        provider,
        new SessionEventPublisher(context.sessionId()),
        scheduler,
        grace);
  }

  private ToolDispatch tools() {
    return new ToolDispatch(context, ToolRegistry.empty(), ConcurrencyLimits.defaults(), scheduler);
  }
}
