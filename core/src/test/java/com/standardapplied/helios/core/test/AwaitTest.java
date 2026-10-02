/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Each operation returns once its event has occurred and fails, naming what was awaited, when the
 * event never comes. The failing cases pass a one-millisecond guard and an event that cannot occur,
 * so real time passes but the outcome cannot vary.
 */
class AwaitTest {

  private static final Duration SHORT_GUARD = Duration.ofMillis(1);

  @Test
  void theHangGuardIsSixtySeconds() {
    assertEquals(Duration.ofSeconds(60), Await.HANG_GUARD);
  }

  @Test
  void latchReturnsOnceAnotherThreadCountsItDown() {
    var latch = new CountDownLatch(1);
    Thread.ofVirtual().start(latch::countDown);

    Await.latch("the count-down", latch);

    assertEquals(0, latch.getCount());
  }

  @Test
  void latchFailsAtTheGuard() {
    assertGuardExpiry(
        "the count-down", () -> Await.latch("the count-down", new CountDownLatch(1), SHORT_GUARD));
  }

  @Test
  void valueReturnsWhatTheFutureCompletesWith() {
    var future = new CompletableFuture<String>();
    Thread.ofVirtual().start(() -> future.complete("done"));

    assertEquals("done", Await.value("the answer", future));
  }

  @Test
  void valueFailsWithTheCauseWhenTheFutureFails() {
    var cause = new IOException("boom");

    var error =
        assertThrows(
            AssertionError.class,
            () -> Await.value("the answer", CompletableFuture.failedFuture(cause)));

    assertEquals("the answer failed instead of completing", error.getMessage());
    assertSame(cause, error.getCause());
  }

  @Test
  void valueFailsWhenTheFutureIsCancelled() {
    var future = new CompletableFuture<String>();
    future.cancel(true);

    var error = assertThrows(AssertionError.class, () -> Await.value("the answer", future));

    assertEquals("the answer was cancelled instead of completing", error.getMessage());
    assertInstanceOf(CancellationException.class, error.getCause());
  }

  @Test
  void valueFailsAtTheGuard() {
    assertGuardExpiry(
        "the answer", () -> Await.value("the answer", new CompletableFuture<>(), SHORT_GUARD));
  }

  @Test
  void failureReturnsTheCauseTheFutureFailsWith() {
    var cause = new IOException("boom");
    var future = new CompletableFuture<String>();
    Thread.ofVirtual().start(() -> future.completeExceptionally(cause));

    assertSame(cause, Await.failure("the rejection", future));
  }

  @Test
  void failureReturnsTheCancellationOfACancelledFuture() {
    var future = new CompletableFuture<String>();
    future.cancel(true);

    assertInstanceOf(CancellationException.class, Await.failure("the rejection", future));
  }

  @Test
  void failureFailsWhenTheFutureCompletesWithAValue() {
    var error =
        assertThrows(
            AssertionError.class,
            () -> Await.failure("the rejection", CompletableFuture.completedFuture("fine")));

    assertEquals("the rejection completed with fine instead of failing", error.getMessage());
  }

  @Test
  void failureFailsAtTheGuard() {
    assertGuardExpiry(
        "the rejection",
        () -> Await.failure("the rejection", new CompletableFuture<>(), SHORT_GUARD));
  }

  @Test
  void nextTakesTheElementAnotherThreadOffers() {
    var queue = new LinkedBlockingQueue<String>();
    Thread.ofVirtual().start(() -> queue.add("first"));

    assertEquals("first", Await.next("the first element", queue));
  }

  @Test
  void nextFailsAtTheGuard() {
    assertGuardExpiry(
        "the first element",
        () -> Await.next("the first element", new LinkedBlockingQueue<>(), SHORT_GUARD));
  }

  @Test
  void terminationReturnsOnceTheThreadHasExited() {
    var thread = Thread.ofVirtual().start(() -> {});

    Await.termination("the worker", thread);

    assertEquals(Thread.State.TERMINATED, thread.getState());
  }

  @Test
  void terminationOfAThreadFailsAtTheGuard() {
    var release = new CountDownLatch(1);
    var thread = Thread.ofVirtual().start(() -> awaitUninterruptibly(release));

    assertGuardExpiry("the worker", () -> Await.termination("the worker", thread, SHORT_GUARD));

    release.countDown();
    Await.termination("the released worker", thread);
  }

  @Test
  void terminationReturnsOnceTheExecutorHasShutDown() {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    executor.submit(() -> {});
    executor.shutdown();

    Await.termination("the executor", executor);

    assertTrue(executor.isTerminated());
  }

  @Test
  void terminationOfAnExecutorFailsAtTheGuard() {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertGuardExpiry(
          "the executor", () -> Await.termination("the executor", executor, SHORT_GUARD));
    }
  }

  @Test
  void terminationReturnsOnceTheProcessHasExited() throws IOException {
    var process = new ProcessBuilder("true").start();

    Await.termination("the child process", process);

    assertEquals(0, process.exitValue());
  }

  @Test
  void terminationOfAProcessFailsAtTheGuard() throws IOException {
    var process = new ProcessBuilder("sleep", "600").start();
    try {
      assertGuardExpiry(
          "the child process", () -> Await.termination("the child process", process, SHORT_GUARD));
    } finally {
      process.destroyForcibly();
    }
    Await.termination("the killed child process", process);
  }

  @Test
  void untilReturnsOnceTheConditionHolds() {
    var polls = new AtomicInteger();

    Await.until("the third poll", () -> polls.incrementAndGet() == 3);

    assertEquals(3, polls.get());
  }

  @Test
  void untilFailsAtTheGuard() {
    assertGuardExpiry(
        "the impossible", () -> Await.until("the impossible", () -> false, SHORT_GUARD));
  }

  @Test
  void anInterruptWhileWaitingFailsAndStaysSet() {
    Thread.currentThread().interrupt();

    var error =
        assertThrows(
            AssertionError.class, () -> Await.latch("the count-down", new CountDownLatch(1)));

    assertTrue(Thread.interrupted());
    assertEquals("Interrupted while waiting for the count-down", error.getMessage());
    assertInstanceOf(InterruptedException.class, error.getCause());
  }

  private static void assertGuardExpiry(String description, Executable wait) {
    var error = assertThrows(AssertionError.class, wait);
    assertEquals("Still waiting for " + description + " after PT0.001S", error.getMessage());
    assertInstanceOf(TimeoutException.class, error.getCause());
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }
}
