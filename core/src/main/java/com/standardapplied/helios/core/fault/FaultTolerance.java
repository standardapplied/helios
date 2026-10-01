/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

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

  /**
   * A no-op passthrough that executes operations directly without retry, circuit breaker, or
   * timeout.
   */
  public static final FaultTolerance PASSTHROUGH = new FaultTolerance(null, null, null);

  private static final ExecutorService VIRTUAL_EXECUTOR =
      Executors.newVirtualThreadPerTaskExecutor();

  private final RetryPolicy retryPolicy;
  private final CircuitBreaker circuitBreaker;
  private final Duration operationTimeout;

  private FaultTolerance(
      RetryPolicy retryPolicy, CircuitBreaker circuitBreaker, Duration operationTimeout) {
    this.retryPolicy = retryPolicy;
    this.circuitBreaker = circuitBreaker;
    this.operationTimeout = operationTimeout;
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
    return new FaultTolerance(null, circuitBreaker, operationTimeout);
  }

  private <T> T executeWithTimeout(Callable<T> operation)
      throws OperationTimeoutException,
          CircuitBreakerOpenException,
          RetryExhaustedException,
          InterruptedException {

    Future<T> future = VIRTUAL_EXECUTOR.submit(() -> executeWithoutTimeoutUnchecked(operation));

    try {
      return future.get(operationTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new OperationTimeoutException(operationTimeout);
    } catch (InterruptedException e) {
      future.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof WrappedException we) {
        cause = we.getCause();
      }
      if (cause instanceof CircuitBreakerOpenException cbe) {
        throw cbe;
      }
      if (cause instanceof RetryExhaustedException ree) {
        throw ree;
      }
      if (cause instanceof InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      }
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(cause);
    }
  }

  private <T> T executeWithoutTimeout(Callable<T> operation)
      throws CircuitBreakerOpenException, RetryExhaustedException, InterruptedException {

    Callable<T> wrappedOperation = operation;

    if (retryPolicy != null) {
      final Callable<T> toRetry = wrappedOperation;
      wrappedOperation = () -> retryPolicy.execute(toRetry);
    }

    if (circuitBreaker != null) {
      try {
        return circuitBreaker.execute(wrappedOperation);
      } catch (CircuitBreakerOpenException | RetryExhaustedException | InterruptedException e) {
        throw e;
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    try {
      return wrappedOperation.call();
    } catch (RetryExhaustedException | InterruptedException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private <T> T executeWithoutTimeoutUnchecked(Callable<T> operation) {
    try {
      return executeWithoutTimeout(operation);
    } catch (CircuitBreakerOpenException | RetryExhaustedException | InterruptedException e) {
      throw new WrappedException(e);
    }
  }

  private static class WrappedException extends RuntimeException {
    WrappedException(Exception cause) {
      super(cause);
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
