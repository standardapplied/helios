/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Circuit breaker that prevents cascading failures by failing fast when a service is unavailable.
 *
 * <p>States:
 *
 * <ul>
 *   <li>CLOSED: Normal operation, failures are counted
 *   <li>OPEN: Circuit is tripped, calls fail immediately without executing
 *   <li>HALF_OPEN: Testing recovery, limited calls allowed through
 * </ul>
 */
public class CircuitBreaker {

  /** Circuit breaker states. */
  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int failureThreshold;
  private final int successThreshold;
  private final Duration halfOpenAfter;

  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private final AtomicInteger successCount = new AtomicInteger(0);
  private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>();
  private final ReentrantLock halfOpenLock = new ReentrantLock();

  private CircuitBreaker(int failureThreshold, int successThreshold, Duration halfOpenAfter) {
    this.failureThreshold = failureThreshold;
    this.successThreshold = successThreshold;
    this.halfOpenAfter = halfOpenAfter;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Execute an operation through the circuit breaker.
   *
   * @param operation the operation to execute
   * @param <T> the return type
   * @return the result of the operation
   * @throws CircuitBreakerOpenException if the circuit is open
   * @throws Exception if the operation fails
   */
  public <T> T execute(Callable<T> operation) throws Exception {
    checkAndTransition();

    var currentState = state.get();

    if (currentState == State.OPEN) {
      throw new CircuitBreakerOpenException();
    }

    if (currentState == State.HALF_OPEN) {
      return executeInHalfOpen(operation);
    }

    return executeInClosed(operation);
  }

  /**
   * Execute an operation without returning a value.
   *
   * @param operation the operation to execute
   * @throws CircuitBreakerOpenException if the circuit is open
   * @throws Exception if the operation fails
   */
  public void execute(Runnable operation) throws Exception {
    execute(
        () -> {
          operation.run();
          return null;
        });
  }

  /**
   * Get the current state of the circuit breaker.
   *
   * @return the current state
   */
  public State state() {
    checkAndTransition();
    return state.get();
  }

  /** Reset the circuit breaker to closed state. */
  public void reset() {
    state.set(State.CLOSED);
    failureCount.set(0);
    successCount.set(0);
    lastFailureTime.set(null);
  }

  /**
   * Get the current failure count.
   *
   * @return the failure count
   */
  public int failureCount() {
    return failureCount.get();
  }

  /**
   * Get the configured failure threshold.
   *
   * @return the failure threshold
   */
  public int failureThreshold() {
    return failureThreshold;
  }

  /**
   * Get the configured success threshold.
   *
   * @return the success threshold
   */
  public int successThreshold() {
    return successThreshold;
  }

  /**
   * Get the configured half-open delay.
   *
   * @return the half-open delay
   */
  public Duration halfOpenAfter() {
    return halfOpenAfter;
  }

  private void checkAndTransition() {
    if (state.get() == State.OPEN) {
      var lastFailure = lastFailureTime.get();
      if (lastFailure != null && Instant.now().isAfter(lastFailure.plus(halfOpenAfter))) {
        state.compareAndSet(State.OPEN, State.HALF_OPEN);
        successCount.set(0);
      }
    }
  }

  private <T> T executeInClosed(Callable<T> operation) throws Exception {
    try {
      var result = operation.call();
      onSuccess();
      return result;
    } catch (Exception e) {
      onFailure();
      throw e;
    }
  }

  private <T> T executeInHalfOpen(Callable<T> operation) throws Exception {
    if (!halfOpenLock.tryLock()) {
      throw new CircuitBreakerOpenException();
    }
    try {
      if (state.get() != State.HALF_OPEN) {
        return execute(operation);
      }

      try {
        var result = operation.call();
        onHalfOpenSuccess();
        return result;
      } catch (Exception e) {
        onHalfOpenFailure();
        throw e;
      }
    } finally {
      halfOpenLock.unlock();
    }
  }

  private void onSuccess() {
    failureCount.set(0);
  }

  private void onFailure() {
    var failures = failureCount.incrementAndGet();
    lastFailureTime.set(Instant.now());

    if (failures >= failureThreshold) {
      state.set(State.OPEN);
    }
  }

  private void onHalfOpenSuccess() {
    var successes = successCount.incrementAndGet();
    if (successes >= successThreshold) {
      state.set(State.CLOSED);
      failureCount.set(0);
    }
  }

  private void onHalfOpenFailure() {
    state.set(State.OPEN);
    lastFailureTime.set(Instant.now());
    successCount.set(0);
  }

  public static class Builder {
    private int failureThreshold = 5;
    private int successThreshold = 1;
    private Duration halfOpenAfter = Duration.ofSeconds(30);

    private Builder() {}

    public Builder withFailureThreshold(int failureThreshold) {
      this.failureThreshold = failureThreshold;
      return this;
    }

    public Builder withSuccessThreshold(int successThreshold) {
      this.successThreshold = successThreshold;
      return this;
    }

    public Builder withHalfOpenAfter(Duration halfOpenAfter) {
      this.halfOpenAfter = halfOpenAfter;
      return this;
    }

    public CircuitBreaker build() {
      if (failureThreshold < 1) {
        throw new IllegalStateException("failureThreshold must be >= 1");
      }
      if (successThreshold < 1) {
        throw new IllegalStateException("successThreshold must be >= 1");
      }
      if (halfOpenAfter == null || halfOpenAfter.isNegative() || halfOpenAfter.isZero()) {
        throw new IllegalStateException("halfOpenAfter must be a positive duration");
      }
      return new CircuitBreaker(failureThreshold, successThreshold, halfOpenAfter);
    }
  }
}
