/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The interleavings {@link ToolDispatchTest} cannot force through the dispatcher, whose caller
 * always waits: the deadline passes before the worker is admitted, and no caller has yet cancelled
 * the call. A scheduler on which every deadline is overdue fires it as it is armed, before the
 * worker starts; a scheduler whose only thread is busy never fires it.
 */
final class DispatchedCallTest {

  private static final Duration NEVER = Duration.ofMinutes(10);

  private final Overdue scheduler = new Overdue();
  private final AtomicInteger runs = new AtomicInteger();
  private final AtomicInteger live = new AtomicInteger();
  private final ScheduledThreadPoolExecutor busy = new ScheduledThreadPoolExecutor(1);
  private final CountDownLatch busyRelease = new CountDownLatch(1);

  @AfterEach
  void stopTheSchedulers() {
    busyRelease.countDown();
    scheduler.shutdownNow();
    busy.shutdownNow();
  }

  private DispatchedCall dispatched(Duration timeout) {
    return new DispatchedCall(
        Tool.newBuilder()
            .withName("t")
            .withDescription("t")
            .withExecutor((args, ctx) -> ToolResult.success("run " + runs.incrementAndGet()))
            .build(),
        new ToolCall("c", "t", Map.of()),
        SessionContext.forTesting("dispatched-call"),
        new CancellationToken(),
        timeout,
        new CategoryPermits(1),
        live);
  }

  /** Runs each deadline as it is armed, as if its delay had already passed. */
  private static final class Overdue extends ScheduledThreadPoolExecutor {

    Overdue() {
      super(1);
      setRemoveOnCancelPolicy(true);
    }

    @Override
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Runnable runnable, RunnableScheduledFuture<V> task) {
      runnable.run();
      return task;
    }
  }

  @Test
  void aDeadlineThatFiredBeforeTheWorkerWasAdmittedKeepsTheToolFromStarting() {
    dispatched(NEVER).start(scheduler);

    Await.until("the worker to end", () -> live.get() == 0);
    assertEquals(0, runs.get(), "the tool never ran");
  }

  @Test
  void aWorkerReachingAdmissionPastItsDeadlineKeepsTheToolFromStarting() {
    busy.execute(() -> ToolDispatchTest.awaitThroughInterrupts(busyRelease));

    dispatched(Duration.ofNanos(1)).start(busy);

    Await.until("the worker to end", () -> live.get() == 0);
    assertEquals(0, runs.get(), "the tool never ran");
  }
}
