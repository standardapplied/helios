/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The interleavings {@link ToolDispatchTest} cannot force through the dispatcher, whose caller
 * always waits: the deadline passes or the session's token is cancelled before the worker starts
 * the tool, and no caller has yet cancelled the call, and the session's token is cancelled, its
 * callbacks held up by an earlier one, before the caller reads the call's result or its timeout. A
 * set of live calls that cancels the session as the call is added to it does so after the call is
 * linked to the session and before its worker starts.
 */
final class DispatchedCallTest {

  private static final Duration NEVER = Duration.ofMinutes(10);

  private final AtomicInteger runs = new AtomicInteger();
  private final Set<DispatchedCall> live = ConcurrentHashMap.newKeySet();
  private final SessionEnding ending =
      new SessionEnding("dispatched-call", new CancellationToken());
  private final CountDownLatch callbackEntered = new CountDownLatch(1);
  private final CountDownLatch callbackRelease = new CountDownLatch(1);

  @AfterEach
  void releaseTheCallbacks() {
    callbackRelease.countDown();
  }

  private DispatchedCall dispatched(Duration timeout) {
    return dispatched(timeout, new CancellationToken());
  }

  private DispatchedCall dispatched(Duration timeout, CancellationToken session) {
    return dispatched(timeout, session, live, this::counting);
  }

  private DispatchedCall dispatched(
      Duration timeout, CancellationToken session, ToolExecutor executor) {
    return dispatched(timeout, session, live, executor);
  }

  private DispatchedCall dispatched(
      Duration timeout,
      CancellationToken session,
      Set<DispatchedCall> liveCalls,
      ToolExecutor executor) {
    return new DispatchedCall(
        ToolBinding.newBuilder(
                Tool.newBuilder().withName("t").withDescription("t").withExecutor(executor).build())
            .withCategory(ToolCategory.WRITE)
            .build(),
        new ToolCall("c", "t", Map.of()),
        SessionContext.forTesting("dispatched-call"),
        session,
        timeout,
        key -> false,
        liveCalls,
        abandoned -> {},
        ending);
  }

  private ToolResult counting(Map<String, Object> args, ToolContext ctx) {
    return ToolResult.success("run " + runs.incrementAndGet());
  }

  /** A session token whose first callback signals {@link #callbackEntered}, then blocks. */
  private CancellationToken blockedSession() {
    var session = new CancellationToken();
    session.onCancel(
        () -> {
          callbackEntered.countDown();
          Uninterruptible.await(callbackRelease);
        });
    return session;
  }

  /** Cancel {@code session}, whose callbacks after the first never run while the test lasts. */
  private void cancelBehindTheBlockedCallback(CancellationToken session) {
    Thread.ofVirtual().start(() -> session.cancel("session closed"));
    Await.latch("the session's first callback to block", callbackEntered);
  }

  /**
   * The live calls of a call whose session is cancelled, behind its blocked first callback, as the
   * call is added: after the call is linked to the session, before its worker starts.
   */
  private Set<DispatchedCall> cancellingOnAdd(CancellationToken session) {
    return new CopyOnWriteArraySet<>() {
      @Override
      public boolean add(DispatchedCall call) {
        cancelBehindTheBlockedCallback(session);
        return super.add(call);
      }
    };
  }

  @Test
  void aDeadlineThatFiredBeforeTheWorkerStartedKeepsTheToolFromStarting() {
    var dispatched = dispatched(NEVER);
    dispatched.expire();

    dispatched.start();

    Await.until("the worker to end", live::isEmpty);
    assertEquals(0, runs.get(), "the tool never ran");
  }

  @Test
  void aWorkerStartingPastItsDeadlineKeepsTheToolFromStarting() {
    dispatched(Duration.ofNanos(1)).start();

    Await.until("the worker to end", live::isEmpty);
    assertEquals(0, runs.get(), "the tool never ran");
  }

  @Test
  void aWorkerStartingAfterItsSessionWasCancelledKeepsTheToolFromStarting() {
    var session = blockedSession();
    var calls = cancellingOnAdd(session);

    dispatched(NEVER, session, calls, this::counting).start();

    Await.until("the worker to end", calls::isEmpty);
    assertEquals(0, runs.get(), "the tool never ran");
  }

  @Test
  void aCallLeavesItsLiveCallsOnlyOnceItsWorkHasEnded() {
    var runningWhenRemoved = new CompletableFuture<Boolean>();
    var calls =
        new CopyOnWriteArraySet<DispatchedCall>() {
          @Override
          public boolean remove(Object call) {
            runningWhenRemoved.complete(((DispatchedCall) call).isRunning());
            return super.remove(call);
          }
        };

    dispatched(NEVER, new CancellationToken(), calls, this::counting).start();

    assertFalse(Await.value("the call to leave its live calls", runningWhenRemoved));
  }

  @Test
  void aResultReadAfterTheSessionWasCancelledIsNotReturned() {
    var session = blockedSession();
    var dispatched = dispatched(NEVER, session);
    dispatched.start();
    Await.until("the worker to end", live::isEmpty);
    assertEquals(1, runs.get(), "the tool ran and answered");
    cancelBehindTheBlockedCallback(session);

    var thrown = assertThrows(CancellationException.class, () -> dispatched.await(NEVER));
    assertEquals("session closed", thrown.getMessage());
  }

  @Test
  void aCallerInterruptedAfterTheToolAnsweredIsCancelledWithItsInterruptStatusKept() {
    var dispatched = dispatched(NEVER);
    dispatched.start();
    Await.until("the worker to end", live::isEmpty);
    assertEquals(1, runs.get(), "the tool ran and answered");

    Thread.currentThread().interrupt();
    var thrown = assertThrows(CancellationException.class, () -> dispatched.await(NEVER));

    assertTrue(Thread.interrupted(), "the caller's interrupt status is kept");
    assertEquals("interrupted waiting for tool t", thrown.getMessage());
  }

  @Test
  void anErrorTheToolThrewIsThrownAsItselfOnceTheSessionWasCancelled() {
    var sentinel = new AssertionError("tool failure");
    var session = new CancellationToken();
    var dispatched =
        dispatched(
            NEVER,
            session,
            (args, ctx) -> {
              throw sentinel;
            });
    dispatched.start();
    Await.until("the worker to end", live::isEmpty);
    session.cancel("session closed");

    assertSame(sentinel, assertThrows(AssertionError.class, () -> dispatched.await(NEVER)));
    assertSame(sentinel, ending.seal().orElseThrow());
  }

  @Test
  void aThrowableThatIsNoErrorReadOnceTheSessionWasCancelledIsACancellation() {
    var session = new CancellationToken();
    var dispatched =
        dispatched(
            NEVER,
            session,
            (args, ctx) ->
                DispatchFixture.sneakyThrow(new Throwable("neither an Exception nor an Error")));
    dispatched.start();
    Await.until("the worker to end", live::isEmpty);
    session.cancel("session closed");

    var thrown = assertThrows(CancellationException.class, () -> dispatched.await(NEVER));
    assertEquals("session closed", thrown.getMessage());
  }

  @Test
  void aTimeoutReadAfterTheSessionWasCancelledIsNotReturned() {
    var session = blockedSession();
    var dispatched = dispatched(Duration.ofNanos(1), session);
    dispatched.start();
    Await.until("the worker to end", live::isEmpty);
    assertEquals(0, runs.get(), "the tool never ran");
    cancelBehindTheBlockedCallback(session);

    var thrown = assertThrows(CancellationException.class, () -> dispatched.await(NEVER));
    assertEquals("session closed", thrown.getMessage());
  }
}
