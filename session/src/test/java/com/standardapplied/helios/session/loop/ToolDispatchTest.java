/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static com.standardapplied.helios.session.loop.DispatchFixture.CTX;
import static com.standardapplied.helios.session.loop.DispatchFixture.NEVER;
import static com.standardapplied.helios.session.loop.DispatchFixture.SHORT;
import static com.standardapplied.helios.session.loop.DispatchFixture.awaitIdle;
import static com.standardapplied.helios.session.loop.DispatchFixture.binding;
import static com.standardapplied.helios.session.loop.DispatchFixture.call;
import static com.standardapplied.helios.session.loop.DispatchFixture.start;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.RedactedLogs;
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
 * checks, results, timeouts, the refusal while a call that did not stop runs, and what a call
 * leaves behind. Each deadline lies beyond the hang guard and fires only when a test calls {@link
 * ToolDispatch#expireLiveCalls()}; a stop grace under test is a millisecond against a tool that
 * cannot stop, and otherwise beyond the hang guard.
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
            () -> new ToolDispatch(null, ToolRegistry.empty(), fixture.ending));
    assertEquals("sessionContext must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullRegistry() {
    var ex =
        assertThrows(NullPointerException.class, () -> new ToolDispatch(CTX, null, fixture.ending));
    assertEquals("registry must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullEnding() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new ToolDispatch(CTX, ToolRegistry.empty(), null));
    assertEquals("ending must not be null", ex.getMessage());
  }

  @Test
  void accessorsReturnTheConstructorValues() {
    var registry = ToolRegistry.empty();
    var d = new ToolDispatch(CTX, registry, fixture.ending);
    assertSame(CTX, d.sessionContext());
    assertSame(registry, d.registry());
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
    d.expireLiveCalls();

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
            binding("blocking", ToolCategory.READ, fixture.interruptible(interrupted)),
            fixture.reader());

    var running = start(d, "blocking");
    Await.latch("the tool to start", fixture.entered);
    d.expireLiveCalls();

    assertEquals(ToolResult.timedOut("blocking", NEVER), running.ending("the dispatch").result());
    Await.latch("the tool to be interrupted", interrupted);
    assertEquals(ToolResult.success("read"), start(d, "read").ending("the next dispatch").result());
  }

  @ParameterizedTest
  @EnumSource(ToolCategory.class)
  void anUnknownCallOfAnyCategoryRefusesEveryCallUntilItEnds(ToolCategory category) {
    var d =
        fixture.dispatcher(
            binding("stuck", category, fixture.stuck(new AtomicInteger())), fixture.reader());
    var stuck = start(d, "stuck", new CancellationToken(), SHORT);
    Await.latch("the stuck tool to start", fixture.entered);
    d.expireLiveCalls();
    assertEquals(ToolResult.unknown("stuck", NEVER), stuck.ending("the stuck dispatch").result());
    assertEquals(List.of(call("stuck")), d.awaitLiveCalls(SHORT), "the worker is still running");

    fixture.assertTheNextCallIsRefusedUntilTheStuckOneEnds(d);
  }

  @Test
  void aWriterTimedOutBeforeItStartedRefusesNoLaterCall() {
    var starts = new AtomicInteger();
    var d =
        fixture.dispatcher(
            binding("write", ToolCategory.WRITE, fixture.stuck(starts)), fixture.reader());
    var instant = Duration.ofNanos(1);

    var late = start(d, call("write"), new CancellationToken(), instant, NEVER);

    assertEquals(ToolResult.timedOut("write", instant), late.ending("the late dispatch").result());
    assertEquals(0, starts.get(), "the late writer's tool never ran");
    assertEquals(ToolResult.success("read"), start(d, "read").ending("the next dispatch").result());
  }

  @Test
  void aResultArrivingAfterTheTimeoutDoesNotReplaceIt() {
    var cancelled = new CountDownLatch(1);
    var late = fixture.stuckSignalling(cancelled);
    var d = fixture.dispatcher(binding("late", ToolCategory.READ, late));

    var running = start(d, "late");
    Await.latch("the tool to start", fixture.entered);
    d.expireLiveCalls();
    Await.latch("the call to be timed out", cancelled);
    fixture.release.countDown();

    assertEquals(ToolResult.timedOut("late", NEVER), running.ending("the dispatch").result());
  }

  @Test
  void aThrowableThatIsNoErrorThrownAfterTheCallWasAnsweredIsLoggedRedacted() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", "sk-test-0123456789abcdef");
    var late = new Throwable("late failure", new IllegalStateException("sk-test-0123456789abcdef"));
    var d = fixture.dispatcher(secrets, binding("late", ToolCategory.READ, failsLate(late)));
    var records = new CopyOnWriteArrayList<LogRecord>();

    try (var capture = LogCapture.of(DispatchedCall.class, records)) {
      answerUnknownThenFail(d);
    }

    var logged = records.getFirst();
    assertEquals(Level.SEVERE, logged.getLevel());
    assertEquals("tool 'late' threw after its call was answered", logged.getMessage());
    RedactedLogs.assertCopyOf(
        late,
        logged.getThrown(),
        List.of(
            "java.lang.Throwable: late failure",
            "java.lang.IllegalStateException: <redacted:API_KEY>"));
    assertFalse(RedactedLogs.text(logged).contains("sk-test-0123456789abcdef"));
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
    d.expireLiveCalls();
    assertEquals(ToolResult.unknown("late", NEVER), running.ending("the dispatch").result());
    fixture.release.countDown();
    awaitIdle(d);
  }

  // ── nothing left behind ───────────────────────────────────────────────────

  @Test
  void repeatedCallsLeaveNoRegistrationOrWorkerBehind() {
    var d =
        fixture.dispatcher(
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

    assertEquals(0, session.activeCallbackCountForTests(), "no link to the session is left");
  }
}
