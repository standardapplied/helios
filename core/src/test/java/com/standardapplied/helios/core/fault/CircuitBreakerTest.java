/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Time never passes on its own here: the breaker reads {@link #now}, which a test advances by hand,
 * so no assertion depends on how fast the machine is. A test can also run code at the exact moment
 * the breaker next reads the clock, which places a second caller inside a transition without
 * relying on thread scheduling.
 */
class CircuitBreakerTest {

  private static final Duration HALF_OPEN_AFTER = Duration.ofSeconds(30);

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final AtomicReference<Callable<?>> beforeNextClockRead =
      new AtomicReference<>(() -> null);

  @Test
  void closedStateAllowsCalls() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();
    var calls = new AtomicInteger(0);

    var result =
        cb.execute(
            () -> {
              calls.incrementAndGet();
              return "success";
            });

    assertEquals("success", result);
    assertEquals(1, calls.get());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void tripOpenAfterFailureThreshold() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).withClock(this::readClock).build();

    for (int i = 0; i < 3; i++) {
      assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    }

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertEquals(3, cb.failureCount());
  }

  @Test
  void openStateRejectsCalls() {
    var cb = trippedBreaker(1);

    assertThrows(CircuitBreakerOpenException.class, () -> cb.execute(() -> "should not run"));
  }

  @Test
  void successResetsFailureCount() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    assertEquals(2, cb.failureCount());

    cb.execute(() -> "success");

    assertEquals(0, cb.failureCount());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void transitionToHalfOpenAfterDelay() {
    var cb = trippedBreaker(1);

    advance(HALF_OPEN_AFTER);
    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    advance(Duration.ofNanos(1));
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
  }

  @Test
  void halfOpenSuccessClosesCircuit() throws Exception {
    var cb = halfOpenBreaker(1);

    cb.execute(() -> "success");

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(0, cb.failureCount());
  }

  @Test
  void halfOpenFailureOpensCircuitAndRestartsTheDelay() {
    var cb = halfOpenBreaker(1);

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail again")));
    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    advance(HALF_OPEN_AFTER);
    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    advance(Duration.ofNanos(1));
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
  }

  @Test
  void multipleSuccessesRequiredToClose() throws Exception {
    var cb = halfOpenBreaker(3);

    cb.execute(() -> "success 1");
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.execute(() -> "success 2");
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.execute(() -> "success 3");
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void reset() throws Exception {
    var cb = trippedBreaker(1);

    cb.reset();

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(0, cb.failureCount());

    var result = cb.execute(() -> "works again");
    assertEquals("works again", result);
  }

  @Test
  void executeRunnableSuccess() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();
    var executed = new AtomicInteger(0);

    Runnable operation = () -> executed.incrementAndGet();
    cb.execute(operation);

    assertEquals(1, executed.get());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void executeRunnableFailure() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(2).build();

    Runnable operation =
        () -> {
          throw new RuntimeException("fail");
        };
    assertThrows(RuntimeException.class, () -> cb.execute(operation));

    assertEquals(1, cb.failureCount());
  }

  @Test
  void stateCheckInClosedWithNoFailures() {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(5)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void defaultValues() {
    var cb = CircuitBreaker.newBuilder().build();

    assertEquals(5, cb.failureThreshold());
    assertEquals(1, cb.successThreshold());
    assertEquals(Duration.ofSeconds(30), cb.halfOpenAfter());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void builderConfiguration() {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(10)
            .withSuccessThreshold(3)
            .withHalfOpenAfter(Duration.ofMinutes(1))
            .build();

    assertEquals(10, cb.failureThreshold());
    assertEquals(3, cb.successThreshold());
    assertEquals(Duration.ofMinutes(1), cb.halfOpenAfter());
  }

  @Test
  void halfOpenFailsFastForNonProbeThreads() throws Exception {
    var threadCount = 10;
    var cb = halfOpenBreaker(1);
    var rejected = new CountDownLatch(threadCount - 1);
    var probes = new AtomicInteger(0);

    runConcurrently(
        threadCount,
        () -> {
          try {
            return cb.execute(
                () -> {
                  probes.incrementAndGet();
                  rejected.await();
                  return "probe success";
                });
          } catch (CircuitBreakerOpenException e) {
            rejected.countDown();
            return "rejected";
          }
        });

    assertEquals(1, probes.get(), "Exactly one thread should probe");
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void builderRejectsZeroFailureThreshold() {
    assertThrows(
        IllegalStateException.class,
        () -> CircuitBreaker.newBuilder().withFailureThreshold(0).build());
  }

  @Test
  void builderRejectsZeroSuccessThreshold() {
    assertThrows(
        IllegalStateException.class,
        () -> CircuitBreaker.newBuilder().withSuccessThreshold(0).build());
  }

  @Test
  void builderRejectsNullHalfOpenAfter() {
    assertThrows(
        IllegalStateException.class,
        () -> CircuitBreaker.newBuilder().withHalfOpenAfter(null).build());
  }

  @Test
  void builderRejectsZeroHalfOpenAfter() {
    assertThrows(
        IllegalStateException.class,
        () -> CircuitBreaker.newBuilder().withHalfOpenAfter(Duration.ZERO).build());
  }

  @Test
  void builderRejectsNegativeHalfOpenAfter() {
    assertThrows(
        IllegalStateException.class,
        () -> CircuitBreaker.newBuilder().withHalfOpenAfter(Duration.ofMillis(-1)).build());
  }

  @Test
  void builderRejectsNullClock() {
    assertThrows(NullPointerException.class, () -> CircuitBreaker.newBuilder().withClock(null));
  }

  @Test
  void stateReadWhileAFailedProbeIsRecordedDoesNotReopenTheCircuit() {
    var cb = halfOpenBreaker(1);

    beforeNextClockRead.set(cb::state);
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("probe fail")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
  }

  @Test
  void probeSuccessCountedWhileAnotherCallerEntersHalfOpenIsNotLost() throws Exception {
    var cb = trippedBreaker(2);
    advance(HALF_OPEN_AFTER.plusNanos(1));

    beforeNextClockRead.set(() -> cb.execute(() -> "first success"));
    cb.execute(() -> "second success");

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @RepeatedTest(5)
  void concurrentFailuresTripsCircuit() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(5).withClock(this::readClock).build();

    runConcurrently(
        20,
        () ->
            assertThrows(Exception.class, () -> cb.execute(() -> throwRuntime("concurrent fail"))));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertTrue(
        cb.failureCount() >= 5,
        "Failure count should be at least the threshold, was: " + cb.failureCount());
  }

  @RepeatedTest(5)
  void concurrentSuccessesKeepCircuitClosed() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(5).build();

    runConcurrently(50, () -> cb.execute(() -> "ok"));

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(0, cb.failureCount());
  }

  @RepeatedTest(5)
  void concurrentMixedSuccessAndFailureUnderThreshold() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(100).build();
    var calls = new AtomicInteger(0);

    runConcurrently(
        20,
        () ->
            calls.getAndIncrement() % 2 == 0
                ? assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")))
                : cb.execute(() -> "ok"));

    assertEquals(CircuitBreaker.State.CLOSED, cb.state(), "Circuit should stay closed");
  }

  @RepeatedTest(5)
  void concurrentHalfOpenToClosedTransition() throws Exception {
    var cb = halfOpenBreaker(1);
    var successes = new AtomicInteger(0);

    runConcurrently(
        20,
        () -> {
          try {
            cb.execute(() -> "ok");
            return successes.incrementAndGet();
          } catch (CircuitBreakerOpenException e) {
            return "rejected";
          }
        });

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertTrue(successes.get() >= 1, "At least one probe should succeed");
  }

  @RepeatedTest(5)
  void concurrentHalfOpenProbeFailureReopensCircuit() throws Exception {
    var threadCount = 10;
    var cb = halfOpenBreaker(1);
    var probeFailures = new AtomicInteger(0);
    var rejections = new AtomicInteger(0);

    runConcurrently(
        threadCount,
        () -> {
          try {
            return cb.execute(() -> throwRuntime("probe fail"));
          } catch (CircuitBreakerOpenException e) {
            return rejections.incrementAndGet();
          } catch (RuntimeException e) {
            return probeFailures.incrementAndGet();
          }
        });

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertEquals(1, probeFailures.get(), "Exactly one thread should probe");
    assertEquals(threadCount - 1, rejections.get(), "Every other thread should fail fast");
  }

  @Test
  void repeatedTripAndRecoverCyclesReturnToClosed() throws Exception {
    var cb = breaker(1);

    for (int cycle = 0; cycle < 100; cycle++) {
      trip(cb);
      advance(HALF_OPEN_AFTER.plusNanos(1));

      assertEquals("recover", cb.execute(() -> "recover"));
      assertEquals(CircuitBreaker.State.CLOSED, cb.state());
      assertEquals(0, cb.failureCount());
    }
  }

  private CircuitBreaker breaker(int successThreshold) {
    return CircuitBreaker.newBuilder()
        .withFailureThreshold(2)
        .withSuccessThreshold(successThreshold)
        .withHalfOpenAfter(HALF_OPEN_AFTER)
        .withClock(this::readClock)
        .build();
  }

  private CircuitBreaker trippedBreaker(int successThreshold) {
    var cb = breaker(successThreshold);
    trip(cb);
    return cb;
  }

  private CircuitBreaker halfOpenBreaker(int successThreshold) {
    var cb = trippedBreaker(successThreshold);
    advance(HALF_OPEN_AFTER.plusNanos(1));
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
    return cb;
  }

  private void advance(Duration by) {
    now.updateAndGet(instant -> instant.plus(by));
  }

  private Instant readClock() {
    try {
      beforeNextClockRead.getAndSet(() -> null).call();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    return now.get();
  }

  private static void trip(CircuitBreaker cb) {
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertEquals(CircuitBreaker.State.OPEN, cb.state());
  }

  private static void runConcurrently(int threadCount, Callable<?> task) throws Exception {
    var barrier = new CyclicBarrier(threadCount);
    var futures = new ArrayList<Future<?>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            executor.submit(
                () -> {
                  barrier.await();
                  return task.call();
                }));
      }
    }
    for (var future : futures) {
      future.get();
    }
  }

  private static String throwRuntime(String message) {
    throw new RuntimeException(message);
  }
}
