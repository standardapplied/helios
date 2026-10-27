/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.loop.SessionState;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A task run on a thread of its own settles its outcome with what it returned or threw, hands an
 * {@link Error} to the session's ending as the same instance, and is waited for only until a
 * deadline, which the waiting thread's interrupt ends early.
 */
final class BoundedTaskTest {

  private final SessionContext context = SessionContext.forTesting("sess-bounded");
  private final SessionState state =
      new SessionState(context.sessionId(), context.cancellation(), context.clock());
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void releaseTheTask() {
    release.countDown();
  }

  @Test
  void aTasksValueSettlesItsOutcome() {
    var task = BoundedTask.start("helios-test-value", () -> "done", state.ending());

    assertTrue(task.settlesBy(Await.hangGuardDeadline()));
    assertEquals("done", task.outcome().resultNow());
    assertEquals(Optional.empty(), state.ending().seal());
  }

  @Test
  void aTasksExceptionSettlesItsOutcomeAndFailsNothing() {
    var thrown = new IOException("unreadable");
    var task =
        BoundedTask.<String>start(
            "helios-test-exception",
            () -> {
              throw thrown;
            },
            state.ending());

    assertTrue(task.settlesBy(Await.hangGuardDeadline()));
    assertSame(thrown, task.outcome().exceptionNow());
    assertEquals(Optional.empty(), state.ending().seal());
  }

  @Test
  void aTasksCancellationExceptionHasSettledItsOutcome() {
    var thrown = new CancellationException("given up");
    var task =
        BoundedTask.<String>start(
            "helios-test-cancellation",
            () -> {
              throw thrown;
            },
            state.ending());

    assertTrue(task.settlesBy(Await.hangGuardDeadline()));
    assertTrue(task.outcome().isCancelled());
  }

  @Test
  void aTasksErrorReachesTheSessionsEndingAsItself() {
    var error = new AssertionError("host failure");
    var task =
        BoundedTask.<String>start(
            "helios-test-error",
            () -> {
              throw error;
            },
            state.ending());

    assertTrue(task.settlesBy(Await.hangGuardDeadline()));
    assertSame(error, task.outcome().exceptionNow());
    assertSame(error, state.ending().seal().orElseThrow());
  }

  @Test
  void anErrorAJoinedFutureCarriesReachesTheSessionsEndingAsItself() {
    var error = new AssertionError("host failure");
    var task =
        BoundedTask.<String>start(
            "helios-test-carried",
            () -> {
              throw new ExecutionException(error);
            },
            state.ending());

    assertTrue(task.settlesBy(Await.hangGuardDeadline()));
    assertSame(error, state.ending().seal().orElseThrow());
  }

  @Test
  void aPassedDeadlineEndsTheWaitAtOnce() {
    var task = blocking();

    assertFalse(task.settlesBy(Await.passedDeadline()));
    assertFalse(task.outcome().isDone());
  }

  @Test
  void anInterruptOfTheWaitingThreadEndsTheWaitAndIsKept() {
    var task = blocking();

    Thread.currentThread().interrupt();
    var settled = task.settlesBy(Await.hangGuardDeadline());

    assertTrue(Thread.interrupted(), "the wait keeps the interrupt");
    assertFalse(settled);
  }

  @Test
  void anInterruptAsksTheTaskToStop() {
    var interrupted = new CompletableFuture<Boolean>();
    var started = new CountDownLatch(1);
    var task =
        BoundedTask.start(
            "helios-test-interrupt",
            () -> {
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.complete(true);
              }
              return null;
            },
            state.ending());
    Await.latch("the task to start", started);

    task.interrupt();

    assertTrue(Await.value("the task to see the interrupt", interrupted));
  }

  @Test
  void aPlainFutureSettledWithAFailureHasSettled() {
    var failed = CompletableFuture.failedFuture(new IllegalStateException("failed"));

    assertTrue(BoundedTask.settlesBy(failed, Await.passedDeadline()));
  }

  @Test
  void anErrorIsRecordedByTheSessionsEndingBeforeItSettlesTheOutcome() {
    var task =
        BoundedTask.<String>start(
            "helios-test-recorded-first",
            () -> {
              release.await();
              throw new AssertionError("host failure");
            },
            state.ending());
    var cancelledAtSettlement =
        task.outcome().handle((value, thrown) -> state.cancellation().isCancelled());

    release.countDown();

    assertTrue(Await.value("the outcome to settle", cancelledAtSettlement));
  }

  private BoundedTask<String> blocking() {
    return BoundedTask.start(
        "helios-test-blocking",
        () -> {
          release.await();
          return "released";
        },
        state.ending());
  }
}
