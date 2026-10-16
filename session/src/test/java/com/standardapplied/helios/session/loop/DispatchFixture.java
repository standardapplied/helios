/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What {@link ToolDispatchTest} and {@link ToolDispatchCancellationTest} share: a {@link
 * ManualScheduler} on which every deadline is armed beyond the hang guard, tools that block on
 * {@link #release} until {@link #close} opens it, and dispatches run on their own virtual threads.
 */
final class DispatchFixture implements AutoCloseable {

  static final SessionContext CTX = SessionContext.forTesting("dispatch-test");
  static final Duration NEVER = Duration.ofMinutes(10);
  static final Duration SHORT = Duration.ofMillis(1);

  final ManualScheduler scheduler = new ManualScheduler();
  final CountDownLatch release = new CountDownLatch(1);
  final CountDownLatch entered = new CountDownLatch(1);
  final CountDownLatch callbackRelease = new CountDownLatch(1);
  final CountDownLatch schedulerFree = new CountDownLatch(1);

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
    schedulerFree.countDown();
    scheduler.shutdownNow();
  }

  ToolDispatch dispatcher(ConcurrencyLimits limits, ToolBinding... bindings) {
    return new ToolDispatch(CTX, new ToolRegistry(List.of(bindings)), limits, scheduler);
  }

  ToolDispatch dispatcher(ToolBinding... bindings) {
    return dispatcher(ConcurrencyLimits.defaults(), bindings);
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
                return new Ending(d.dispatch(call, token, timeout, stopGrace), null, false);
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
      awaitThroughInterrupts(release);
      return ToolResult.success("late");
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
  ToolExecutor stuck(AtomicInteger starts) {
    return (args, ctx) -> {
      starts.incrementAndGet();
      entered.countDown();
      awaitThroughInterrupts(release);
      return ToolResult.success("done");
    };
  }

  /**
   * The next call of {@code d}'s {@code write} tool queues and starts only once {@link #release}
   * opens.
   */
  void assertTheNextWriterWaitsForRelease(ToolDispatch d, AtomicInteger starts) {
    var next = start(d, "write");
    Await.until(
        "the next writer to queue for the write permit",
        () -> d.queuedDispatches(ToolCategory.WRITE) == 1);
    assertEquals(1, starts.get(), "the next writer has not started");

    release.countDown();
    assertEquals(ToolResult.success("done"), next.ending("the next dispatch").result());
    assertEquals(2, starts.get());
  }

  /**
   * The next call of {@code d}'s {@code write} tool is refused at once because the earlier writer
   * did not stop, and its tool never starts; once {@link #release} opens and that writer ends, the
   * next one runs.
   */
  void assertTheNextWriterIsRefusedUntilTheStuckOneEnds(ToolDispatch d, AtomicInteger starts) {
    assertEquals(
        new ToolResult(
            new ToolOutcome.Rejected(
                "ToolDispatch", "tool 'write' did not stop; no tool runs until it ends"),
            "blocked by ToolDispatch: tool 'write' did not stop; no tool runs until it ends",
            List.of()),
        start(d, "write").ending("the refused dispatch").result());
    assertEquals(1, starts.get(), "the refused writer's tool never started");

    release.countDown();
    awaitIdle(d);
    assertEquals(
        ToolResult.success("done"), start(d, "write").ending("the next dispatch").result());
    assertEquals(2, starts.get());
  }

  /**
   * Registers a cancellation callback that waits for {@link #release}, signals {@link #entered},
   * then waits for {@link #release}, ending early on interruption.
   */
  ToolExecutor blockingCallback(CountDownLatch interrupted) {
    return (args, ctx) -> {
      ctx.cancellation().onCancel(() -> awaitThroughInterrupts(release));
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
          awaitThroughInterrupts(callbackRelease);
        });
  }

  static void assertCancelled(String reason, Running running) {
    var thrown = running.ending("the cancelled dispatch").thrown();
    assertInstanceOf(CancellationException.class, thrown);
    assertEquals(reason, thrown.getMessage());
  }

  static void awaitIdle(ToolDispatch d) {
    Await.until("every worker to end", () -> d.liveCalls() == 0);
  }
}
