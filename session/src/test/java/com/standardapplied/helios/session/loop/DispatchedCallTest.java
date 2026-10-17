/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The interleavings {@link ToolDispatchTest} cannot force through the dispatcher, whose caller
 * always waits: the deadline passes before the worker is admitted, and no caller has yet cancelled
 * the call, and the session's token is cancelled, its callbacks held up by an earlier one, before
 * the caller reads the call's result or its timeout. A scheduler on which every deadline is overdue
 * fires it as it is armed, before the worker starts; a scheduler whose only thread is busy never
 * fires it. A test that holds a category's permits or its scope's lock itself decides when a queued
 * worker is admitted.
 */
final class DispatchedCallTest {

  private static final Duration NEVER = Duration.ofMinutes(10);

  private final Overdue scheduler = new Overdue();
  private final AtomicInteger runs = new AtomicInteger();
  private final Set<DispatchedCall.Live> live = ConcurrentHashMap.newKeySet();
  private final ScheduledThreadPoolExecutor busy = new ScheduledThreadPoolExecutor(1);
  private final CountDownLatch busyRelease = new CountDownLatch(1);
  private final CountDownLatch callbackEntered = new CountDownLatch(1);
  private final CountDownLatch callbackRelease = new CountDownLatch(1);
  private final CountDownLatch toolRelease = new CountDownLatch(1);

  @AfterEach
  void stopTheSchedulers() {
    busyRelease.countDown();
    callbackRelease.countDown();
    toolRelease.countDown();
    scheduler.shutdownNow();
    busy.shutdownNow();
  }

  private DispatchedCall dispatched(Duration timeout) {
    return dispatched(timeout, new CancellationToken());
  }

  private DispatchedCall dispatched(Duration timeout, CancellationToken session) {
    return dispatched(timeout, session, new CategoryPermits(1, new CategoryPermits.Scope()));
  }

  private DispatchedCall dispatched(
      Duration timeout, CancellationToken session, CategoryPermits permits) {
    return dispatched(timeout, session, permits, this::counting);
  }

  private DispatchedCall dispatched(
      Duration timeout, CancellationToken session, CategoryPermits permits, ToolExecutor executor) {
    return new DispatchedCall(
        ToolBinding.newBuilder(
                Tool.newBuilder().withName("t").withDescription("t").withExecutor(executor).build())
            .withCategory(ToolCategory.WRITE)
            .build(),
        new ToolCall("c", "t", Map.of()),
        SessionContext.forTesting("dispatched-call"),
        session,
        timeout,
        permits,
        live);
  }

  private ToolResult counting(Map<String, Object> args, ToolContext ctx) {
    return ToolResult.success("run " + runs.incrementAndGet());
  }

  /** Counts its run, then waits for {@link #toolRelease} through every interruption. */
  private ToolResult blocking(Map<String, Object> args, ToolContext ctx) {
    var result = counting(args, ctx);
    DispatchFixture.awaitThroughInterrupts(toolRelease);
    return result;
  }

  /** A session token whose first callback signals {@link #callbackEntered}, then blocks. */
  private CancellationToken blockedSession() {
    var session = new CancellationToken();
    session.onCancel(
        () -> {
          callbackEntered.countDown();
          DispatchFixture.awaitThroughInterrupts(callbackRelease);
        });
    return session;
  }

  /** Cancel {@code session}, whose callbacks after the first never run while the test lasts. */
  private void cancelBehindTheBlockedCallback(CancellationToken session) {
    Thread.ofVirtual().start(() -> session.cancel("session closed"));
    Await.latch("the session's first callback to block", callbackEntered);
  }

  private static void neverRefused(String reason) {
    throw new AssertionError("refused: " + reason);
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

    Await.until("the worker to end", live::isEmpty);
    assertEquals(0, runs.get(), "the tool never ran");
  }

  @Test
  void aWorkerReachingAdmissionPastItsDeadlineKeepsTheToolFromStarting() {
    busy.execute(() -> DispatchFixture.awaitThroughInterrupts(busyRelease));

    dispatched(Duration.ofNanos(1)).start(busy);

    Await.until("the worker to end", live::isEmpty);
    assertEquals(0, runs.get(), "the tool never ran");
  }

  @Test
  void aWorkerReachingAdmissionAfterItsSessionWasCancelledKeepsTheToolFromStarting()
      throws InterruptedException {
    var session = blockedSession();
    var permits = new CategoryPermits(1, new CategoryPermits.Scope());
    var holder = dispatched(NEVER, session, permits);
    permits.admit(holder, () -> true, DispatchedCallTest::neverRefused);
    dispatched(NEVER, session, permits).start(busy);
    Await.until("the worker to queue for the permit", () -> permits.queued() == 1);
    cancelBehindTheBlockedCallback(session);

    permits.release(holder);

    Await.until("the worker to end", live::isEmpty);
    assertEquals(0, runs.get(), "the tool never ran");
  }

  @Test
  void aCallArrivingWhileAnEarlierOneWaitsIsNotAdmittedBeforeIt() throws InterruptedException {
    var scope = new CategoryPermits.Scope();
    var permits = new CategoryPermits(1, scope);
    var holder = dispatched(NEVER, new CancellationToken(), permits);
    var later = dispatched(NEVER, new CancellationToken(), permits);
    permits.admit(holder, () -> true, DispatchedCallTest::neverRefused);
    dispatched(NEVER, new CancellationToken(), permits).start(busy);
    Await.until("the earlier call to queue for the permit", () -> permits.queued() == 1);

    int runsBeforeTheLaterCall;
    synchronized (scope) {
      permits.release(holder);
      permits.admit(later, () -> true, DispatchedCallTest::neverRefused);
      runsBeforeTheLaterCall = runs.get();
    }
    permits.release(later);

    Await.until("the earlier call's worker to end", live::isEmpty);
    assertEquals(
        1, runsBeforeTheLaterCall, "the earlier call ran before the later one was admitted");
  }

  @Test
  void aCallAdmittedFromTheHeadOfTheQueueLetsTheNextOneInWhileRoomRemains()
      throws InterruptedException {
    var scope = new CategoryPermits.Scope();
    var permits = new CategoryPermits(3, scope);
    var holders =
        Stream.generate(() -> dispatched(NEVER, new CancellationToken(), permits))
            .limit(3)
            .toList();
    for (var holder : holders) {
      permits.admit(holder, () -> true, DispatchedCallTest::neverRefused);
    }
    var next = dispatched(NEVER, new CancellationToken(), permits);
    dispatched(NEVER, new CancellationToken(), permits, this::blocking).start(busy);
    Await.until("the earlier call to queue for a permit", () -> permits.queued() == 1);
    var admission =
        new FutureTask<>(
            () -> {
              synchronized (scope) {
                permits.release(holders.get(0));
                permits.release(holders.get(1));
                return permits.admit(next, () -> true, DispatchedCallTest::neverRefused);
              }
            });
    Thread.ofVirtual().start(admission);

    assertTrue(Await.value("the next call to be admitted", admission));
  }

  @Test
  void aResultReadAfterTheSessionWasCancelledIsNotReturned() {
    var session = blockedSession();
    var dispatched = dispatched(NEVER, session);
    dispatched.start(busy);
    Await.until("the worker to end", live::isEmpty);
    assertEquals(1, runs.get(), "the tool ran and answered");
    cancelBehindTheBlockedCallback(session);

    var thrown = assertThrows(CancellationException.class, () -> dispatched.await(NEVER));
    assertEquals("session closed", thrown.getMessage());
  }

  @Test
  void aTimeoutReadAfterTheSessionWasCancelledIsNotReturned() {
    busy.execute(() -> DispatchFixture.awaitThroughInterrupts(busyRelease));
    var session = blockedSession();
    var dispatched = dispatched(Duration.ofNanos(1), session);
    dispatched.start(busy);
    Await.until("the worker to end", live::isEmpty);
    assertEquals(0, runs.get(), "the tool never ran");
    cancelBehindTheBlockedCallback(session);

    var thrown = assertThrows(CancellationException.class, () -> dispatched.await(NEVER));
    assertEquals("session closed", thrown.getMessage());
  }
}
