/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static com.standardapplied.helios.session.loop.DispatchFixture.NEVER;
import static com.standardapplied.helios.session.loop.DispatchFixture.SHORT;
import static com.standardapplied.helios.session.loop.DispatchFixture.assertCancelled;
import static com.standardapplied.helios.session.loop.DispatchFixture.awaitIdle;
import static com.standardapplied.helios.session.loop.DispatchFixture.binding;
import static com.standardapplied.helios.session.loop.DispatchFixture.start;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolDispatch} when the session's token is cancelled or the caller is interrupted, and when
 * a callback on the call's own token blocks or throws. Deadlines and stop graces are set up as in
 * {@link ToolDispatchTest}.
 */
final class ToolDispatchCancellationTest {

  private final DispatchFixture fixture = new DispatchFixture();

  @AfterEach
  void closeTheFixture() {
    fixture.close();
  }

  // ── cancellation and interruption ─────────────────────────────────────────

  @Test
  void cancellingTheSessionWhileTheCallQueuesEndsItAndNeverStartsTheTool() {
    var starts = new AtomicInteger();
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(1, 1, 1, 1),
            binding("slow", ToolCategory.READ, fixture.stuck(starts)));
    var holder = start(d, "slow");
    Await.latch("the first call to take the only permit", fixture.entered);
    var session = new CancellationToken();

    var queued = start(d, "slow", session, NEVER);
    Await.until(
        "the second call to queue for the permit",
        () -> d.queuedDispatches(ToolCategory.READ) == 1);
    session.cancel("user-stop");

    assertCancelled("user-stop", queued);
    fixture.release.countDown();
    assertEquals(ToolResult.success("done"), holder.ending("the first dispatch").result());
    awaitIdle(d);
    assertEquals(1, starts.get(), "the cancelled call's tool never ran");
    assertEquals(0, session.activeCallbackCountForTests(), "no link to the session is left");
  }

  @Test
  void cancellingTheSessionWhileTheToolRunsEndsTheCallAndInterruptsTheTool() {
    var interrupted = new CountDownLatch(1);
    var d =
        fixture.dispatcher(
            binding("blocking", ToolCategory.READ, fixture.interruptible(interrupted)));
    var session = new CancellationToken();

    var running = start(d, "blocking", session, NEVER);
    Await.latch("the tool to start", fixture.entered);
    session.cancel("session closed");

    assertCancelled("session closed", running);
    Await.latch("the tool to be interrupted", interrupted);
    awaitIdle(d);
    assertEquals(0, session.activeCallbackCountForTests(), "no link to the session is left");
  }

  @Test
  void cancellingTheSessionReachesACallAlreadyAnsweredUnknown() {
    var firstInterrupt = new CountDownLatch(1);
    var secondInterrupt = new CountDownLatch(1);
    ToolExecutor needsTwo =
        (args, ctx) -> {
          fixture.entered.countDown();
          while (secondInterrupt.getCount() > 0) {
            try {
              fixture.release.await();
              return ToolResult.success("released");
            } catch (InterruptedException e) {
              (firstInterrupt.getCount() > 0 ? firstInterrupt : secondInterrupt).countDown();
            }
          }
          return ToolResult.failure("stopped");
        };
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8), binding("write", ToolCategory.WRITE, needsTwo));
    var session = new CancellationToken();
    var running = start(d, "write", session, SHORT);
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();
    assertEquals(ToolResult.unknown("write", NEVER), running.ending("the dispatch").result());
    Await.latch("the timeout's interrupt to reach the tool", firstInterrupt);

    session.cancel("session closed");

    Await.latch("the session's cancellation to interrupt it again", secondInterrupt);
    awaitIdle(d);
    assertEquals(1, d.availableFileWritePermits(), "the write permit is back");
  }

  @Test
  void interruptingTheCallerWhileTheToolRunsEndsTheCallAndKeepsTheInterrupt() {
    var interrupted = new CountDownLatch(1);
    var d =
        fixture.dispatcher(
            binding("blocking", ToolCategory.READ, fixture.interruptible(interrupted)));

    var running = start(d, "blocking");
    Await.latch("the tool to start", fixture.entered);
    running.thread().interrupt();

    var ending = running.ending("the interrupted dispatch");
    assertInstanceOf(CancellationException.class, ending.thrown());
    assertEquals("interrupted waiting for tool blocking", ending.thrown().getMessage());
    assertTrue(ending.interrupted(), "the caller's interrupt status is kept");
    Await.latch("the tool to be interrupted", interrupted);
  }

  @Test
  void interruptingTheCallerDuringTheStopGraceEndsTheCallAndKeepsTheInterrupt() {
    var cancelled = new CountDownLatch(1);
    var stuck = fixture.stuckSignalling(cancelled);
    var d = fixture.dispatcher(binding("stuck", ToolCategory.READ, stuck));

    var running = start(d, "stuck");
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();
    Await.latch("the call to be timed out", cancelled);
    running.thread().interrupt();

    var ending = running.ending("the interrupted dispatch");
    assertInstanceOf(CancellationException.class, ending.thrown());
    assertEquals("interrupted waiting for tool stuck", ending.thrown().getMessage());
    assertTrue(ending.interrupted(), "the caller's interrupt status is kept");
  }

  @Test
  void cancellingTheSessionDuringTheStopGraceEndsTheCall() {
    var cancelled = new CountDownLatch(1);
    var stuck = fixture.stuckSignalling(cancelled);
    var d = fixture.dispatcher(binding("stuck", ToolCategory.READ, stuck));
    var session = new CancellationToken();

    var running = start(d, "stuck", session, NEVER);
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();
    Await.latch("the call to be timed out", cancelled);
    session.cancel("session closed");

    assertCancelled("session closed", running);
  }

  @Test
  void cancellingTheSessionWhileAnEarlierCallbackBlocksEndsARunningCall() {
    var session = new CancellationToken();
    var callbackEntered = new CountDownLatch(1);
    fixture.blockFirstCallback(session, callbackEntered);
    var cancelled = new CountDownLatch(1);
    var stuck = fixture.stuckSignalling(cancelled);
    var d = fixture.dispatcher(binding("stuck", ToolCategory.READ, stuck));
    var running = start(d, "stuck", session, NEVER);
    Await.latch("the tool to start", fixture.entered);

    Thread.ofVirtual().start(() -> session.cancel("session closed"));
    Await.latch("the session's first callback to block", callbackEntered);

    assertCancelled("session closed", running);
    Await.latch("the call's own token to be cancelled", cancelled);
  }

  @Test
  void cancellingTheSessionDuringTheStopGraceWhileAnEarlierCallbackBlocksEndsTheCall() {
    var session = new CancellationToken();
    var callbackEntered = new CountDownLatch(1);
    fixture.blockFirstCallback(session, callbackEntered);
    var cancelled = new CountDownLatch(1);
    var stuck = fixture.stuckSignalling(cancelled);
    var d = fixture.dispatcher(binding("stuck", ToolCategory.READ, stuck));
    var running = start(d, "stuck", session, NEVER);
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();
    Await.latch("the call to be timed out", cancelled);

    Thread.ofVirtual().start(() -> session.cancel("session closed"));
    Await.latch("the session's first callback to block", callbackEntered);

    assertCancelled("session closed", running);
  }

  @Test
  void cancellingTheSessionWhileAStuckWriterRunsRefusesEveryCallUntilItEnds() {
    var starts = new AtomicInteger();
    var d = fixture.dispatcher(binding("write", ToolCategory.WRITE, fixture.stuck(starts)));
    var session = new CancellationToken();
    var first = start(d, "write", session, NEVER);
    Await.latch("the first writer to start", fixture.entered);
    session.cancel("user-stop");
    assertCancelled("user-stop", first);

    fixture.assertTheNextWriterIsRefusedUntilTheStuckOneEnds(d, starts);
  }

  @Test
  void cancellingTheSessionDuringTheStopGraceRefusesEveryCallUntilItEnds() {
    var starts = new AtomicInteger();
    var cancelled = new CountDownLatch(1);
    var stuck = fixture.stuck(starts);
    var d =
        fixture.dispatcher(
            binding(
                "write",
                ToolCategory.WRITE,
                (args, ctx) -> {
                  ctx.cancellation().onCancel(cancelled::countDown);
                  return stuck.execute(args, ctx);
                }));
    var session = new CancellationToken();
    var first = start(d, "write", session, NEVER);
    Await.latch("the first writer to start", fixture.entered);
    fixture.scheduler.fireLatest();
    Await.latch("the first writer to be timed out", cancelled);
    session.cancel("user-stop");
    assertCancelled("user-stop", first);

    fixture.assertTheNextWriterIsRefusedUntilTheStuckOneEnds(d, starts);
  }

  @Test
  void interruptingTheCallerWhileAStuckWriterRunsRefusesEveryCallUntilItEnds() {
    var starts = new AtomicInteger();
    var d = fixture.dispatcher(binding("write", ToolCategory.WRITE, fixture.stuck(starts)));
    var first = start(d, "write");
    Await.latch("the first writer to start", fixture.entered);
    first.thread().interrupt();
    assertCancelled("interrupted waiting for tool write", first);

    fixture.assertTheNextWriterIsRefusedUntilTheStuckOneEnds(d, starts);
  }

  // ── the call's cancellation callbacks ─────────────────────────────────────

  @Test
  void aBlockingCancellationCallbackDoesNotHoldBackTheInterrupt() {
    var interrupted = new CountDownLatch(1);
    var d =
        fixture.dispatcher(
            binding("blocking", ToolCategory.READ, fixture.blockingCallback(interrupted)));

    var running = start(d, "blocking");
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();

    Await.latch("the tool to be interrupted", interrupted);
    fixture.release.countDown();
    assertEquals(ToolResult.timedOut("blocking", NEVER), running.ending("the dispatch").result());
  }

  @Test
  void aBlockingCancellationCallbackDoesNotHoldUpTheSessionsCancellation() {
    var d =
        fixture.dispatcher(
            binding(
                "blocking", ToolCategory.READ, fixture.blockingCallback(new CountDownLatch(1))));
    var session = new CancellationToken();
    var running = start(d, "blocking", session, NEVER);
    Await.latch("the tool to start", fixture.entered);

    var cancelling = new FutureTask<>(() -> session.cancel("session closed"));
    Thread.ofVirtual().start(cancelling);

    assertTrue(Await.value("the session's cancellation to return", cancelling));
    assertCancelled("session closed", running);
  }

  @Test
  void aCallWhoseCancellationCallbackStillRunsIsUnknownAndRefusesEveryCall() {
    var starts = new AtomicInteger();
    ToolExecutor writer =
        (args, ctx) -> {
          starts.incrementAndGet();
          return fixture.blockingCallback(new CountDownLatch(1)).execute(args, ctx);
        };
    var d = fixture.dispatcher(binding("write", ToolCategory.WRITE, writer));
    var first = start(d, "write", new CancellationToken(), SHORT);
    Await.latch("the first writer to start", fixture.entered);
    fixture.scheduler.fireLatest();
    assertEquals(ToolResult.unknown("write", NEVER), first.ending("the first dispatch").result());

    fixture.assertTheNextWriterIsRefusedUntilTheStuckOneEnds(d, starts);
  }

  @Test
  void aCancellationCallbackErrorIsKeptAsAStrayErrorAndTheToolIsStillInterrupted() {
    var sentinel = new AssertionError("broken callback");
    var interrupted = new CountDownLatch(1);
    var d =
        fixture.dispatcher(
            binding("throwing", ToolCategory.READ, throwingCallback(sentinel, interrupted)));
    var session = new CancellationToken();

    var running = start(d, "throwing", session, NEVER);
    Await.latch("the tool to start", fixture.entered);
    session.cancel("session closed");
    Await.latch("the tool to be interrupted", interrupted);
    assertCancelled("session closed", running);
    awaitIdle(d);

    assertEquals(List.of(sentinel), d.strayErrors());
  }

  @Test
  void aCancellationCallbackErrorOfATimedOutCallIsThrownByItsDispatchAsItself() {
    var sentinel = new AssertionError("broken callback");
    var d =
        fixture.dispatcher(
            binding(
                "throwing", ToolCategory.READ, throwingCallback(sentinel, new CountDownLatch(1))));
    var dispatching =
        new FutureTask<>(
            () ->
                d.dispatch(
                    DispatchFixture.call("throwing"), new CancellationToken(), NEVER, NEVER));
    Thread.ofVirtual().start(dispatching);
    Await.latch("the tool to start", fixture.entered);

    fixture.scheduler.fireLatest();

    assertSame(sentinel, Await.failure("the timed-out dispatch", dispatching));
  }

  /**
   * Registers a callback on the call's token that throws {@code error}, then runs interruptibly.
   */
  private ToolExecutor throwingCallback(Error error, CountDownLatch interrupted) {
    return (args, ctx) -> {
      ctx.cancellation()
          .onCancel(
              () -> {
                throw error;
              });
      return fixture.interruptible(interrupted).execute(args, ctx);
    };
  }

  // ── waiting for the calls still running ──────────────────────────────────

  @Test
  void waitingForLiveCallsReturnsNoneOnceTheCancelledCallsWorkHasEnded() {
    var d =
        fixture.dispatcher(binding("slow", ToolCategory.READ, fixture.stuck(new AtomicInteger())));
    var session = new CancellationToken();
    var running = start(d, "slow", session, NEVER);
    Await.latch("the tool to start", fixture.entered);
    session.cancel("session ended");
    assertCancelled("session ended", running);

    var waiting = new FutureTask<>(() -> d.awaitLiveCalls(NEVER));
    Thread.ofVirtual().start(waiting);
    fixture.release.countDown();

    assertEquals(List.of(), Await.value("the wait for the live calls", waiting));
  }

  @Test
  void anInterruptedWaitForLiveCallsReportsTheCallStillRunningAndKeepsTheInterrupt() {
    var d =
        fixture.dispatcher(binding("slow", ToolCategory.READ, fixture.stuck(new AtomicInteger())));
    start(d, "slow", new CancellationToken(), NEVER);
    Await.latch("the tool to start", fixture.entered);

    Thread.currentThread().interrupt();
    var live = d.awaitLiveCalls(NEVER);

    assertTrue(Thread.interrupted(), "the wait keeps the caller's interrupt");
    assertEquals(List.of(DispatchFixture.call("slow")), live);
  }
}
