/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ToolDispatch} owns each call from admission to the end of its worker. Every deadline runs
 * on a {@link ManualScheduler} with a delay beyond the hang guard, so a deadline passes only when a
 * test fires it; a stop grace under test is a millisecond against a tool that cannot stop, and
 * otherwise beyond the hang guard. Tools that block wait on {@link #release}, which every test
 * opens when it ends.
 */
final class ToolDispatchTest {

  private static final SessionContext CTX = SessionContext.forTesting("dispatch-test");
  private static final Duration NEVER = Duration.ofMinutes(10);
  private static final Duration SHORT = Duration.ofMillis(1);

  private final ManualScheduler scheduler = new ManualScheduler();
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch entered = new CountDownLatch(1);

  /** What a dispatch on its own thread ended with, and whether that thread was left interrupted. */
  private record Ending(ToolResult result, RuntimeException thrown, boolean interrupted) {}

  /** A dispatch running on its own virtual thread. */
  private record Running(FutureTask<Ending> task, Thread thread) {

    Ending ending(String description) {
      return Await.value(description, task);
    }
  }

  @AfterEach
  void releaseToolsAndStopTheScheduler() {
    release.countDown();
    scheduler.shutdownNow();
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private ToolDispatch dispatcher(ConcurrencyLimits limits, ToolBinding... bindings) {
    return new ToolDispatch(CTX, new ToolRegistry(List.of(bindings)), limits, scheduler);
  }

  private ToolDispatch dispatcher(ToolBinding... bindings) {
    return dispatcher(ConcurrencyLimits.defaults(), bindings);
  }

  private static ToolBinding binding(String name, ToolCategory category, ToolExecutor executor) {
    var tool =
        Tool.newBuilder().withName(name).withDescription(name).withExecutor(executor).build();
    return ToolBinding.newBuilder(tool).withCategory(category).build();
  }

  private static ToolCall call(String name) {
    return new ToolCall("c", name, Map.of());
  }

  private static Running start(
      ToolDispatch d,
      ToolCall call,
      CancellationToken token,
      Duration timeout,
      Duration stopGrace) {
    var task =
        new FutureTask<>(
            () -> {
              try {
                return new Ending(d.dispatch(call, token, timeout, stopGrace), null, false);
              } catch (RuntimeException e) {
                return new Ending(null, e, Thread.currentThread().isInterrupted());
              }
            });
    return new Running(task, Thread.ofVirtual().start(task));
  }

  private static Running start(
      ToolDispatch d, String name, CancellationToken token, Duration stopGrace) {
    return start(d, call(name), token, NEVER, stopGrace);
  }

  private static Running start(ToolDispatch d, String name) {
    return start(d, name, new CancellationToken(), NEVER);
  }

  /** Signals {@link #entered}, then waits for {@link #release}, ending early on interruption. */
  private ToolExecutor interruptible(CountDownLatch interrupted) {
    return (args, ctx) -> {
      entered.countDown();
      try {
        release.await();
        return ToolResult.success("done");
      } catch (InterruptedException e) {
        interrupted.countDown();
        return ToolResult.failure("interrupted");
      }
    };
  }

  /** Signals {@link #entered}, then waits for {@link #release} through every interruption. */
  private ToolExecutor stubborn(ToolResult result) {
    return (args, ctx) -> {
      entered.countDown();
      awaitThroughInterrupts(release);
      return result;
    };
  }

  static void awaitThroughInterrupts(CountDownLatch latch) {
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

  /**
   * Counts its starts, signals {@link #entered}, then waits for {@link #release} through every
   * interruption.
   */
  private ToolExecutor stuckWriter(AtomicInteger starts) {
    return (args, ctx) -> {
      starts.incrementAndGet();
      entered.countDown();
      awaitThroughInterrupts(release);
      return ToolResult.success("written");
    };
  }

  /**
   * The next call of {@code d}'s {@code write} tool queues and starts only once {@link #release}
   * opens.
   */
  private void assertTheNextWriterWaitsForRelease(ToolDispatch d, AtomicInteger starts) {
    var next = start(d, "write");
    Await.until(
        "the next writer to queue for the write permit",
        () -> d.queuedDispatches(ToolCategory.WRITE) == 1);
    assertEquals(1, starts.get(), "the next writer has not started");

    release.countDown();
    assertEquals(ToolResult.success("written"), next.ending("the next dispatch").result());
    assertEquals(2, starts.get());
  }

  /**
   * Registers a cancellation callback that waits for {@link #release}, signals {@link #entered},
   * then waits for {@link #release}, ending early on interruption.
   */
  private ToolExecutor blockingCallback(CountDownLatch interrupted) {
    return (args, ctx) -> {
      ctx.cancellation().onCancel(() -> awaitThroughInterrupts(release));
      return interruptible(interrupted).execute(args, ctx);
    };
  }

  private static void awaitIdle(ToolDispatch d) {
    Await.until("every worker to end", () -> d.liveCalls() == 0);
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullSessionContext() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolDispatch(
                    null, ToolRegistry.empty(), ConcurrencyLimits.defaults(), scheduler));
    assertEquals("sessionContext must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullRegistry() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ToolDispatch(CTX, null, ConcurrencyLimits.defaults(), scheduler));
    assertEquals("registry must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullLimits() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ToolDispatch(CTX, ToolRegistry.empty(), null, scheduler));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullScheduler() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ToolDispatch(CTX, ToolRegistry.empty(), ConcurrencyLimits.defaults(), null));
    assertEquals("scheduler must not be null", ex.getMessage());
  }

  @Test
  void accessorsReturnTheConstructorValues() {
    var registry = ToolRegistry.empty();
    var limits = new ConcurrencyLimits(8, 2, 1, 32);
    var d = new ToolDispatch(CTX, registry, limits, scheduler);
    assertSame(CTX, d.sessionContext());
    assertSame(registry, d.registry());
    assertSame(limits, d.limits());
  }

  @Test
  void permitCountsTrackLimits() {
    var d = dispatcher(new ConcurrencyLimits(8, 2, 1, 32));
    assertEquals(8, d.availableToolCallPermits());
    assertEquals(2, d.availableFileWritePermits());
    assertEquals(1, d.availableExecutionPermits());
  }

  // ── dispatch validation ───────────────────────────────────────────────────

  @Test
  void dispatchRejectsNullCall() {
    var d = dispatcher();
    var token = new CancellationToken();
    var ex = assertThrows(NullPointerException.class, () -> d.dispatch(null, token, NEVER, NEVER));
    assertEquals("call must not be null", ex.getMessage());
  }

  @Test
  void dispatchRejectsNullCancellation() {
    var d = dispatcher();
    var c = call("read");
    var ex = assertThrows(NullPointerException.class, () -> d.dispatch(c, null, NEVER, NEVER));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void dispatchRejectsNullTimeoutAndStopGrace() {
    var d = dispatcher();
    var c = call("read");
    var token = new CancellationToken();
    var timeout = assertThrows(NullPointerException.class, () -> d.dispatch(c, token, null, NEVER));
    var grace = assertThrows(NullPointerException.class, () -> d.dispatch(c, token, NEVER, null));
    assertEquals("timeout must not be null", timeout.getMessage());
    assertEquals("stopGrace must not be null", grace.getMessage());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void dispatchRejectsANonPositiveTimeout(long millis) {
    var d = dispatcher();
    var c = call("read");
    var token = new CancellationToken();
    var timeout = Duration.ofMillis(millis);
    var ex =
        assertThrows(IllegalArgumentException.class, () -> d.dispatch(c, token, timeout, NEVER));
    assertEquals("timeout must be strictly positive, got " + timeout, ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void dispatchRejectsANonPositiveStopGrace(long millis) {
    var d = dispatcher();
    var c = call("read");
    var token = new CancellationToken();
    var grace = Duration.ofMillis(millis);
    var ex = assertThrows(IllegalArgumentException.class, () -> d.dispatch(c, token, NEVER, grace));
    assertEquals("stopGrace must be strictly positive, got " + grace, ex.getMessage());
  }

  @Test
  void unknownToolReturnsNotFound() {
    var result = dispatcher().dispatch(call("nope"), new CancellationToken(), NEVER, NEVER);
    assertEquals(ToolResult.notFound("nope"), result);
  }

  @Test
  void aCancelledSessionDispatchesNothing() {
    var runs = new AtomicInteger();
    var d =
        dispatcher(
            binding(
                "echo",
                ToolCategory.READ,
                (args, ctx) -> ToolResult.success("run " + runs.incrementAndGet())));
    var token = new CancellationToken();
    token.cancel("user-stop");
    var c = call("echo");

    var ex = assertThrows(CancellationException.class, () -> d.dispatch(c, token, NEVER, NEVER));

    assertEquals("user-stop", ex.getMessage());
    assertEquals(0, runs.get());
    assertEquals(0, d.liveCalls());
  }

  // ── a call that completes ─────────────────────────────────────────────────

  @Test
  void dispatchReturnsTheToolsResult() {
    var d =
        dispatcher(
            binding(
                "echo",
                ToolCategory.READ,
                (args, ctx) -> ToolResult.success("echoed: " + args.get("v"))));
    var result =
        d.dispatch(
            new ToolCall("c", "echo", Map.of("v", "hello")), new CancellationToken(), NEVER, NEVER);
    assertEquals(ToolResult.success("echoed: hello"), result);
  }

  @Test
  void dispatchReturnsAReportedFailure() {
    var d =
        dispatcher(
            binding("bad", ToolCategory.READ, (args, ctx) -> ToolResult.failure("intentional")));
    var result = d.dispatch(call("bad"), new CancellationToken(), NEVER, NEVER);
    assertEquals(ToolResult.failure("intentional"), result);
  }

  @Test
  void theToolSeesTheSessionItsOwnTokenAndItsTimeout() {
    var seen = new AtomicReference<ToolContext>();
    var d =
        dispatcher(
            binding(
                "look",
                ToolCategory.READ,
                (args, ctx) -> {
                  seen.set(ctx);
                  return ToolResult.success("seen");
                }));
    var token = new CancellationToken();

    d.dispatch(call("look"), token, NEVER, NEVER);

    var ctx = seen.get();
    assertSame(CTX, ctx.sessionContext());
    assertNotSame(token, ctx.cancellation());
    assertNotSame(CTX.cancellation(), ctx.cancellation());
    assertEquals(NEVER, ctx.deadline());
  }

  @Test
  void aToolErrorIsRethrownAsItself() {
    var sentinel = new AssertionError("broken tool");
    var d =
        dispatcher(
            binding(
                "boom",
                ToolCategory.READ,
                (args, ctx) -> {
                  throw sentinel;
                }));
    var c = call("boom");
    var token = new CancellationToken();

    var thrown = assertThrows(AssertionError.class, () -> d.dispatch(c, token, NEVER, NEVER));

    assertSame(sentinel, thrown);
  }

  // ── per-category permits ──────────────────────────────────────────────────

  private void assertHeldPermits(
      ToolCategory category, int toolCalls, int fileWrites, int executions) {
    var d =
        dispatcher(
            new ConcurrencyLimits(8, 4, 2, 8),
            binding("t", category, stubborn(ToolResult.success("done"))));

    var running = start(d, "t");
    Await.latch("the tool to start", entered);

    assertEquals(toolCalls, d.availableToolCallPermits(), "general pool");
    assertEquals(fileWrites, d.availableFileWritePermits(), "file-write pool");
    assertEquals(executions, d.availableExecutionPermits(), "execution pool");
    release.countDown();
    assertEquals(ToolResult.success("done"), running.ending("the dispatch").result());
  }

  @Test
  void writeCategoryAcquiresFileWritePermits() {
    assertHeldPermits(ToolCategory.WRITE, 8, 3, 2);
  }

  @Test
  void executionCategoryAcquiresExecutionPermits() {
    assertHeldPermits(ToolCategory.EXECUTION, 8, 4, 1);
  }

  @Test
  void readCategoryAcquiresGeneralToolCallPermits() {
    assertHeldPermits(ToolCategory.READ, 7, 4, 2);
  }

  @Test
  void thePermitIsBackOnceTheWorkerEnds() {
    var d = dispatcher(binding("echo", ToolCategory.READ, (args, ctx) -> ToolResult.success("x")));
    d.dispatch(call("echo"), new CancellationToken(), NEVER, NEVER);
    awaitIdle(d);
    assertEquals(
        ConcurrencyLimits.defaults().maxConcurrentToolCalls(), d.availableToolCallPermits());
  }

  @Test
  void capExactlyBoundsConcurrentDispatch() {
    var started = new CountDownLatch(2);
    var inFlight = new AtomicInteger();
    var peak = new AtomicInteger();
    ToolExecutor slow =
        (args, ctx) -> {
          var current = inFlight.incrementAndGet();
          peak.updateAndGet(p -> Math.max(p, current));
          started.countDown();
          awaitThroughInterrupts(release);
          inFlight.decrementAndGet();
          return ToolResult.success("ok");
        };
    var d = dispatcher(new ConcurrencyLimits(2, 4, 1, 8), binding("slow", ToolCategory.READ, slow));

    var dispatches = Stream.generate(() -> start(d, "slow")).limit(6).toList();
    Await.latch("two tools to start", started);
    Await.until(
        "the other four calls to queue for a permit",
        () -> d.queuedDispatches(ToolCategory.READ) == 4);

    assertEquals(2, inFlight.get(), "exactly the cap is running while the rest wait");
    release.countDown();
    for (var dispatch : dispatches) {
      assertEquals(ToolResult.success("ok"), dispatch.ending("a capped dispatch").result());
    }
    awaitIdle(d);
    assertEquals(2, peak.get(), "peak in-flight equals the cap");
    assertEquals(2, d.availableToolCallPermits(), "every permit is back in the pool");
  }

  // ── timeouts ──────────────────────────────────────────────────────────────

  @Test
  void aToolThatHonoursItsTokenStopsAtTheDeadlineAndIsTimedOut() {
    var stopped = new CountDownLatch(1);
    ToolExecutor tokenOnly =
        (args, ctx) -> {
          var cancelled = new CountDownLatch(1);
          ctx.cancellation().onCancel(cancelled::countDown);
          entered.countDown();
          awaitThroughInterrupts(cancelled);
          stopped.countDown();
          return ToolResult.success("stopped");
        };
    var d = dispatcher(binding("coop", ToolCategory.READ, tokenOnly));
    var session = new CancellationToken();

    var running = start(d, "coop", session, NEVER);
    Await.latch("the tool to start", entered);
    scheduler.fireLatest();

    assertEquals(ToolResult.timedOut("coop", NEVER), running.ending("the dispatch").result());
    Await.latch("the tool to stop", stopped);
    assertFalse(session.isCancelled(), "the call's timeout does not cancel the session");
    assertFalse(CTX.cancellation().isCancelled(), "nor the session context's token");
  }

  @Test
  void aToolThatHonoursInterruptionIsInterruptedAtTheDeadlineAndIsTimedOut() {
    var interrupted = new CountDownLatch(1);
    var d = dispatcher(binding("blocking", ToolCategory.READ, interruptible(interrupted)));

    var running = start(d, "blocking");
    Await.latch("the tool to start", entered);
    scheduler.fireLatest();

    assertEquals(ToolResult.timedOut("blocking", NEVER), running.ending("the dispatch").result());
    Await.latch("the tool to be interrupted", interrupted);
  }

  @Test
  void aToolStillRunningAfterTheStopGraceIsUnknownAndKeepsItsPermit() {
    var d =
        dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, stubborn(ToolResult.success("late"))));

    var running = start(d, "write", new CancellationToken(), SHORT);
    Await.latch("the tool to start", entered);
    scheduler.fireLatest();

    assertEquals(ToolResult.unknown("write", NEVER), running.ending("the dispatch").result());
    assertEquals(1, d.liveCalls(), "the worker is still running");
    assertEquals(0, d.availableFileWritePermits(), "and still holds the write permit");
  }

  @Test
  void aStuckWriterAdmitsNoOtherWriterUntilItEndsThoughPermitsAreFree() {
    var starts = new AtomicInteger();
    var d = dispatcher(binding("write", ToolCategory.WRITE, stuckWriter(starts)));
    var first = start(d, "write", new CancellationToken(), SHORT);
    Await.latch("the first writer to start", entered);
    scheduler.fireLatest();
    assertEquals(ToolResult.unknown("write", NEVER), first.ending("the first dispatch").result());

    assertTheNextWriterWaitsForRelease(d, starts);
  }

  @Test
  void aCallWhoseDeadlinePassesWhileTheSchedulerIsBusyTimesOutWithoutStartingTheTool() {
    var schedulerFree = new CountDownLatch(1);
    scheduler.execute(() -> awaitThroughInterrupts(schedulerFree));
    var starts = new AtomicInteger();
    var d =
        dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, stuckWriter(starts)));
    var holder = start(d, "write");
    Await.latch("the first writer to take the only permit", entered);
    var instant = Duration.ofNanos(1);

    var late = start(d, call("write"), new CancellationToken(), instant, NEVER);

    assertEquals(ToolResult.timedOut("write", instant), late.ending("the late dispatch").result());
    release.countDown();
    assertEquals(ToolResult.success("written"), holder.ending("the first dispatch").result());
    awaitIdle(d);
    assertEquals(1, starts.get(), "the late writer's tool never ran");
    schedulerFree.countDown();
  }

  @Test
  void aQueuedCallWhoseSessionIsCancelledNeverStartsWhileAnEarlierCallbackBlocks() {
    var session = new CancellationToken();
    var callbackEntered = new CountDownLatch(1);
    var callbackRelease = new CountDownLatch(1);
    session.onCancel(
        () -> {
          callbackEntered.countDown();
          awaitThroughInterrupts(callbackRelease);
        });
    var starts = new AtomicInteger();
    var d =
        dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, stuckWriter(starts)));
    var holder = start(d, "write", session, NEVER);
    Await.latch("the first writer to take the only permit", entered);
    var queued = start(d, "write", session, NEVER);
    Await.until(
        "the second writer to queue for the permit",
        () -> d.queuedDispatches(ToolCategory.WRITE) == 1);
    Thread.ofVirtual().start(() -> session.cancel("session closed"));
    Await.latch("the session's first callback to block", callbackEntered);

    release.countDown();

    var thrown = queued.ending("the queued dispatch").thrown();
    assertInstanceOf(CancellationException.class, thrown);
    assertEquals("session closed", thrown.getMessage());
    assertEquals(1, starts.get(), "the queued writer's tool never ran");
    callbackRelease.countDown();
    holder.ending("the first dispatch");
    awaitIdle(d);
  }

  @Test
  void queuedCallsAreAdmittedInTheOrderTheyArrived() {
    var order = new CopyOnWriteArrayList<Object>();
    ToolExecutor recording =
        (args, ctx) -> {
          order.add(args.get("n"));
          entered.countDown();
          awaitThroughInterrupts(release);
          return ToolResult.success("done");
        };
    var d =
        dispatcher(
            new ConcurrencyLimits(1, 1, 1, 1), binding("slow", ToolCategory.READ, recording));
    var token = new CancellationToken();
    var dispatches = new ArrayList<Running>();
    dispatches.add(start(d, new ToolCall("c", "slow", Map.of("n", 0)), token, NEVER, NEVER));
    Await.latch("the first call to take the only permit", entered);
    for (var n = 1; n <= 2; n++) {
      var queued = n;
      dispatches.add(start(d, new ToolCall("c", "slow", Map.of("n", n)), token, NEVER, NEVER));
      Await.until("call " + n + " to queue", () -> d.queuedDispatches(ToolCategory.READ) == queued);
    }

    release.countDown();
    for (var dispatch : dispatches) {
      assertEquals(ToolResult.success("done"), dispatch.ending("a queued dispatch").result());
    }
    assertEquals(List.of(0, 1, 2), order);
  }

  @Test
  void aCallWhoseDeadlinePassesWhileItQueuesIsTimedOutAndNeverStarts() {
    var starts = new CountDownLatch(2);
    ToolExecutor counted =
        (args, ctx) -> {
          starts.countDown();
          entered.countDown();
          awaitThroughInterrupts(release);
          return ToolResult.success("done");
        };
    var d =
        dispatcher(new ConcurrencyLimits(1, 1, 1, 1), binding("slow", ToolCategory.READ, counted));
    var holder = start(d, "slow");
    Await.latch("the first call to take the only permit", entered);

    var queued = start(d, "slow");
    Await.until(
        "the second call to queue for the permit",
        () -> d.queuedDispatches(ToolCategory.READ) == 1);
    scheduler.fireLatest();

    assertEquals(ToolResult.timedOut("slow", NEVER), queued.ending("the queued dispatch").result());
    release.countDown();
    assertEquals(ToolResult.success("done"), holder.ending("the first dispatch").result());
    awaitIdle(d);
    assertEquals(1, starts.getCount(), "only the first call's tool ran");
  }

  @Test
  void aResultArrivingAfterTheTimeoutDoesNotReplaceIt() {
    var cancelled = new CountDownLatch(1);
    ToolExecutor late =
        (args, ctx) -> {
          ctx.cancellation().onCancel(cancelled::countDown);
          entered.countDown();
          awaitThroughInterrupts(release);
          return ToolResult.success("late");
        };
    var d = dispatcher(binding("late", ToolCategory.READ, late));

    var running = start(d, "late");
    Await.latch("the tool to start", entered);
    scheduler.fireLatest();
    Await.latch("the call to be timed out", cancelled);
    release.countDown();

    assertEquals(ToolResult.timedOut("late", NEVER), running.ending("the dispatch").result());
  }

  @Test
  void anErrorThrownAfterTheCallWasAnsweredIsLogged() {
    var sentinel = new AssertionError("late failure");
    ToolExecutor failsLate =
        (args, ctx) -> {
          entered.countDown();
          awaitThroughInterrupts(release);
          throw sentinel;
        };
    var d = dispatcher(binding("late", ToolCategory.READ, failsLate));
    var records = new CopyOnWriteArrayList<LogRecord>();

    try (var capture = LogCapture.of(DispatchedCall.class, records)) {
      var running = start(d, "late", new CancellationToken(), SHORT);
      Await.latch("the tool to start", entered);
      scheduler.fireLatest();
      assertEquals(ToolResult.unknown("late", NEVER), running.ending("the dispatch").result());
      release.countDown();
      awaitIdle(d);
    }

    var logged = records.getFirst();
    assertEquals(Level.SEVERE, logged.getLevel());
    assertEquals("tool 'late' threw after its call was answered", logged.getMessage());
    assertSame(sentinel, logged.getThrown());
  }

  // ── cancellation and interruption ─────────────────────────────────────────

  @Test
  void cancellingTheSessionWhileTheCallQueuesEndsItAndNeverStartsTheTool() {
    var starts = new CountDownLatch(2);
    ToolExecutor counted =
        (args, ctx) -> {
          starts.countDown();
          entered.countDown();
          awaitThroughInterrupts(release);
          return ToolResult.success("done");
        };
    var d =
        dispatcher(new ConcurrencyLimits(1, 1, 1, 1), binding("slow", ToolCategory.READ, counted));
    var holder = start(d, "slow");
    Await.latch("the first call to take the only permit", entered);
    var session = new CancellationToken();

    var queued = start(d, "slow", session, NEVER);
    Await.until(
        "the second call to queue for the permit",
        () -> d.queuedDispatches(ToolCategory.READ) == 1);
    session.cancel("user-stop");

    var thrown = queued.ending("the cancelled dispatch").thrown();
    assertInstanceOf(CancellationException.class, thrown);
    assertEquals("user-stop", thrown.getMessage());
    release.countDown();
    assertEquals(ToolResult.success("done"), holder.ending("the first dispatch").result());
    awaitIdle(d);
    assertEquals(1, starts.getCount(), "the cancelled call's tool never ran");
    assertEquals(0, session.activeCallbackCountForTests(), "no link to the session is left");
  }

  @Test
  void cancellingTheSessionWhileTheToolRunsEndsTheCallAndInterruptsTheTool() {
    var interrupted = new CountDownLatch(1);
    var d = dispatcher(binding("blocking", ToolCategory.READ, interruptible(interrupted)));
    var session = new CancellationToken();

    var running = start(d, "blocking", session, NEVER);
    Await.latch("the tool to start", entered);
    session.cancel("session closed");

    var thrown = running.ending("the cancelled dispatch").thrown();
    assertInstanceOf(CancellationException.class, thrown);
    assertEquals("session closed", thrown.getMessage());
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
          entered.countDown();
          while (secondInterrupt.getCount() > 0) {
            try {
              release.await();
              return ToolResult.success("released");
            } catch (InterruptedException e) {
              (firstInterrupt.getCount() > 0 ? firstInterrupt : secondInterrupt).countDown();
            }
          }
          return ToolResult.failure("stopped");
        };
    var d =
        dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8), binding("write", ToolCategory.WRITE, needsTwo));
    var session = new CancellationToken();
    var running = start(d, "write", session, SHORT);
    Await.latch("the tool to start", entered);
    scheduler.fireLatest();
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
    var d = dispatcher(binding("blocking", ToolCategory.READ, interruptible(interrupted)));

    var running = start(d, "blocking");
    Await.latch("the tool to start", entered);
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
    ToolExecutor stuck =
        (args, ctx) -> {
          ctx.cancellation().onCancel(cancelled::countDown);
          entered.countDown();
          awaitThroughInterrupts(release);
          return ToolResult.success("late");
        };
    var d = dispatcher(binding("stuck", ToolCategory.READ, stuck));

    var running = start(d, "stuck");
    Await.latch("the tool to start", entered);
    scheduler.fireLatest();
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
    ToolExecutor stuck =
        (args, ctx) -> {
          ctx.cancellation().onCancel(cancelled::countDown);
          entered.countDown();
          awaitThroughInterrupts(release);
          return ToolResult.success("late");
        };
    var d = dispatcher(binding("stuck", ToolCategory.READ, stuck));
    var session = new CancellationToken();

    var running = start(d, "stuck", session, NEVER);
    Await.latch("the tool to start", entered);
    scheduler.fireLatest();
    Await.latch("the call to be timed out", cancelled);
    session.cancel("session closed");

    var thrown = running.ending("the cancelled dispatch").thrown();
    assertInstanceOf(CancellationException.class, thrown);
    assertEquals("session closed", thrown.getMessage());
  }

  @Test
  void cancellingTheSessionWhileAStuckWriterRunsAdmitsNoOtherWriterUntilItEnds() {
    var starts = new AtomicInteger();
    var d = dispatcher(binding("write", ToolCategory.WRITE, stuckWriter(starts)));
    var session = new CancellationToken();
    var first = start(d, "write", session, NEVER);
    Await.latch("the first writer to start", entered);
    session.cancel("user-stop");
    assertInstanceOf(CancellationException.class, first.ending("the first dispatch").thrown());

    assertTheNextWriterWaitsForRelease(d, starts);
  }

  @Test
  void interruptingTheCallerWhileAStuckWriterRunsAdmitsNoOtherWriterUntilItEnds() {
    var starts = new AtomicInteger();
    var d = dispatcher(binding("write", ToolCategory.WRITE, stuckWriter(starts)));
    var first = start(d, "write");
    Await.latch("the first writer to start", entered);
    first.thread().interrupt();
    assertInstanceOf(CancellationException.class, first.ending("the first dispatch").thrown());

    assertTheNextWriterWaitsForRelease(d, starts);
  }

  // ── the call's cancellation callbacks ─────────────────────────────────────

  @Test
  void aBlockingCancellationCallbackDoesNotHoldBackTheInterrupt() {
    var interrupted = new CountDownLatch(1);
    var d = dispatcher(binding("blocking", ToolCategory.READ, blockingCallback(interrupted)));

    var running = start(d, "blocking");
    Await.latch("the tool to start", entered);
    scheduler.fireLatest();

    Await.latch("the tool to be interrupted", interrupted);
    release.countDown();
    assertEquals(ToolResult.timedOut("blocking", NEVER), running.ending("the dispatch").result());
  }

  @Test
  void aBlockingCancellationCallbackDoesNotHoldUpTheSessionsCancellation() {
    var d =
        dispatcher(binding("blocking", ToolCategory.READ, blockingCallback(new CountDownLatch(1))));
    var session = new CancellationToken();
    var running = start(d, "blocking", session, NEVER);
    Await.latch("the tool to start", entered);

    var cancelling = new FutureTask<>(() -> session.cancel("session closed"));
    Thread.ofVirtual().start(cancelling);

    assertTrue(Await.value("the session's cancellation to return", cancelling));
    assertInstanceOf(CancellationException.class, running.ending("the dispatch").thrown());
  }

  @Test
  void aCallWhoseCancellationCallbackStillRunsIsUnknownAndAdmitsNoOtherWriter() {
    var starts = new AtomicInteger();
    ToolExecutor writer =
        (args, ctx) -> {
          ctx.cancellation().onCancel(() -> awaitThroughInterrupts(release));
          starts.incrementAndGet();
          entered.countDown();
          try {
            release.await();
            return ToolResult.success("written");
          } catch (InterruptedException e) {
            return ToolResult.failure("interrupted");
          }
        };
    var d = dispatcher(binding("write", ToolCategory.WRITE, writer));
    var first = start(d, "write", new CancellationToken(), SHORT);
    Await.latch("the first writer to start", entered);
    scheduler.fireLatest();
    assertEquals(ToolResult.unknown("write", NEVER), first.ending("the first dispatch").result());

    assertTheNextWriterWaitsForRelease(d, starts);
  }

  @Test
  void aCancellationCallbackErrorIsLoggedAsItselfAndTheToolIsStillInterrupted() {
    var sentinel = new AssertionError("broken callback");
    var interrupted = new CountDownLatch(1);
    ToolExecutor throwing =
        (args, ctx) -> {
          ctx.cancellation()
              .onCancel(
                  () -> {
                    throw sentinel;
                  });
          return interruptible(interrupted).execute(args, ctx);
        };
    var d = dispatcher(binding("throwing", ToolCategory.READ, throwing));
    var session = new CancellationToken();
    var records = new CopyOnWriteArrayList<LogRecord>();

    try (var capture = LogCapture.of(DispatchedCall.class, records)) {
      var running = start(d, "throwing", session, NEVER);
      Await.latch("the tool to start", entered);
      session.cancel("session closed");
      Await.latch("the tool to be interrupted", interrupted);
      assertInstanceOf(CancellationException.class, running.ending("the dispatch").thrown());
      awaitIdle(d);
    }

    var logged = records.getFirst();
    assertEquals(Level.SEVERE, logged.getLevel());
    assertEquals("a cancellation callback of tool 'throwing' threw", logged.getMessage());
    assertSame(sentinel, logged.getThrown());
  }

  // ── nothing left behind ───────────────────────────────────────────────────

  @Test
  void repeatedCallsLeaveNoPermitRegistrationWorkerOrDeadlineBehind() {
    var d =
        dispatcher(
            new ConcurrencyLimits(4, 2, 1, 8),
            binding("read", ToolCategory.READ, (args, ctx) -> ToolResult.success("r")),
            binding("write", ToolCategory.WRITE, (args, ctx) -> ToolResult.failure("w")),
            binding(
                "exec",
                ToolCategory.EXECUTION,
                (args, ctx) -> {
                  throw new IllegalStateException("x");
                }));
    var session = new CancellationToken();
    var names = List.of("read", "write", "exec");
    var tasks = new ArrayList<FutureTask<ToolResult>>();
    IntStream.range(0, 300)
        .forEach(
            i -> {
              var task =
                  new FutureTask<>(() -> d.dispatch(call(names.get(i % 3)), session, NEVER, NEVER));
              Thread.ofVirtual().start(task);
              tasks.add(task);
            });

    for (var task : tasks) {
      Await.value("a repeated dispatch", task);
    }
    awaitIdle(d);

    assertEquals(4, d.availableToolCallPermits());
    assertEquals(2, d.availableFileWritePermits());
    assertEquals(1, d.availableExecutionPermits());
    assertEquals(0, session.activeCallbackCountForTests(), "no link to the session is left");
    assertTrue(scheduler.getQueue().isEmpty(), "no deadline is left on the scheduler");
  }
}
