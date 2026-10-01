/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FaultToleranceTest {

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
        OperationTimeoutException.class,
        () ->
            ft.execute(
                () -> {
                  Thread.sleep(500);
                  return "too slow";
                }));
  }

  @Test
  void operationTimeoutIncludesRetries() {
    var retryPolicy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(10)
            .withBackoff(Backoff.fixed(Duration.ofMillis(50)))
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
                    Thread.sleep(500);
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
            .withOperationTimeout(Duration.ofSeconds(10))
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
            .withOperationTimeout(Duration.ofSeconds(10))
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
            OperationTimeoutException.class,
            () ->
                ft.execute(
                    () -> {
                      Thread.sleep(200);
                      return "slow";
                    }));

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
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofSeconds(10)).build();

    var result = ft.execute(() -> "quick success");

    assertEquals("quick success", result);
  }

  @Test
  void executeRunnableSuccessWithTimeout() throws Exception {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofSeconds(10)).build();
    var executed = new AtomicInteger(0);

    Runnable operation = () -> executed.incrementAndGet();
    ft.execute(operation);

    assertEquals(1, executed.get());
  }

  @Test
  void runtimeExceptionWithTimeout() {
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofSeconds(10)).build();

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
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofSeconds(10)).build();

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
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofSeconds(10)).build();

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
  void operationTimeoutInterruptsVirtualThread() throws Exception {
    var interrupted = new AtomicBoolean(false);
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofMillis(100)).build();

    assertThrows(
        OperationTimeoutException.class,
        () ->
            ft.execute(
                () -> {
                  try {
                    Thread.sleep(10_000);
                  } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                  }
                  return "too slow";
                }));

    Thread.sleep(200);
    assertTrue(interrupted.get(), "Virtual thread should have been interrupted on timeout");
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
            .withOperationTimeout(Duration.ofSeconds(10))
            .build();

    var exception =
        assertThrows(
            RetryExhaustedException.class, () -> ft.execute(() -> throwRuntime("service down")));

    assertTrue(exception.getMessage().contains("service down"));
    assertInstanceOf(RuntimeException.class, exception.getCause());
    assertEquals("service down", exception.getCause().getMessage());
  }

  @Test
  void interruptedCallerCancelsFuture() throws Exception {
    var operationStarted = new CountDownLatch(1);
    var operationInterrupted = new AtomicBoolean(false);
    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofSeconds(30)).build();

    var thread =
        new Thread(
            () -> {
              try {
                ft.execute(
                    () -> {
                      operationStarted.countDown();
                      try {
                        Thread.sleep(30_000);
                      } catch (InterruptedException e) {
                        operationInterrupted.set(true);
                        throw e;
                      }
                      return "too slow";
                    });
              } catch (InterruptedException e) {
                // Expected — caller was interrupted
              } catch (Exception e) {
                // Other FT exceptions
              }
            });

    thread.start();
    assertTrue(operationStarted.await(5, TimeUnit.SECONDS));
    Thread.sleep(50);
    thread.interrupt();
    thread.join(5000);
    assertFalse(thread.isAlive());
    Thread.sleep(200);
    assertTrue(
        operationInterrupted.get(),
        "Virtual thread should have been interrupted when caller was interrupted");
  }

  private static String throwRuntime(String message) {
    throw new RuntimeException(message);
  }
}
