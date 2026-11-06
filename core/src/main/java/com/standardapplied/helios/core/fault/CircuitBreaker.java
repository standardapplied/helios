/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
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
 *
 * <p>Thread-safe: one instance is shared by every caller of the protected service. In the {@code
 * HALF_OPEN} state one probe runs at a time, and a call arriving while a probe runs fails with
 * {@link CircuitBreakerOpenException}.
 */
public class CircuitBreaker {

  /** Circuit breaker states. */
  public enum State {
    /**
     * Calls run; consecutive failures are counted, and reaching the threshold opens the circuit.
     */
    CLOSED,
    /** Calls fail at once with {@link CircuitBreakerOpenException} until the half-open delay. */
    OPEN,
    /** One probe call at a time runs; enough successes close the circuit, a failure reopens it. */
    HALF_OPEN
  }

  private final int failureThreshold;
  private final int successThreshold;
  private final Duration halfOpenAfter;
  private final InstantSource clock;

  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private final AtomicInteger successCount = new AtomicInteger(0);
  private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>();
  private final ReentrantLock halfOpenLock = new ReentrantLock();

  private CircuitBreaker(
      int failureThreshold, int successThreshold, Duration halfOpenAfter, InstantSource clock) {
    this.failureThreshold = failureThreshold;
    this.successThreshold = successThreshold;
    this.halfOpenAfter = halfOpenAfter;
    this.clock = clock;
  }

  /**
   * Creates a builder with the defaults: failure threshold 5, success threshold 1, half-open after
   * 30 seconds, and the system UTC clock.
   *
   * @return a new builder
   */
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
      if (lastFailure != null
          && clock.instant().isAfter(lastFailure.plus(halfOpenAfter))
          && state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
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
    lastFailureTime.set(clock.instant());

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

  /**
   * Records the failure time before reopening: a caller that sees {@code OPEN} must also see the
   * new failure time, or it would judge the delay against the previous one and let a second probe
   * through at once.
   */
  private void onHalfOpenFailure() {
    successCount.set(0);
    lastFailureTime.set(clock.instant());
    state.set(State.OPEN);
  }

  /**
   * Builder for {@link CircuitBreaker}; not thread-safe. Every setting is validated by {@link
   * #build()}.
   */
  public static class Builder {
    private int failureThreshold = 5;
    private int successThreshold = 1;
    private Duration halfOpenAfter = Duration.ofSeconds(30);
    private InstantSource clock = Clock.systemUTC();

    private Builder() {}

    /**
     * Sets how many consecutive failures in the {@link State#CLOSED} state open the circuit; a
     * success resets the count. Defaults to 5.
     *
     * @param failureThreshold the consecutive failures that trip the circuit; at least 1 by {@link
     *     #build()}
     * @return this builder
     */
    public Builder withFailureThreshold(int failureThreshold) {
      this.failureThreshold = failureThreshold;
      return this;
    }

    /**
     * Sets how many successful probe calls in the {@link State#HALF_OPEN} state close the circuit.
     * Defaults to 1.
     *
     * @param successThreshold the probe successes that close the circuit; at least 1 by {@link
     *     #build()}
     * @return this builder
     */
    public Builder withSuccessThreshold(int successThreshold) {
      this.successThreshold = successThreshold;
      return this;
    }

    /**
     * Sets how long the circuit stays {@link State#OPEN} after its last failure before it lets a
     * probe through as {@link State#HALF_OPEN}; the circuit moves once the clock is strictly past
     * that instant. Defaults to 30 seconds.
     *
     * @param halfOpenAfter the delay; non-null and positive by {@link #build()}
     * @return this builder
     */
    public Builder withHalfOpenAfter(Duration halfOpenAfter) {
      this.halfOpenAfter = halfOpenAfter;
      return this;
    }

    /**
     * Source of the current instant, read when a failure is recorded and when the half-open delay
     * is checked. Defaults to {@link Clock#systemUTC()}; a test passes a source it advances by
     * hand.
     *
     * @param clock non-null instant source
     * @return this builder
     * @throws NullPointerException if {@code clock} is null
     */
    public Builder withClock(InstantSource clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    /**
     * Builds a circuit breaker in the {@link State#CLOSED} state.
     *
     * @return the circuit breaker
     * @throws IllegalStateException if either threshold is below 1, or the half-open delay is null,
     *     zero or negative
     */
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
      return new CircuitBreaker(failureThreshold, successThreshold, halfOpenAfter, clock);
    }
  }
}
