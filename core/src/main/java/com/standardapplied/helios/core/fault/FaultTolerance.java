/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Combines retry policy, circuit breaker, and timeout into a unified fault tolerance mechanism.
 *
 * <p>Execution order:
 *
 * <ol>
 *   <li>Operation timeout wraps the entire execution (including retries)
 *   <li>Circuit breaker checks if calls should be allowed
 *   <li>Retry policy handles transient failures
 * </ol>
 *
 * <p>Example:
 *
 * <pre>{@code
 * FaultTolerance ft = FaultTolerance.newBuilder()
 *     .withRetry(RetryPolicy.newBuilder()
 *         .withMaxAttempts(3)
 *         .withBackoff(Backoff.exponential(Duration.ofMillis(500), 2.0))
 *         .build())
 *     .withCircuitBreaker(CircuitBreaker.newBuilder()
 *         .withFailureThreshold(5)
 *         .withHalfOpenAfter(Duration.ofSeconds(30))
 *         .build())
 *     .withOperationTimeout(Duration.ofMinutes(5))
 *     .build();
 *
 * String result = ft.execute(() -> callExternalService());
 * }</pre>
 */
public class FaultTolerance {

  private static final ExecutorService VIRTUAL_EXECUTOR =
      Executors.newVirtualThreadPerTaskExecutor();

  /**
   * A no-op passthrough that executes operations directly without retry, circuit breaker, or
   * timeout.
   */
  public static final FaultTolerance PASSTHROUGH = new FaultTolerance(null, null, null);

  private final RetryPolicy retryPolicy;
  private final CircuitBreaker circuitBreaker;
  private final Duration operationTimeout;
  private final ExecutorService executor;

  private FaultTolerance(
      RetryPolicy retryPolicy, CircuitBreaker circuitBreaker, Duration operationTimeout) {
    this(retryPolicy, circuitBreaker, operationTimeout, VIRTUAL_EXECUTOR);
  }

  /**
   * For tests of the timeout, which starts when {@code executor} has accepted the operation: an
   * executor that returns from {@code submit} only once the operation runs puts the timeout after
   * the start, whatever the scheduling.
   */
  FaultTolerance(
      RetryPolicy retryPolicy,
      CircuitBreaker circuitBreaker,
      Duration operationTimeout,
      ExecutorService executor) {
    this.retryPolicy = retryPolicy;
    this.circuitBreaker = circuitBreaker;
    this.operationTimeout = operationTimeout;
    this.executor = executor;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Execute an operation with fault tolerance.
   *
   * @param operation the operation to execute
   * @param <T> the return type
   * @return the result of the operation
   * @throws OperationTimeoutException if the operation times out
   * @throws CircuitBreakerOpenException if the circuit is open
   * @throws RetryExhaustedException if all retries are exhausted
   * @throws InterruptedException if the thread is interrupted
   */
  public <T> T execute(Callable<T> operation)
      throws OperationTimeoutException,
          CircuitBreakerOpenException,
          RetryExhaustedException,
          InterruptedException {

    if (operationTimeout == null) {
      return executeWithoutTimeout(operation);
    }

    return executeWithTimeout(operation);
  }

  /**
   * Execute an operation without returning a value.
   *
   * @param operation the operation to execute
   * @throws OperationTimeoutException if the operation times out
   * @throws CircuitBreakerOpenException if the circuit is open
   * @throws RetryExhaustedException if all retries are exhausted
   * @throws InterruptedException if the thread is interrupted
   */
  public void execute(Runnable operation)
      throws OperationTimeoutException,
          CircuitBreakerOpenException,
          RetryExhaustedException,
          InterruptedException {
    execute(
        () -> {
          operation.run();
          return null;
        });
  }

  /**
   * Get the retry policy.
   *
   * @return the retry policy, or null if not configured
   */
  public RetryPolicy retryPolicy() {
    return retryPolicy;
  }

  /**
   * Get the circuit breaker.
   *
   * @return the circuit breaker, or null if not configured
   */
  public CircuitBreaker circuitBreaker() {
    return circuitBreaker;
  }

  /**
   * Get the operation timeout.
   *
   * @return the operation timeout, or null if not configured
   */
  public Duration operationTimeout() {
    return operationTimeout;
  }

  /**
   * Returns a sibling {@code FaultTolerance} with the retry policy stripped but the circuit breaker
   * and operation timeout retained. Returns {@code this} if no retry policy is configured.
   *
   * <p>Used by the agent loop to enforce that non-idempotent tools execute at most once even when a
   * retry policy is configured at the agent level — replaying a side-effecting call would duplicate
   * the effect. Circuit breaker and timeout still apply because they are resume/replay-safe.
   */
  public FaultTolerance withoutRetry() {
    if (retryPolicy == null) {
      return this;
    }
    return new FaultTolerance(null, circuitBreaker, operationTimeout, executor);
  }

  private <T> T executeWithTimeout(Callable<T> operation)
      throws OperationTimeoutException,
          CircuitBreakerOpenException,
          RetryExhaustedException,
          InterruptedException {

    Future<T> future = executor.submit(() -> executeWithoutTimeout(operation));

    try {
      return future.get(operationTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new OperationTimeoutException(operationTimeout);
    } catch (InterruptedException e) {
      future.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      throw rethrow(e.getCause());
    }
  }

  /** Re-throws what the timed operation failed with, unwrapping its checked exceptions. */
  private static RuntimeException rethrow(Throwable cause)
      throws CircuitBreakerOpenException, RetryExhaustedException, InterruptedException {
    switch (cause) {
      case CircuitBreakerOpenException cbe -> throw cbe;
      case RetryExhaustedException ree -> throw ree;
      case InterruptedException ie -> {
        Thread.currentThread().interrupt();
        throw ie;
      }
      case RuntimeException re -> throw re;
      case null, default -> throw new RuntimeException(cause);
    }
  }

  private <T> T executeWithoutTimeout(Callable<T> operation)
      throws CircuitBreakerOpenException, RetryExhaustedException, InterruptedException {
    Callable<T> attempt = retryPolicy == null ? operation : () -> retryPolicy.execute(operation);
    if (circuitBreaker == null) {
      return call(attempt);
    }
    try {
      return circuitBreaker.execute(attempt);
    } catch (CircuitBreakerOpenException
        | RetryExhaustedException
        | InterruptedException
        | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static <T> T call(Callable<T> operation)
      throws RetryExhaustedException, InterruptedException {
    try {
      return operation.call();
    } catch (RetryExhaustedException | InterruptedException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static class Builder {
    private RetryPolicy retryPolicy;
    private CircuitBreaker circuitBreaker;
    private Duration operationTimeout;

    private Builder() {}

    public Builder withRetry(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    public Builder withCircuitBreaker(CircuitBreaker circuitBreaker) {
      this.circuitBreaker = circuitBreaker;
      return this;
    }

    public Builder withOperationTimeout(Duration operationTimeout) {
      this.operationTimeout = operationTimeout;
      return this;
    }

    public FaultTolerance build() {
      if (operationTimeout != null
          && (operationTimeout.isNegative() || operationTimeout.isZero())) {
        throw new IllegalStateException("operationTimeout must be a positive duration");
      }
      return new FaultTolerance(retryPolicy, circuitBreaker, operationTimeout);
    }
  }
}
