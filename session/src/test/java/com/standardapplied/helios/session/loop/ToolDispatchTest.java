/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.ConcurrencyLimits;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  void writeCategoryAcquiresFileWritePermits() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var registry =
        new ToolRegistry(
            List.of(binding(blockingTool("write", entered, release), ToolCategory.WRITE)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(8, 1, 1, 8));
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      exec.submit(
          () ->
              d.dispatch(
                  new ToolCall("c", "write", Map.of()), new CancellationToken(), DEFAULT_TIMEOUT));
      assertTrue(entered.await(2, TimeUnit.SECONDS), "tool should be entered");
      assertEquals(0, d.availableFileWritePermits(), "WRITE took the file-write permit");
      assertEquals(8, d.availableToolCallPermits(), "tool-call pool unchanged");
      assertEquals(1, d.availableExecutionPermits(), "execution pool unchanged");
      release.countDown();
    }
  }

  @Test
  void executionCategoryAcquiresExecutionPermits() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var registry =
        new ToolRegistry(
            List.of(binding(blockingTool("exec", entered, release), ToolCategory.EXECUTION)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(8, 4, 1, 8));
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      exec.submit(
          () ->
              d.dispatch(
                  new ToolCall("c", "exec", Map.of()), new CancellationToken(), DEFAULT_TIMEOUT));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals(0, d.availableExecutionPermits(), "EXECUTION took the execution permit");
      assertEquals(8, d.availableToolCallPermits(), "tool-call pool unchanged");
      assertEquals(4, d.availableFileWritePermits(), "file-write pool unchanged");
      release.countDown();
    }
  }

  @Test
  void readCategoryAcquiresGeneralToolCallPermits() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var registry =
        new ToolRegistry(
            List.of(binding(blockingTool("read", entered, release), ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(2, 4, 1, 8));
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      exec.submit(
          () ->
              d.dispatch(
                  new ToolCall("c", "read", Map.of()), new CancellationToken(), DEFAULT_TIMEOUT));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals(1, d.availableToolCallPermits(), "READ took a general permit");
      assertEquals(4, d.availableFileWritePermits(), "file-write pool unchanged");
      assertEquals(1, d.availableExecutionPermits(), "execution pool unchanged");
      release.countDown();
    }
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
  void interruptedAcquireThrowsCancellationException() throws Exception {
    // Saturate the single-permit pool with a blocking tool; then a second dispatch waits on
    // acquire. Interrupt the waiter's thread → ToolDispatch surfaces CancellationException.
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var registry =
        new ToolRegistry(
            List.of(binding(blockingTool("slow", entered, release), ToolCategory.READ)));
    var d = new ToolDispatch(CTX, registry, new ConcurrencyLimits(1, 1, 1, 1));
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      // First dispatch takes the permit and blocks.
      exec.submit(
          () ->
              d.dispatch(
                  new ToolCall("c1", "slow", Map.of()), new CancellationToken(), DEFAULT_TIMEOUT));
      assertTrue(entered.await(2, TimeUnit.SECONDS), "first dispatch should acquire");

      // Second dispatch will wait for the permit; we interrupt its thread.
      var waiter = new AtomicReference<Throwable>();
      var ready = new CountDownLatch(1);
      var workerRef = new AtomicReference<Thread>();
      Thread.ofVirtual()
          .start(
              () -> {
                workerRef.set(Thread.currentThread());
                ready.countDown();
                try {
                  d.dispatch(
                      new ToolCall("c2", "slow", Map.of()),
                      new CancellationToken(),
                      DEFAULT_TIMEOUT);
                } catch (Throwable t) {
                  waiter.set(t);
                }
              });
      assertTrue(ready.await(2, TimeUnit.SECONDS));
      Thread.sleep(50); // let the second dispatch start blocking on acquire
      workerRef.get().interrupt();

      // Give the interrupted dispatch a moment to surface the exception.
      for (int i = 0; i < 200 && waiter.get() == null; i++) {
        Thread.sleep(10);
      }
      assertInstanceOf(CancellationException.class, waiter.get());
      assertTrue(waiter.get().getMessage().contains("interrupted while acquiring permit"));

      release.countDown();
    }
  }

  @Test
  void capExactlyBoundsConcurrentDispatch() throws Exception {
    var release = new CountDownLatch(1);
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
    var limits = new ConcurrencyLimits(2, 4, 1, 8);
    var d = new ToolDispatch(CTX, registry, limits);

    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 6; i++) {
        exec.submit(
            () ->
                d.dispatch(
                    new ToolCall("c", "slow", Map.of()), new CancellationToken(), DEFAULT_TIMEOUT));
      }
      // Allow some scheduling churn; only 2 can be inFlight at once.
      Thread.sleep(150);
      assertTrue(peak.get() <= 2, "peak in-flight must respect cap; observed=" + peak.get());
      release.countDown();
    }
  }
}
