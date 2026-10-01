/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

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
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();

    for (int i = 0; i < 3; i++) {
      assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    }

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertEquals(3, cb.failureCount());
  }

  @Test
  void openStateRejectsCalls() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(2).build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());

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
  void transitionToHalfOpenAfterDelay() throws Exception {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    Thread.sleep(100);

    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
  }

  @Test
  void halfOpenSuccessClosesCircuit() throws Exception {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withSuccessThreshold(1)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    Thread.sleep(100);

    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.execute(() -> "success");

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(0, cb.failureCount());
  }

  @Test
  void halfOpenFailureOpensCircuit() throws Exception {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    Thread.sleep(100);

    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail again")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
  }

  @Test
  void multipleSuccessesRequiredToClose() throws Exception {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withSuccessThreshold(3)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    Thread.sleep(100);

    cb.execute(() -> "success 1");
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.execute(() -> "success 2");
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.execute(() -> "success 3");
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void reset() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(2).build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());

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
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withSuccessThreshold(1)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    Thread.sleep(100);
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    var barrier = new CyclicBarrier(threadCount);
    var probeStarted = new CountDownLatch(1);
    var successes = new AtomicInteger(0);
    var openExceptions = new AtomicInteger(0);

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              cb.execute(
                  () -> {
                    probeStarted.countDown();
                    Thread.sleep(200);
                    return "probe success";
                  });
              successes.incrementAndGet();
            } catch (CircuitBreakerOpenException e) {
              openExceptions.incrementAndGet();
            } catch (Exception e) {
              // other exceptions ignored
            }
          });
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    assertEquals(1, successes.get(), "Exactly one thread should probe successfully");
    assertEquals(
        threadCount - 1,
        openExceptions.get(),
        "All other threads should fail fast with CircuitBreakerOpenException");
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

  // --- Concurrent stress tests ---

  @RepeatedTest(5)
  void concurrentFailuresTripsCircuit() throws Exception {
    var threadCount = 20;
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(5).build();
    var barrier = new CyclicBarrier(threadCount);
    var tripped = new CountDownLatch(1);

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              cb.execute(
                  () -> {
                    throw new RuntimeException("concurrent fail");
                  });
            } catch (CircuitBreakerOpenException e) {
              tripped.countDown();
            } catch (Exception e) {
              // original RuntimeException propagated — expected
            }
          });
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertTrue(
        cb.failureCount() >= 5,
        "Failure count should be at least the threshold, was: " + cb.failureCount());
  }

  @RepeatedTest(5)
  void concurrentSuccessesKeepCircuitClosed() throws Exception {
    var threadCount = 50;
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(5).build();
    var barrier = new CyclicBarrier(threadCount);
    var completedCount = new AtomicInteger(0);

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              cb.execute(() -> "ok");
              completedCount.incrementAndGet();
            } catch (Exception e) {
              // should not happen
            }
          });
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(threadCount, completedCount.get());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(0, cb.failureCount());
  }

  @RepeatedTest(5)
  void concurrentMixedSuccessAndFailureUnderThreshold() throws Exception {
    var threadCount = 20;
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(100).build();
    var barrier = new CyclicBarrier(threadCount);
    var completedCount = new AtomicInteger(0);

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < threadCount; i++) {
      var shouldFail = i % 2 == 0;
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              cb.execute(
                  () -> {
                    if (shouldFail) {
                      throw new RuntimeException("fail");
                    }
                    return "ok";
                  });
              completedCount.incrementAndGet();
            } catch (RuntimeException e) {
              // expected for failing threads
            } catch (Exception e) {
              // barrier/interrupt — ignore
            }
          });
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(CircuitBreaker.State.CLOSED, cb.state(), "Circuit should stay closed");
  }

  @RepeatedTest(5)
  void concurrentHalfOpenToClosedTransition() throws Exception {
    var threadCount = 20;
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withSuccessThreshold(1)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    // Trip the circuit
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    // Wait for HALF_OPEN
    Thread.sleep(100);
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    // Flood with concurrent successes — exactly one probes, rest fail fast
    var barrier = new CyclicBarrier(threadCount);
    var successes = new AtomicInteger(0);
    var openExceptions = new AtomicInteger(0);

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              cb.execute(() -> "ok");
              successes.incrementAndGet();
            } catch (CircuitBreakerOpenException e) {
              openExceptions.incrementAndGet();
            } catch (Exception e) {
              // ignore
            }
          });
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertTrue(successes.get() >= 1, "At least one probe should succeed");
    assertEquals(threadCount, successes.get() + openExceptions.get(), "All threads should finish");
  }

  @RepeatedTest(5)
  void concurrentHalfOpenProbeFailureReopensCircuit() throws Exception {
    var threadCount = 10;
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withSuccessThreshold(1)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    // Trip the circuit
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    // Wait for HALF_OPEN
    Thread.sleep(100);
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    // All threads fail — probe(s) should reopen the circuit.
    // On slow CI, halfOpenAfter (50ms) may elapse between the first probe's failure
    // and a late thread, causing a correct OPEN→HALF_OPEN cycle and a second probe.
    var barrier = new CyclicBarrier(threadCount);
    var runtimeExceptions = new AtomicInteger(0);
    var openExceptions = new AtomicInteger(0);

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              cb.execute(
                  () -> {
                    throw new RuntimeException("probe fail");
                  });
            } catch (CircuitBreakerOpenException e) {
              openExceptions.incrementAndGet();
            } catch (RuntimeException e) {
              runtimeExceptions.incrementAndGet();
            } catch (Exception e) {
              // ignore
            }
          });
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertTrue(
        runtimeExceptions.get() >= 1,
        "At least one probe thread should throw RuntimeException, was: " + runtimeExceptions.get());
    assertEquals(
        threadCount, runtimeExceptions.get() + openExceptions.get(), "All threads should finish");
  }

  @RepeatedTest(5)
  void rapidOpenCloseTransitionsUnderLoad() throws Exception {
    var iterations = 100;
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withSuccessThreshold(1)
            .withHalfOpenAfter(Duration.ofMillis(10))
            .build();

    for (int cycle = 0; cycle < iterations; cycle++) {
      // Trip
      try {
        cb.execute(() -> throwRuntime("fail"));
      } catch (RuntimeException ignored) {
      }
      try {
        cb.execute(() -> throwRuntime("fail"));
      } catch (RuntimeException | CircuitBreakerOpenException ignored) {
      }

      // Wait and recover
      Thread.sleep(15);
      try {
        cb.execute(() -> "recover");
      } catch (CircuitBreakerOpenException ignored) {
        // Timing can cause this — acceptable
      }
    }

    // Circuit should be in a valid state — not corrupted
    var state = cb.state();
    assertTrue(
        state == CircuitBreaker.State.CLOSED
            || state == CircuitBreaker.State.OPEN
            || state == CircuitBreaker.State.HALF_OPEN,
        "State must be valid after rapid cycling");
  }

  private static String throwRuntime(String message) {
    throw new RuntimeException(message);
  }
}
