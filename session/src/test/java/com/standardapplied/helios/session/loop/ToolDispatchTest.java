/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static com.standardapplied.helios.session.loop.DispatchFixture.CTX;
import static com.standardapplied.helios.session.loop.DispatchFixture.NEVER;
import static com.standardapplied.helios.session.loop.DispatchFixture.SHORT;
import static com.standardapplied.helios.session.loop.DispatchFixture.assertCancelled;
import static com.standardapplied.helios.session.loop.DispatchFixture.awaitIdle;
import static com.standardapplied.helios.session.loop.DispatchFixture.binding;
import static com.standardapplied.helios.session.loop.DispatchFixture.call;
import static com.standardapplied.helios.session.loop.DispatchFixture.start;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.test.LogCapture;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ToolDispatch}'s contract for a call that runs to an answer: construction and argument
 * checks, results, per-category permits, timeouts and what a call leaves behind. The fixture's
 * {@link ManualScheduler} fires each deadline only when a test does; a stop grace under test is a
 * millisecond against a tool that cannot stop, and otherwise beyond the hang guard.
 */
final class ToolDispatchTest {

  private final DispatchFixture fixture = new DispatchFixture();

  @AfterEach
  void closeTheFixture() {
    fixture.close();
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullSessionContext() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolDispatch(
                    null,
                    ToolRegistry.empty(),
                    ConcurrencyLimits.defaults(),
                    fixture.scheduler,
                    fixture.ending));
    assertEquals("sessionContext must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullRegistry() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolDispatch(
                    CTX, null, ConcurrencyLimits.defaults(), fixture.scheduler, fixture.ending));
    assertEquals("registry must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullLimits() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolDispatch(
                    CTX, ToolRegistry.empty(), null, fixture.scheduler, fixture.ending));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullScheduler() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolDispatch(
                    CTX, ToolRegistry.empty(), ConcurrencyLimits.defaults(), null, fixture.ending));
    assertEquals("scheduler must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullEnding() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolDispatch(
                    CTX,
                    ToolRegistry.empty(),
                    ConcurrencyLimits.defaults(),
                    fixture.scheduler,
                    null));
    assertEquals("ending must not be null", ex.getMessage());
  }

  @Test
  void accessorsReturnTheConstructorValues() {
    var registry = ToolRegistry.empty();
    var limits = new ConcurrencyLimits(8, 2, 1, 32);
    var d = new ToolDispatch(CTX, registry, limits, fixture.scheduler, fixture.ending);
    assertSame(CTX, d.sessionContext());
    assertSame(registry, d.registry());
    assertSame(limits, d.limits());
  }

  @Test
  void permitCountsTrackLimits() {
    var d = fixture.dispatcher(new ConcurrencyLimits(8, 2, 1, 32));
    assertEquals(8, d.availableToolCallPermits());
    assertEquals(2, d.availableFileWritePermits());
    assertEquals(1, d.availableExecutionPermits());
  }

  // ── dispatch validation ───────────────────────────────────────────────────

  @Test
  void dispatchRejectsNullCall() {
    var d = fixture.dispatcher();
    var token = new CancellationToken();
    var ex =
        assertThrows(
            NullPointerException.class, () -> d.dispatch(null, token, NEVER, NEVER, key -> false));
    assertEquals("call must not be null", ex.getMessage());
  }

  @Test
  void dispatchRejectsNullCancellation() {
    var d = fixture.dispatcher();
    var c = call("read");
    var ex =
        assertThrows(
            NullPointerException.class, () -> d.dispatch(c, null, NEVER, NEVER, key -> false));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void dispatchRejectsNullTimeoutAndStopGrace() {
    var d = fixture.dispatcher();
    var c = call("read");
    var token = new CancellationToken();
    var timeout =
        assertThrows(
            NullPointerException.class, () -> d.dispatch(c, token, null, NEVER, key -> false));
    var grace =
        assertThrows(
            NullPointerException.class, () -> d.dispatch(c, token, NEVER, null, key -> false));
    assertEquals("timeout must not be null", timeout.getMessage());
    assertEquals("stopGrace must not be null", grace.getMessage());
  }

  @Test
  void dispatchRejectsANullWithheldBeforeLookingTheCallUp() {
    var d = fixture.dispatcher();
    var c = call("nope");
    var token = new CancellationToken();
    var ex =
        assertThrows(NullPointerException.class, () -> d.dispatch(c, token, NEVER, NEVER, null));
    assertEquals("withheld must not be null", ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void dispatchRejectsANonPositiveTimeout(long millis) {
    var d = fixture.dispatcher();
    var c = call("read");
    var token = new CancellationToken();
    var timeout = Duration.ofMillis(millis);
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> d.dispatch(c, token, timeout, NEVER, key -> false));
    assertEquals("timeout must be strictly positive, got " + timeout, ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void dispatchRejectsANonPositiveStopGrace(long millis) {
    var d = fixture.dispatcher();
    var c = call("read");
    var token = new CancellationToken();
    var grace = Duration.ofMillis(millis);
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> d.dispatch(c, token, NEVER, grace, key -> false));
    assertEquals("stopGrace must be strictly positive, got " + grace, ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void awaitingLiveCallsRejectsANonPositiveGrace(long millis) {
    var d = fixture.dispatcher();
    var grace = Duration.ofMillis(millis);

    var ex = assertThrows(IllegalArgumentException.class, () -> d.awaitLiveCalls(grace));

    assertEquals("grace must be strictly positive, got " + grace, ex.getMessage());
  }

  @Test
  void unknownToolReturnsNotFound() {
    var result =
        fixture
            .dispatcher()
            .dispatch(call("nope"), new CancellationToken(), NEVER, NEVER, key -> false);
    assertEquals(ToolResult.notFound("nope"), result);
  }

  @Test
  void aCancelledSessionDispatchesNothing() {
    var runs = new AtomicInteger();
    var d =
        fixture.dispatcher(
            binding(
                "echo",
                ToolCategory.READ,
                (args, ctx) -> ToolResult.success("run " + runs.incrementAndGet())));
    var token = new CancellationToken();
    token.cancel("user-stop");
    var c = call("echo");

    var ex =
        assertThrows(
            CancellationException.class, () -> d.dispatch(c, token, NEVER, NEVER, key -> false));

    assertEquals("user-stop", ex.getMessage());
    assertEquals(0, runs.get());
    assertEquals(List.of(), d.awaitLiveCalls(SHORT));
  }

  // ── a call that completes ─────────────────────────────────────────────────

  @Test
  void dispatchReturnsTheToolsResult() {
    var d =
        fixture.dispatcher(
            binding(
                "echo",
                ToolCategory.READ,
                (args, ctx) -> ToolResult.success("echoed: " + args.get("v"))));
    var result =
        d.dispatch(
            new ToolCall("c", "echo", Map.of("v", "hello")),
            new CancellationToken(),
            NEVER,
            NEVER,
            key -> false);
    assertEquals(ToolResult.success("echoed: hello"), result);
  }

  @Test
  void theToolWithholdsWhatTheDispatchedPredicateWithholds() {
    var d =
        fixture.dispatcher(
            binding(
                "walk",
                ToolCategory.SEARCH,
                (args, ctx) ->
                    ToolResult.success(
                        Stream.of("./docs", "./secrets")
                            .filter(key -> !ctx.withholds(key))
                            .toList()
                            .toString())));
    var result =
        d.dispatch(call("walk"), new CancellationToken(), NEVER, NEVER, "./secrets"::equals);
    assertEquals(ToolResult.success("[./docs]"), result);
  }

  @Test
  void dispatchReturnsAReportedFailure() {
    var d =
        fixture.dispatcher(
            binding("bad", ToolCategory.READ, (args, ctx) -> ToolResult.failure("intentional")));
    var result = d.dispatch(call("bad"), new CancellationToken(), NEVER, NEVER, key -> false);
    assertEquals(ToolResult.failure("intentional"), result);
  }

  @Test
  void theToolSeesTheSessionItsOwnTokenAndItsTimeout() {
    var seen = new AtomicReference<ToolContext>();
    var d =
        fixture.dispatcher(
            binding(
                "look",
                ToolCategory.READ,
                (args, ctx) -> {
                  seen.set(ctx);
                  return ToolResult.success("seen");
                }));
    var token = new CancellationToken();

    d.dispatch(call("look"), token, NEVER, NEVER, key -> false);

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
        fixture.dispatcher(
            binding(
                "boom",
                ToolCategory.READ,
                (args, ctx) -> {
                  throw sentinel;
                }));
    var c = call("boom");
    var token = new CancellationToken();

    var thrown =
        assertThrows(AssertionError.class, () -> d.dispatch(c, token, NEVER, NEVER, key -> false));

    assertSame(sentinel, thrown);
  }

  @Test
  void aToolWhoseFailureCannotBeReportedIsThrewWithoutWaitingForTheDeadline() {
    var d =
        fixture.dispatcher(
            binding(
                "garbled",
                ToolCategory.READ,
                (args, ctx) -> {
                  throw new IllegalStateException() {
                    @Override
                    public String getMessage() {
                      throw new UnsupportedOperationException("message unavailable");
                    }
                  };
                }));

    var ending = start(d, "garbled").ending("the dispatch");

    assertEquals(
        new ToolResult(
            new ToolOutcome.Threw("java.lang.UnsupportedOperationException"),
            "tool 'garbled' threw: message unavailable",
            List.of()),
        ending.result());
  }

  // ── per-category permits ──────────────────────────────────────────────────

  private void assertHeldPermits(
      ToolCategory category, int toolCalls, int fileWrites, int executions) {
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(8, 4, 2, 8),
            binding("t", category, fixture.stuck(new AtomicInteger())));

    var running = start(d, "t");
    Await.latch("the tool to start", fixture.entered);

    assertEquals(toolCalls, d.availableToolCallPermits(), "general pool");
    assertEquals(fileWrites, d.availableFileWritePermits(), "file-write pool");
    assertEquals(executions, d.availableExecutionPermits(), "execution pool");
    fixture.release.countDown();
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
    var d =
        fixture.dispatcher(
            binding("echo", ToolCategory.READ, (args, ctx) -> ToolResult.success("x")));
    d.dispatch(call("echo"), new CancellationToken(), NEVER, NEVER, key -> false);
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
          Uninterruptible.await(fixture.release);
          inFlight.decrementAndGet();
          return ToolResult.success("ok");
        };
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(2, 4, 1, 8), binding("slow", ToolCategory.READ, slow));

    var dispatches = Stream.generate(() -> start(d, "slow")).limit(6).toList();
    Await.latch("two tools to start", started);
    Await.until(
        "the other four calls to queue for a permit",
        () -> d.permitsFor(ToolCategory.READ).queued() == 4);

    assertEquals(2, inFlight.get(), "exactly the cap is running while the rest wait");
    fixture.release.countDown();
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
          fixture.entered.countDown();
          Uninterruptible.await(cancelled);
          stopped.countDown();
          return ToolResult.success("stopped");
        };
    var d = fixture.dispatcher(binding("coop", ToolCategory.READ, tokenOnly));
    var session = new CancellationToken();

    var running = start(d, "coop", session, NEVER);
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();

    assertEquals(ToolResult.timedOut("coop", NEVER), running.ending("the dispatch").result());
    Await.latch("the tool to stop", stopped);
    assertFalse(session.isCancelled(), "the call's timeout does not cancel the session");
    assertFalse(CTX.cancellation().isCancelled(), "nor the session context's token");
  }

  @Test
  void aToolThatHonoursInterruptionIsInterruptedAtTheDeadlineAndIsTimedOut() {
    var interrupted = new CountDownLatch(1);
    var d =
        fixture.dispatcher(
            binding("blocking", ToolCategory.READ, fixture.interruptible(interrupted)));

    var running = start(d, "blocking");
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();

    assertEquals(ToolResult.timedOut("blocking", NEVER), running.ending("the dispatch").result());
    Await.latch("the tool to be interrupted", interrupted);
  }

  @Test
  void aToolStillRunningAfterTheStopGraceIsUnknownAndKeepsItsPermit() {
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, fixture.stuck(new AtomicInteger())));

    var running = start(d, "write", new CancellationToken(), SHORT);
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();

    assertEquals(ToolResult.unknown("write", NEVER), running.ending("the dispatch").result());
    assertEquals(List.of(call("write")), d.awaitLiveCalls(SHORT), "the worker is still running");
    assertEquals(0, d.availableFileWritePermits(), "and still holds the write permit");
  }

  @Test
  void aSecondWriterUnderCapacityOneQueuesUntilTheFirstEnds() {
    var starts = new AtomicInteger();
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, fixture.stuck(starts)));
    var first = start(d, "write");
    Await.latch("the first writer to start", fixture.entered);

    fixture.assertTheNextWriterWaitsForRelease(d, starts);
    assertEquals(ToolResult.success("done"), first.ending("the first dispatch").result());
  }

  @Test
  void aStuckWriterRefusesEveryCallUntilItEndsThoughPermitsAreFree() {
    var starts = new AtomicInteger();
    var d = fixture.dispatcher(binding("write", ToolCategory.WRITE, fixture.stuck(starts)));
    var first = start(d, "write", new CancellationToken(), SHORT);
    Await.latch("the first writer to start", fixture.entered);
    fixture.scheduler.fireLatest();
    assertEquals(ToolResult.unknown("write", NEVER), first.ending("the first dispatch").result());

    fixture.assertTheNextWriterIsRefusedUntilTheStuckOneEnds(d, starts);
  }

  @ParameterizedTest
  @EnumSource(
      value = ToolCategory.class,
      names = {"WRITE", "EXECUTION", "NETWORK", "DELEGATION", "CONTROL"})
  void anUnresolvedCallWithSideEffectsRefusesACallOfAnyCategory(ToolCategory category) {
    var runs = new AtomicInteger();
    var d =
        fixture.dispatcher(
            binding("stuck", category, fixture.stuck(new AtomicInteger())),
            binding(
                "read",
                ToolCategory.READ,
                (args, ctx) -> ToolResult.success("read " + runs.incrementAndGet())));
    var stuck = start(d, "stuck", new CancellationToken(), SHORT);
    Await.latch("the stuck tool to start", fixture.entered);
    fixture.scheduler.fireLatest();
    assertEquals(ToolResult.unknown("stuck", NEVER), stuck.ending("the stuck dispatch").result());

    var refused = start(d, "read").ending("the refused dispatch").result();

    assertEquals(
        ToolResult.rejected(
            "ToolDispatch", "tool 'stuck' did not stop; no tool runs until it ends"),
        refused);
    assertEquals(0, runs.get(), "the refused tool never started");
  }

  @ParameterizedTest
  @EnumSource(
      value = ToolCategory.class,
      names = {"READ", "SEARCH"})
  void anUnresolvedCallWithoutSideEffectsRefusesNothing(ToolCategory category) {
    var d =
        fixture.dispatcher(
            binding("stuck", category, fixture.stuck(new AtomicInteger())),
            binding("write", ToolCategory.WRITE, (args, ctx) -> ToolResult.success("written")));
    var stuck = start(d, "stuck", new CancellationToken(), SHORT);
    Await.latch("the stuck tool to start", fixture.entered);
    fixture.scheduler.fireLatest();
    assertEquals(ToolResult.unknown("stuck", NEVER), stuck.ending("the stuck dispatch").result());

    var next = start(d, "write").ending("the next dispatch").result();

    assertEquals(ToolResult.success("written"), next);
    assertEquals(
        ConcurrencyLimits.defaults().maxConcurrentToolCalls() - 1,
        d.availableToolCallPermits(),
        "the stuck tool still holds its permit");
  }

  @Test
  void aWriterTimedOutBeforeItStartedRefusesNoLaterCall() {
    var starts = new AtomicInteger();
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, fixture.stuck(starts)));
    var holder = start(d, "write");
    Await.latch("the first writer to take the only permit", fixture.entered);
    var queued = start(d, "write");
    Await.until(
        "the second writer to queue for the permit",
        () -> d.permitsFor(ToolCategory.WRITE).queued() == 1);
    fixture.scheduler.fireLatest();
    assertEquals(
        ToolResult.timedOut("write", NEVER), queued.ending("the queued dispatch").result());
    fixture.release.countDown();
    assertEquals(ToolResult.success("done"), holder.ending("the first dispatch").result());
    awaitIdle(d);

    var next = start(d, "write").ending("the next dispatch").result();

    assertEquals(ToolResult.success("done"), next);
  }

  @Test
  void aCallQueuedForCapacityIsRefusedOnceAQuarantineStarts() {
    var starts = new AtomicInteger();
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, fixture.stuck(starts)));
    var first = start(d, "write");
    Await.latch("the first writer to start", fixture.entered);
    var queued = start(d, "write");
    Await.until(
        "the second writer to queue for the permit",
        () -> d.permitsFor(ToolCategory.WRITE).queued() == 1);

    first.thread().interrupt();

    assertEquals(
        ToolResult.rejected(
            "ToolDispatch", "tool 'write' did not stop; no tool runs until it ends"),
        queued.ending("the queued dispatch").result());
    assertCancelled("interrupted waiting for tool write", first);
    assertEquals(1, starts.get(), "the queued writer's tool never started");
  }

  @Test
  void aCallWhoseDeadlinePassesWhileTheSchedulerIsBusyTimesOutWithoutStartingTheTool() {
    fixture.scheduler.execute(() -> Uninterruptible.await(fixture.schedulerFree));
    var starts = new AtomicInteger();
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, fixture.stuck(starts)));
    var holder = start(d, "write");
    Await.latch("the first writer to take the only permit", fixture.entered);
    var instant = Duration.ofNanos(1);

    var late = start(d, call("write"), new CancellationToken(), instant, NEVER);

    assertEquals(ToolResult.timedOut("write", instant), late.ending("the late dispatch").result());
    fixture.release.countDown();
    assertEquals(ToolResult.success("done"), holder.ending("the first dispatch").result());
    awaitIdle(d);
    assertEquals(1, starts.get(), "the late writer's tool never ran");
  }

  @Test
  void aQueuedCallWhoseSessionIsCancelledNeverStartsWhileAnEarlierCallbackBlocks() {
    var session = new CancellationToken();
    var callbackEntered = new CountDownLatch(1);
    fixture.blockFirstCallback(session, callbackEntered);
    var starts = new AtomicInteger();
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(8, 1, 1, 8),
            binding("write", ToolCategory.WRITE, fixture.stuck(starts)));
    var holder = start(d, "write", session, NEVER);
    Await.latch("the first writer to take the only permit", fixture.entered);
    var queued = start(d, "write", session, NEVER);
    Await.until(
        "the second writer to queue for the permit",
        () -> d.permitsFor(ToolCategory.WRITE).queued() == 1);
    Thread.ofVirtual().start(() -> session.cancel("session closed"));
    Await.latch("the session's first callback to block", callbackEntered);

    fixture.release.countDown();

    assertCancelled("session closed", queued);
    holder.ending("the first dispatch");
    awaitIdle(d);
    assertEquals(1, starts.get(), "the queued writer's tool never ran");
  }

  @Test
  void queuedCallsAreAdmittedInTheOrderTheyArrived() {
    var order = new CopyOnWriteArrayList<Object>();
    ToolExecutor recording =
        (args, ctx) -> {
          order.add(args.get("n"));
          fixture.entered.countDown();
          Uninterruptible.await(fixture.release);
          return ToolResult.success("done");
        };
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(1, 1, 1, 1), binding("slow", ToolCategory.READ, recording));
    var token = new CancellationToken();
    var dispatches = new ArrayList<DispatchFixture.Running>();
    dispatches.add(start(d, new ToolCall("c", "slow", Map.of("n", 0)), token, NEVER, NEVER));
    Await.latch("the first call to take the only permit", fixture.entered);
    for (var n = 1; n <= 2; n++) {
      var queued = n;
      dispatches.add(start(d, new ToolCall("c", "slow", Map.of("n", n)), token, NEVER, NEVER));
      Await.until(
          "call " + n + " to queue", () -> d.permitsFor(ToolCategory.READ).queued() == queued);
    }

    fixture.release.countDown();
    for (var dispatch : dispatches) {
      assertEquals(ToolResult.success("done"), dispatch.ending("a queued dispatch").result());
    }
    assertEquals(List.of(0, 1, 2), order);
  }

  @Test
  void aCallWhoseDeadlinePassesWhileItQueuesIsTimedOutAndNeverStarts() {
    var starts = new AtomicInteger();
    var d =
        fixture.dispatcher(
            new ConcurrencyLimits(1, 1, 1, 1),
            binding("slow", ToolCategory.READ, fixture.stuck(starts)));
    var holder = start(d, "slow");
    Await.latch("the first call to take the only permit", fixture.entered);

    var queued = start(d, "slow");
    Await.until(
        "the second call to queue for the permit",
        () -> d.permitsFor(ToolCategory.READ).queued() == 1);
    fixture.scheduler.fireLatest();

    assertEquals(ToolResult.timedOut("slow", NEVER), queued.ending("the queued dispatch").result());
    fixture.release.countDown();
    assertEquals(ToolResult.success("done"), holder.ending("the first dispatch").result());
    awaitIdle(d);
    assertEquals(1, starts.get(), "only the first call's tool ran");
  }

  @Test
  void aResultArrivingAfterTheTimeoutDoesNotReplaceIt() {
    var cancelled = new CountDownLatch(1);
    var late = fixture.stuckSignalling(cancelled);
    var d = fixture.dispatcher(binding("late", ToolCategory.READ, late));

    var running = start(d, "late");
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();
    Await.latch("the call to be timed out", cancelled);
    fixture.release.countDown();

    assertEquals(ToolResult.timedOut("late", NEVER), running.ending("the dispatch").result());
  }

  @Test
  void aThrowableThatIsNoErrorThrownAfterTheCallWasAnsweredIsLogged() {
    var late = new Throwable("late failure");
    var d = fixture.dispatcher(binding("late", ToolCategory.READ, failsLate(late)));
    var records = new CopyOnWriteArrayList<LogRecord>();

    try (var capture = LogCapture.of(DispatchedCall.class, records)) {
      answerUnknownThenFail(d);
    }

    var logged = records.getFirst();
    assertEquals(Level.SEVERE, logged.getLevel());
    assertEquals("tool 'late' threw after its call was answered", logged.getMessage());
    assertSame(late, logged.getThrown());
  }

  @Test
  void anErrorThrownAfterTheCallWasAnsweredEndsTheSessionAndIsNotLoggedByTheCall() {
    var sentinel = new AssertionError("late failure");
    var d = fixture.dispatcher(binding("late", ToolCategory.READ, failsLate(sentinel)));
    var records = new CopyOnWriteArrayList<LogRecord>();

    try (var capture = LogCapture.of(DispatchedCall.class, records)) {
      answerUnknownThenFail(d);
    }

    assertSame(sentinel, fixture.ending.seal().orElseThrow());
    assertEquals(List.of(), records);
  }

  private ToolExecutor failsLate(Throwable thrown) {
    return (args, ctx) -> {
      fixture.entered.countDown();
      Uninterruptible.await(fixture.release);
      return DispatchFixture.sneakyThrow(thrown);
    };
  }

  /** Answer a call to {@code late} unknown at its deadline, then let its tool throw and end. */
  private void answerUnknownThenFail(ToolDispatch d) {
    var running = start(d, "late", new CancellationToken(), SHORT);
    Await.latch("the tool to start", fixture.entered);
    fixture.scheduler.fireLatest();
    assertEquals(ToolResult.unknown("late", NEVER), running.ending("the dispatch").result());
    fixture.release.countDown();
    awaitIdle(d);
  }

  // ── nothing left behind ───────────────────────────────────────────────────

  @Test
  void repeatedCallsLeaveNoPermitRegistrationWorkerOrDeadlineBehind() {
    var d =
        fixture.dispatcher(
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
                  new FutureTask<>(
                      () ->
                          d.dispatch(call(names.get(i % 3)), session, NEVER, NEVER, key -> false));
              Thread.ofVirtual().start(task);
              tasks.add(task);
            });

    var expected =
        Map.of(
            "read", ToolResult.success("r"),
            "write", ToolResult.failure("w"),
            "exec", ToolResult.threw("exec", new IllegalStateException("x")));
    for (var i = 0; i < tasks.size(); i++) {
      assertEquals(
          expected.get(names.get(i % 3)), Await.value("a repeated dispatch", tasks.get(i)));
    }
    awaitIdle(d);

    assertEquals(0, d.permitsFor(ToolCategory.READ).queued(), "no dispatch is left queued");
    assertEquals(0, d.permitsFor(ToolCategory.WRITE).queued());
    assertEquals(0, d.permitsFor(ToolCategory.EXECUTION).queued());
    assertEquals(4, d.availableToolCallPermits());
    assertEquals(2, d.availableFileWritePermits());
    assertEquals(1, d.availableExecutionPermits());
    assertEquals(0, session.activeCallbackCountForTests(), "no link to the session is left");
    assertTrue(fixture.scheduler.getQueue().isEmpty(), "no deadline is left on the scheduler");
  }
}
