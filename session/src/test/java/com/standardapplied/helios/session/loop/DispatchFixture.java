/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What {@link ToolDispatchTest} and {@link ToolDispatchCancellationTest} share: deadlines beyond
 * the hang guard that a test fires with {@link ToolDispatch#expireLiveCalls()}, tools that block on
 * {@link #release} until {@link #close} opens it, and dispatches run on their own virtual threads.
 */
final class DispatchFixture implements AutoCloseable {

  static final SessionContext CTX = SessionContext.forTesting("dispatch-test");
  static final Duration NEVER = Duration.ofMinutes(10);
  static final Duration SHORT = Duration.ofMillis(1);

  final SessionEnding ending = new SessionEnding("dispatch", new CancellationToken());
  final CountDownLatch release = new CountDownLatch(1);
  final CountDownLatch entered = new CountDownLatch(1);
  final CountDownLatch callbackRelease = new CountDownLatch(1);
  final AtomicInteger reads = new AtomicInteger();

  /** What a dispatch on its own thread ended with, and whether that thread was left interrupted. */
  record Ending(ToolResult result, RuntimeException thrown, boolean interrupted) {}

  /** A dispatch running on its own virtual thread. */
  record Running(FutureTask<Ending> task, Thread thread) {

    Ending ending(String description) {
      return Await.value(description, task);
    }
  }

  @Override
  public void close() {
    release.countDown();
    callbackRelease.countDown();
  }

  ToolDispatch dispatcher(ToolBinding... bindings) {
    return new ToolDispatch(CTX, new ToolRegistry(List.of(bindings)), ending);
  }

  /**
   * A dispatcher like {@link #dispatcher(ToolBinding...)} in a session redacting {@code secrets}.
   */
  ToolDispatch dispatcher(SecretRegistry secrets, ToolBinding... bindings) {
    var session =
        new SessionContext(CTX.sessionId(), new CancellationToken(), CTX.clock(), secrets);
    return new ToolDispatch(session, new ToolRegistry(List.of(bindings)), ending);
  }

  static ToolBinding binding(String name, ToolCategory category, ToolExecutor executor) {
    var tool =
        Tool.newBuilder().withName(name).withDescription(name).withExecutor(executor).build();
    return ToolBinding.newBuilder(tool).withCategory(category).build();
  }

  static ToolCall call(String name) {
    return new ToolCall("c", name, Map.of());
  }

  static Running start(
      ToolDispatch d,
      ToolCall call,
      CancellationToken token,
      Duration timeout,
      Duration stopGrace) {
    var task =
        new FutureTask<>(
            () -> {
              try {
                return new Ending(
                    d.dispatch(call, token, timeout, stopGrace, key -> false), null, false);
              } catch (RuntimeException e) {
                return new Ending(null, e, Thread.currentThread().isInterrupted());
              }
            });
    return new Running(task, Thread.ofVirtual().start(task));
  }

  static Running start(ToolDispatch d, String name, CancellationToken token, Duration stopGrace) {
    return start(d, call(name), token, NEVER, stopGrace);
  }

  static Running start(ToolDispatch d, String name) {
    return start(d, name, new CancellationToken(), NEVER);
  }

  /** Signals {@link #entered}, then waits for {@link #release}, ending early on interruption. */
  ToolExecutor interruptible(CountDownLatch interrupted) {
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

  /**
   * Counts {@code cancelled} down when its token is cancelled, signals {@link #entered}, then waits
   * for {@link #release} through every interruption.
   */
  ToolExecutor stuckSignalling(CountDownLatch cancelled) {
    return (args, ctx) -> {
      ctx.cancellation().onCancel(cancelled::countDown);
      entered.countDown();
      Uninterruptible.await(release);
      return ToolResult.success("late");
    };
  }

  /** Throws {@code thrown}, checked or not, as code compiled elsewhere may. */
  @SuppressWarnings("unchecked")
  static <T extends Throwable> ToolResult sneakyThrow(Throwable thrown) throws T {
    throw (T) thrown;
  }

  /**
   * Counts its starts, signals {@link #entered}, then waits for {@link #release} through every
   * interruption.
   */
  ToolExecutor stuck(AtomicInteger starts) {
    return (args, ctx) -> {
      starts.incrementAndGet();
      entered.countDown();
      Uninterruptible.await(release);
      return ToolResult.success("done");
    };
  }

  /** A {@code read} tool that counts its runs in {@link #reads} and answers {@code read}. */
  ToolBinding reader() {
    return binding(
        "read",
        ToolCategory.READ,
        (args, ctx) -> {
          reads.incrementAndGet();
          return ToolResult.success("read");
        });
  }

  /**
   * The next call of {@code d}'s {@link #reader() read} tool is refused at once because the call to
   * {@code stuck} did not stop, and its tool never starts; once {@link #release} opens and that
   * call's work ends, the same call runs.
   */
  void assertTheNextCallIsRefusedUntilTheStuckOneEnds(ToolDispatch d) {
    assertEquals(
        new ToolResult(
            new ToolOutcome.Rejected(
                "ToolDispatch", "tool 'stuck' did not stop; no tool runs until it ends"),
            "blocked by ToolDispatch: tool 'stuck' did not stop; no tool runs until it ends",
            List.of()),
        start(d, "read").ending("the refused dispatch").result());
    assertEquals(0, reads.get(), "the refused tool never started");

    release.countDown();
    awaitIdle(d);
    assertEquals(ToolResult.success("read"), start(d, "read").ending("the next dispatch").result());
    assertEquals(1, reads.get());
  }

  /**
   * Registers a cancellation callback that waits for {@link #release}, signals {@link #entered},
   * then waits for {@link #release}, ending early on interruption.
   */
  ToolExecutor blockingCallback(CountDownLatch interrupted) {
    return (args, ctx) -> {
      ctx.cancellation().onCancel(() -> Uninterruptible.await(release));
      return interruptible(interrupted).execute(args, ctx);
    };
  }

  /**
   * Registers on {@code session} a first callback that signals {@code entered}, then waits for
   * {@code release} through every interruption.
   */
  void blockFirstCallback(CancellationToken session, CountDownLatch entered) {
    session.onCancel(
        () -> {
          entered.countDown();
          Uninterruptible.await(callbackRelease);
        });
  }

  static void assertCancelled(String reason, Running running) {
    var thrown = running.ending("the cancelled dispatch").thrown();
    assertInstanceOf(CancellationException.class, thrown);
    assertEquals(reason, thrown.getMessage());
  }

  static void awaitIdle(ToolDispatch d) {
    Await.until("every worker to end", () -> d.awaitLiveCalls(Await.passedDeadline()).isEmpty());
  }
}
