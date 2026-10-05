/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A test that expects the operation timeout gives the code under test a short one and an operation
 * that cannot finish on its own, so the timeout is the only thing that can end the call. Every
 * other test that configures a timeout uses {@link #NEVER_REACHED}.
 */
class FaultToleranceTest {

  private static final Duration NEVER_REACHED = Duration.ofMinutes(10);

  @Test
  void successfulOperation() throws Exception {
    var ft = FaultTolerance.newBuilder().build();

    var result = ft.execute(() -> "success");

    assertEquals("success", result);
  }

  @Test
  void withRetryOnTransientFailure() throws Exception {
    var retryPolicy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(3)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var ft = FaultTolerance.newBuilder().withRetry(retryPolicy).build();
    var attempts = new AtomicInteger(0);

    var result =
        ft.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void withCircuitBreakerTripped() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(2).build();
    var ft = FaultTolerance.newBuilder().withCircuitBreaker(cb).build();

    assertThrows(RuntimeException.class, () -> ft.execute(() -> throwRuntime("fail 1")));
    assertThrows(RuntimeException.class, () -> ft.execute(() -> throwRuntime("fail 2")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    assertThrows(CircuitBreakerOpenException.class, () -> ft.execute(() -> "blocked"));
  }

  @Test
  void withOperationTimeout() {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofMillis(100)).build();

    assertThrows(
        OperationTimeoutException.class, () -> ft.execute(FaultToleranceTest::neverFinishes));
  }

  @Test
  void operationTimeoutIncludesRetries() {
    var retryPolicy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(10)
            .withBackoff(Backoff.fixed(NEVER_REACHED))
            .build();
    var ft =
        FaultTolerance.newBuilder()
            .withRetry(retryPolicy)
            .withOperationTimeout(Duration.ofMillis(100))
            .build();

    assertThrows(
        OperationTimeoutException.class, () -> ft.execute(() -> throwRuntime("always fail")));
  }

  @Test
  void combinedRetryAndCircuitBreaker() throws Exception {
    var retryPolicy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(2)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();
    var ft = FaultTolerance.newBuilder().withRetry(retryPolicy).withCircuitBreaker(cb).build();

    assertThrows(RetryExhaustedException.class, () -> ft.execute(() -> throwRuntime("fail")));

    assertEquals(1, cb.failureCount());

    assertThrows(RetryExhaustedException.class, () -> ft.execute(() -> throwRuntime("fail")));

    assertEquals(2, cb.failureCount());
  }

  @Test
  void executeRunnable() throws Exception {
    var ft = FaultTolerance.newBuilder().build();
    var executed = new AtomicInteger(0);

    Runnable operation = () -> executed.incrementAndGet();
    ft.execute(operation);

    assertEquals(1, executed.get());
  }

  @Test
  void executeRunnableWithTimeout() {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofMillis(100)).build();

    assertThrows(
        OperationTimeoutException.class,
        () ->
            ft.execute(
                () -> {
                  try {
                    neverFinishes();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
  }

  @Test
  void accessors() {
    var retryPolicy = RetryPolicy.newBuilder().build();
    var cb = CircuitBreaker.newBuilder().build();
    var timeout = Duration.ofMinutes(5);

    var ft =
        FaultTolerance.newBuilder()
            .withRetry(retryPolicy)
            .withCircuitBreaker(cb)
            .withOperationTimeout(timeout)
            .build();

    assertEquals(retryPolicy, ft.retryPolicy());
    assertEquals(cb, ft.circuitBreaker());
    assertEquals(timeout, ft.operationTimeout());
  }

  @Test
  void emptyBuilderHasNullComponents() {
    var ft = FaultTolerance.newBuilder().build();

    assertNull(ft.retryPolicy());
    assertNull(ft.circuitBreaker());
    assertNull(ft.operationTimeout());
  }

  @Test
  void retryExhaustedPropagates() {
    var retryPolicy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(2)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var ft = FaultTolerance.newBuilder().withRetry(retryPolicy).build();

    var exception =
        assertThrows(
            RetryExhaustedException.class, () -> ft.execute(() -> throwRuntime("always fail")));

    assertEquals(2, exception.attempts());
  }

  @Test
  void circuitBreakerOpenPropagatesWithTimeout() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(1).build();
    var ft =
        FaultTolerance.newBuilder()
            .withCircuitBreaker(cb)
            .withOperationTimeout(NEVER_REACHED)
            .build();

    assertThrows(RuntimeException.class, () -> ft.execute(() -> throwRuntime("fail")));

    assertThrows(CircuitBreakerOpenException.class, () -> ft.execute(() -> "blocked"));
  }

  @Test
  void retryExhaustedPropagatesWithTimeout() {
    var retryPolicy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(2)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var ft =
        FaultTolerance.newBuilder()
            .withRetry(retryPolicy)
            .withOperationTimeout(NEVER_REACHED)
            .build();

    var exception =
        assertThrows(
            RetryExhaustedException.class, () -> ft.execute(() -> throwRuntime("always fail")));

    assertEquals(2, exception.attempts());
  }

  @Test
  void operationTimeoutExceptionContainsTimeout() {
    var timeout = Duration.ofMillis(50);
    var ft = FaultTolerance.newBuilder().withOperationTimeout(timeout).build();

    var exception =
        assertThrows(
            OperationTimeoutException.class, () -> ft.execute(FaultToleranceTest::neverFinishes));

    assertEquals(timeout, exception.timeout());
    assertNotNull(exception.getMessage());
  }

  @Test
  void runtimeExceptionPropagates() {
    var ft = FaultTolerance.newBuilder().build();

    var exception =
        assertThrows(RuntimeException.class, () -> ft.execute(() -> throwRuntime("runtime error")));

    assertEquals("runtime error", exception.getMessage());
  }

  @Test
  void runtimeExceptionPropagatesWithCircuitBreaker() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(10).build();
    var ft = FaultTolerance.newBuilder().withCircuitBreaker(cb).build();

    var exception =
        assertThrows(RuntimeException.class, () -> ft.execute(() -> throwRuntime("runtime error")));

    assertEquals("runtime error", exception.getMessage());
  }

  @Test
  void checkedExceptionWrappedInRuntimeException() {
    var ft = FaultTolerance.newBuilder().build();

    var exception =
        assertThrows(
            RuntimeException.class,
            () ->
                ft.execute(
                    () -> {
                      throw new Exception("checked");
                    }));

    assertInstanceOf(Exception.class, exception.getCause());
  }

  @Test
  void successfulOperationWithTimeout() throws Exception {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(NEVER_REACHED).build();

    var result = ft.execute(() -> "quick success");

    assertEquals("quick success", result);
  }

  @Test
  void executeRunnableSuccessWithTimeout() throws Exception {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(NEVER_REACHED).build();
    var executed = new AtomicInteger(0);

    Runnable operation = () -> executed.incrementAndGet();
    ft.execute(operation);

    assertEquals(1, executed.get());
  }

  @Test
  void runtimeExceptionWithTimeout() {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(NEVER_REACHED).build();

    var exception =
        assertThrows(RuntimeException.class, () -> ft.execute(() -> throwRuntime("quick fail")));

    assertEquals("quick fail", exception.getMessage());
  }

  @Test
  void checkedExceptionWithCircuitBreaker() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(10).build();
    var ft = FaultTolerance.newBuilder().withCircuitBreaker(cb).build();

    var exception =
        assertThrows(
            RuntimeException.class,
            () ->
                ft.execute(
                    () -> {
                      throw new Exception("checked");
                    }));

    assertInstanceOf(Exception.class, exception.getCause());
  }

  @Test
  void interruptedExceptionWithTimeout() {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(NEVER_REACHED).build();

    assertThrows(
        InterruptedException.class,
        () ->
            ft.execute(
                () -> {
                  throw new InterruptedException("interrupted");
                }));
  }

  @Test
  void circuitBreakerOnlySuccessfulOperation() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(5).build();
    var ft = FaultTolerance.newBuilder().withCircuitBreaker(cb).build();

    var result = ft.execute(() -> "success with circuit breaker only");

    assertEquals("success with circuit breaker only", result);
  }

  @Test
  void checkedExceptionWithTimeoutWrapped() {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(NEVER_REACHED).build();

    var exception =
        assertThrows(
            RuntimeException.class,
            () ->
                ft.execute(
                    () -> {
                      throw new java.io.IOException("io error");
                    }));

    assertInstanceOf(java.io.IOException.class, exception.getCause());
  }

  @Test
  void errorWithTimeoutWrappedInRuntimeException() {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(NEVER_REACHED).build();
    var error = new AssertionError("broken invariant");

    var exception =
        assertThrows(
            RuntimeException.class,
            () ->
                ft.execute(
                    () -> {
                      throw error;
                    }));

    assertEquals(RuntimeException.class, exception.getClass());
    assertSame(error, exception.getCause());
    assertEquals("java.lang.AssertionError: broken invariant", exception.getMessage());
  }

  @Test
  void builderRejectsZeroTimeout() {
    assertThrows(
        IllegalStateException.class,
        () -> FaultTolerance.newBuilder().withOperationTimeout(Duration.ZERO).build());
  }

  @Test
  void builderRejectsNegativeTimeout() {
    assertThrows(
        IllegalStateException.class,
        () -> FaultTolerance.newBuilder().withOperationTimeout(Duration.ofMillis(-1)).build());
  }

  @Test
  void passthroughConstantIsNonNull() {
    assertNotNull(FaultTolerance.PASSTHROUGH);
    assertNull(FaultTolerance.PASSTHROUGH.retryPolicy());
    assertNull(FaultTolerance.PASSTHROUGH.circuitBreaker());
    assertNull(FaultTolerance.PASSTHROUGH.operationTimeout());
  }

  @Test
  void passthroughExecutesDirectly() throws Exception {
    var result = FaultTolerance.PASSTHROUGH.execute(() -> "direct");
    assertEquals("direct", result);
  }

  @Test
  void operationTimeoutInterruptsVirtualThread() {
    var interrupted = new CountDownLatch(1);

    try (var executor = new StartedOnSubmit()) {
      var ft = new FaultTolerance(null, null, Duration.ofMillis(100), executor);

      assertThrows(
          OperationTimeoutException.class,
          () ->
              ft.execute(
                  () -> {
                    try {
                      return neverFinishes();
                    } catch (InterruptedException e) {
                      interrupted.countDown();
                      throw e;
                    }
                  }));

      Await.latch("the timed-out operation to be interrupted", interrupted);
    }
  }

  @Test
  void withoutRetryKeepsTheExecutor() throws Exception {
    var retryPolicy = RetryPolicy.newBuilder().withMaxAttempts(2).build();

    try (var executor = new StartedOnSubmit()) {
      var ft = new FaultTolerance(retryPolicy, null, NEVER_REACHED, executor).withoutRetry();

      assertEquals("done", ft.execute(() -> "done"));
      assertEquals(1, executor.submitted.get());
    }
  }

  @Test
  void retryExhaustedIncludesRootCauseMessage() {
    var retryPolicy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(2)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var ft = FaultTolerance.newBuilder().withRetry(retryPolicy).build();

    var exception =
        assertThrows(
            RetryExhaustedException.class,
            () -> ft.execute(() -> throwRuntime("connection refused")));

    assertTrue(exception.getMessage().contains("connection refused"));
    assertInstanceOf(RuntimeException.class, exception.getCause());
    assertEquals("connection refused", exception.getCause().getMessage());
  }

  @Test
  void retryExhaustedIncludesRootCauseWithTimeout() {
    var retryPolicy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(2)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var ft =
        FaultTolerance.newBuilder()
            .withRetry(retryPolicy)
            .withOperationTimeout(NEVER_REACHED)
            .build();

    var exception =
        assertThrows(
            RetryExhaustedException.class, () -> ft.execute(() -> throwRuntime("service down")));

    assertTrue(exception.getMessage().contains("service down"));
    assertInstanceOf(RuntimeException.class, exception.getCause());
    assertEquals("service down", exception.getCause().getMessage());
  }

  @Test
  void interruptedCallerCancelsFuture() {
    var operationStarted = new CountDownLatch(1);
    var operationInterrupted = new CountDownLatch(1);
    var callerOutcome = new CompletableFuture<String>();
    var ft = FaultTolerance.newBuilder().withOperationTimeout(NEVER_REACHED).build();

    var caller =
        new Thread(
            () -> {
              try {
                callerOutcome.complete(
                    ft.execute(
                        () -> {
                          operationStarted.countDown();
                          try {
                            return neverFinishes();
                          } catch (InterruptedException e) {
                            operationInterrupted.countDown();
                            throw e;
                          }
                        }));
              } catch (Exception e) {
                callerOutcome.completeExceptionally(e);
              }
            });

    caller.start();
    Await.latch("the operation to start", operationStarted);
    caller.interrupt();

    assertInstanceOf(
        InterruptedException.class, Await.failure("the interrupted caller", callerOutcome));
    Await.latch("the operation of the interrupted caller to be interrupted", operationInterrupted);
    Await.termination("the caller thread", caller);
  }

  private static String neverFinishes() throws InterruptedException {
    new CountDownLatch(1).await();
    throw new AssertionError("a latch nobody counts down was released");
  }

  private static String throwRuntime(String message) {
    throw new RuntimeException(message);
  }

  /**
   * Returns from {@code submit} only once the operation is running, so a timeout that starts after
   * the submission interrupts running work however late the worker was scheduled.
   */
  private static final class StartedOnSubmit extends ScheduledThreadPoolExecutor {

    final AtomicInteger submitted = new AtomicInteger();

    StartedOnSubmit() {
      super(1, Thread.ofVirtual().factory());
    }

    @Override
    public <T> Future<T> submit(Callable<T> operation) {
      var started = new CountDownLatch(1);
      var future =
          super.submit(
              () -> {
                started.countDown();
                return operation.call();
              });
      Await.latch("the submitted operation to start", started);
      submitted.incrementAndGet();
      return future;
    }
  }
}
