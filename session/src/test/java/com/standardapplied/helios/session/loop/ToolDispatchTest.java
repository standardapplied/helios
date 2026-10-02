/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ToolDispatchTest {

  private static final SessionContext CTX = SessionContext.forTesting("dispatch-test");
  private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

  private static Tool echoTool(String name) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("echo")
        .withExecutor((args, ctx) -> ToolResult.success("echoed: " + args.get("v")))
        .build();
  }

  private static Tool failingTool(String name, String error) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("fails")
        .withExecutor((args, ctx) -> ToolResult.failure(error))
        .build();
  }

  private static Tool blockingTool(String name, CountDownLatch entered, CountDownLatch release) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("blocks")
        .withExecutor(
            (args, ctx) -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return ToolResult.success("done");
            })
        .build();
  }

  private static ToolBinding binding(Tool tool, ToolCategory cat) {
    return ToolBinding.newBuilder(tool).withCategory(cat).build();
  }

  private static FutureTask<ToolResult> dispatchTask(ToolDispatch d, String tool) {
    return new FutureTask<>(
        () ->
            d.dispatch(
                new ToolCall("c", tool, Map.of()), new CancellationToken(), DEFAULT_TIMEOUT));
  }

  private static FutureTask<ToolResult> startDispatch(ToolDispatch d, String tool) {
    var task = dispatchTask(d, tool);
    Thread.ofVirtual().start(task);
    return task;
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullSessionContext() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ToolDispatch(null, ToolRegistry.empty(), ConcurrencyLimits.defaults()));
    assertEquals("sessionContext must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullRegistry() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ToolDispatch(CTX, null, ConcurrencyLimits.defaults()));
    assertEquals("registry must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullLimits() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new ToolDispatch(CTX, ToolRegistry.empty(), null));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void registryAccessorReturnsConstructorValue() {
    var registry = ToolRegistry.empty();
    var d = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    assertSame(registry, d.registry());
  }

  @Test
  void limitsAccessorReturnsConstructorValue() {
    var limits = new ConcurrencyLimits(8, 2, 1, 32);
    var d = new ToolDispatch(CTX, ToolRegistry.empty(), limits);
    assertSame(limits, d.limits());
  }

  @Test
  void permitCountsTrackLimits() {
    var limits = new ConcurrencyLimits(8, 2, 1, 32);
    var d = new ToolDispatch(CTX, ToolRegistry.empty(), limits);
    assertEquals(8, d.availableToolCallPermits());
    assertEquals(2, d.availableFileWritePermits());
    assertEquals(1, d.availableExecutionPermits());
  }

  // ── dispatch validation ───────────────────────────────────────────────────

  @Test
  void dispatchRejectsNullCall() {
    var d = new ToolDispatch(CTX, ToolRegistry.empty(), ConcurrencyLimits.defaults());
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> d.dispatch(null, new CancellationToken(), DEFAULT_TIMEOUT));
    assertEquals("call must not be null", ex.getMessage());
  }

  @Test
  void dispatchRejectsNullCancellation() {
    var d = new ToolDispatch(CTX, ToolRegistry.empty(), ConcurrencyLimits.defaults());
    var call = new ToolCall("c", "read", Map.of());
    var ex =
        assertThrows(NullPointerException.class, () -> d.dispatch(call, null, DEFAULT_TIMEOUT));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void unknownToolReturnsFailure() {
    var d = new ToolDispatch(CTX, ToolRegistry.empty(), ConcurrencyLimits.defaults());
    var call = new ToolCall("c", "nope", Map.of());
    var result = d.dispatch(call, new CancellationToken(), DEFAULT_TIMEOUT);
    assertFalse(result.success());
    assertTrue(result.output().contains("tool not found"));
    assertTrue(result.output().contains("nope"));
  }

  @Test
  void preCancelledTokenThrows() {
    var registry = new ToolRegistry(List.of(binding(echoTool("echo"), ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var token = new CancellationToken();
    token.cancel("user-stop");
    var call = new ToolCall("c", "echo", Map.of("v", "hi"));
    var ex =
        assertThrows(CancellationException.class, () -> d.dispatch(call, token, DEFAULT_TIMEOUT));
    assertEquals("user-stop", ex.getMessage());
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void dispatchInvokesRegisteredTool() {
    var registry = new ToolRegistry(List.of(binding(echoTool("echo"), ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var result =
        d.dispatch(
            new ToolCall("c", "echo", Map.of("v", "hello")),
            new CancellationToken(),
            DEFAULT_TIMEOUT);
    assertTrue(result.success());
    assertEquals("echoed: hello", result.output());
  }

  @Test
  void dispatchSurfacesToolFailures() {
    var registry =
        new ToolRegistry(List.of(binding(failingTool("bad", "intentional"), ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var result =
        d.dispatch(new ToolCall("c", "bad", Map.of()), new CancellationToken(), DEFAULT_TIMEOUT);
    assertFalse(result.success());
    assertEquals("intentional", result.output());
  }

  // ── per-category semaphore selection ──────────────────────────────────────

  @Test
  void writeCategoryAcquiresFileWritePermits() {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var registry =
        new ToolRegistry(
            List.of(binding(blockingTool("write", entered, release), ToolCategory.WRITE)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(8, 1, 1, 8));

    var dispatched = startDispatch(d, "write");
    Await.latch("the write tool to start", entered);

    assertEquals(0, d.availableFileWritePermits(), "WRITE took the file-write permit");
    assertEquals(8, d.availableToolCallPermits(), "tool-call pool unchanged");
    assertEquals(1, d.availableExecutionPermits(), "execution pool unchanged");
    release.countDown();
    assertEquals("done", Await.value("the write dispatch to return", dispatched).output());
  }

  @Test
  void executionCategoryAcquiresExecutionPermits() {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var registry =
        new ToolRegistry(
            List.of(binding(blockingTool("exec", entered, release), ToolCategory.EXECUTION)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(8, 4, 1, 8));

    var dispatched = startDispatch(d, "exec");
    Await.latch("the execution tool to start", entered);

    assertEquals(0, d.availableExecutionPermits(), "EXECUTION took the execution permit");
    assertEquals(8, d.availableToolCallPermits(), "tool-call pool unchanged");
    assertEquals(4, d.availableFileWritePermits(), "file-write pool unchanged");
    release.countDown();
    assertEquals("done", Await.value("the execution dispatch to return", dispatched).output());
  }

  @Test
  void readCategoryAcquiresGeneralToolCallPermits() {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var registry =
        new ToolRegistry(
            List.of(binding(blockingTool("read", entered, release), ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(2, 4, 1, 8));

    var dispatched = startDispatch(d, "read");
    Await.latch("the read tool to start", entered);

    assertEquals(1, d.availableToolCallPermits(), "READ took a general permit");
    assertEquals(4, d.availableFileWritePermits(), "file-write pool unchanged");
    assertEquals(1, d.availableExecutionPermits(), "execution pool unchanged");
    release.countDown();
    assertEquals("done", Await.value("the read dispatch to return", dispatched).output());
  }

  @Test
  void semaphoreReleasesAfterDispatch() {
    var registry = new ToolRegistry(List.of(binding(echoTool("echo"), ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    d.dispatch(
        new ToolCall("c", "echo", Map.of("v", "x")), new CancellationToken(), DEFAULT_TIMEOUT);
    assertEquals(
        ConcurrencyLimits.defaults().maxConcurrentToolCalls(), d.availableToolCallPermits());
  }

  // ── concurrency cap enforcement ──────────────────────────────────────────

  @Test
  void interruptedAcquireThrowsCancellationException() {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var registry =
        new ToolRegistry(
            List.of(binding(blockingTool("slow", entered, release), ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(1, 1, 1, 1));
    var holder = startDispatch(d, "slow");
    Await.latch("the first dispatch to take the only permit", entered);

    var waiter = dispatchTask(d, "slow");
    var waiterThread = Thread.ofVirtual().start(waiter);
    Await.until(
        "the second dispatch to queue for the permit", () -> d.queuedToolCallDispatches() == 1);
    waiterThread.interrupt();

    var thrown =
        assertInstanceOf(
            CancellationException.class, Await.failure("the interrupted dispatch", waiter));
    assertEquals("interrupted while acquiring permit for slow", thrown.getMessage());
    release.countDown();
    assertEquals("done", Await.value("the first dispatch to return", holder).output());
    assertEquals(1, d.availableToolCallPermits(), "the only permit is back in the pool");
  }

  @Test
  void capExactlyBoundsConcurrentDispatch() {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(2);
    var inFlight = new AtomicInteger();
    var peak = new AtomicInteger();
    Tool blocking =
        Tool.newBuilder()
            .withName("slow")
            .withDescription("blocks")
            .withExecutor(
                (args, ctx) -> {
                  var current = inFlight.incrementAndGet();
                  peak.updateAndGet(p -> Math.max(p, current));
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  inFlight.decrementAndGet();
                  return ToolResult.success("ok");
                })
            .build();
    var registry = new ToolRegistry(List.of(binding(blocking, ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(2, 4, 1, 8));

    var dispatches = Stream.generate(() -> startDispatch(d, "slow")).limit(6).toList();
    Await.latch("two tools to start", entered);
    Await.until(
        "the other four dispatches to queue for a permit", () -> d.queuedToolCallDispatches() == 4);

    assertEquals(2, inFlight.get(), "exactly the cap is running while the rest wait");
    assertEquals(0, d.availableToolCallPermits(), "both permits are held");
    release.countDown();
    for (var dispatch : dispatches) {
      assertEquals("ok", Await.value("a capped dispatch to return", dispatch).output());
    }
    assertEquals(2, peak.get(), "peak in-flight equals the cap");
    assertEquals(0, inFlight.get());
    assertEquals(2, d.availableToolCallPermits(), "every permit is back in the pool");
  }
}
