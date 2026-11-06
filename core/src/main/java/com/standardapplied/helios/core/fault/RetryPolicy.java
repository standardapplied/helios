/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Policy for retrying failed operations with configurable backoff.
 *
 * <p>Supports configurable max attempts, backoff strategy, jitter, and exception filtering.
 *
 * @param maxAttempts maximum number of attempts (including the initial attempt)
 * @param backoff strategy for calculating delay between retries
 * @param jitter jitter factor (0.0 to 1.0) to add randomness to delays
 * @param retryOn predicate to determine if an exception should trigger a retry
 */
public record RetryPolicy(
    int maxAttempts, Backoff backoff, double jitter, Predicate<Throwable> retryOn) {

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if {@code maxAttempts} is below 1 or {@code jitter} is not a
   *     finite value within {@code 0.0}–{@code 1.0}
   * @throws NullPointerException if {@code backoff} or {@code retryOn} is null
   */
  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
    }
    Objects.requireNonNull(backoff, "backoff must not be null");
    if (!(jitter >= 0.0 && jitter <= 1.0)) {
      throw new IllegalArgumentException("jitter must be within 0.0-1.0, got " + jitter);
    }
    Objects.requireNonNull(retryOn, "retryOn must not be null");
  }

  /**
   * Creates a builder with the defaults: 3 attempts, exponential backoff from 500 ms doubling up to
   * 5 minutes, jitter 0.1, and every {@link Exception} retried.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Execute an operation with this retry policy.
   *
   * @param operation the operation to execute
   * @param <T> the return type
   * @return the result of the operation
   * @throws RetryExhaustedException if all retries are exhausted, or the operation failed with an
   *     exception {@code retryOn} rejects or throws on; its cause is the last failure
   * @throws InterruptedException if the operation throws it, or the thread is interrupted while
   *     waiting; the thread's interrupt flag is set again
   */
  public <T> T execute(Callable<T> operation) throws RetryExhaustedException, InterruptedException {
    Exception lastException = null;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return operation.call();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw e;
      } catch (Exception t) {
        lastException = t;
        if (!shouldRetry(t) || attempt == maxAttempts) {
          break;
        }
        pause(attempt);
      }
    }

    throw new RetryExhaustedException(maxAttempts, lastException);
  }

  /** Whether {@code failure} retries; a predicate that throws is suppressed onto it and stops. */
  private boolean shouldRetry(Exception failure) {
    try {
      return retryOn.test(failure);
    } catch (Exception predicateEx) {
      failure.addSuppressed(predicateEx);
      return false;
    }
  }

  private void pause(int attempt) throws InterruptedException {
    try {
      Thread.sleep(backoff.delay(attempt, jitter).toMillis());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw ie;
    }
  }

  /**
   * Execute an operation without returning a value.
   *
   * @param operation the operation to execute
   * @throws RetryExhaustedException if all retries are exhausted
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public void execute(Runnable operation) throws RetryExhaustedException, InterruptedException {
    execute(
        () -> {
          operation.run();
          return null;
        });
  }

  /**
   * Builder for {@link RetryPolicy}; not thread-safe. Every setting is validated by {@link
   * #build()}.
   */
  public static class Builder {
    private int maxAttempts = 3;
    private Backoff backoff = Backoff.exponential(Duration.ofMillis(500), 2.0);
    private double jitter = 0.1;
    private final Set<Class<? extends Throwable>> retryableExceptions = new HashSet<>();
    private Predicate<Throwable> customRetryOn;

    private Builder() {}

    /**
     * Sets how many times the operation is tried, the first attempt included. Defaults to 3.
     *
     * @param maxAttempts the attempts; at least 1 by {@link #build()}
     * @return this builder
     */
    public Builder withMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    /**
     * Sets the delay strategy between attempts. Defaults to {@code Backoff.exponential(500 ms,
     * 2.0)}, capped at 5 minutes.
     *
     * @param backoff the backoff; non-null by {@link #build()}
     * @return this builder
     */
    public Builder withBackoff(Backoff backoff) {
      this.backoff = backoff;
      return this;
    }

    /**
     * Sets the jitter factor: each delay moves by a random amount of up to this fraction of itself,
     * in either direction. Defaults to 0.1; 0.0 waits exactly the backoff's delay.
     *
     * @param jitter the factor; within {@code 0.0}–{@code 1.0} by {@link #build()}
     * @return this builder
     */
    public Builder withJitter(double jitter) {
      this.jitter = jitter;
      return this;
    }

    /**
     * Adds exception types that are retried; a failure that is an instance of none of them is not.
     * Repeated calls add to the set. With none added and no predicate, every {@link Exception} is
     * retried. A predicate set with {@link #withRetryOn(Predicate)} takes precedence over the set.
     *
     * @param exceptions the retried exception types; the array non-null
     * @return this builder
     * @throws NullPointerException if {@code exceptions} is null
     */
    @SafeVarargs
    public final Builder withRetryOn(Class<? extends Throwable>... exceptions) {
      for (var exception : exceptions) {
        retryableExceptions.add(exception);
      }
      return this;
    }

    /**
     * Sets the predicate that decides whether a failure is retried, replacing any earlier one and
     * taking precedence over the types added with {@link #withRetryOn(Class[])}. A predicate that
     * throws stops the retries, its exception suppressed onto the failure.
     *
     * @param predicate returns {@code true} for a failure to retry; non-null
     * @return this builder
     * @throws NullPointerException if {@code predicate} is null
     */
    public Builder withRetryOn(Predicate<Throwable> predicate) {
      this.customRetryOn = Objects.requireNonNull(predicate, "retryOn must not be null");
      return this;
    }

    /**
     * Builds the policy through its canonical constructor.
     *
     * @return the retry policy
     * @throws IllegalArgumentException if the attempts are below 1 or the jitter is outside {@code
     *     0.0}–{@code 1.0}
     * @throws NullPointerException if the backoff is null
     */
    public RetryPolicy build() {
      return new RetryPolicy(maxAttempts, backoff, jitter, buildRetryPredicate());
    }

    private Predicate<Throwable> buildRetryPredicate() {
      if (customRetryOn != null) {
        return customRetryOn;
      }

      if (retryableExceptions.isEmpty()) {
        return t -> true;
      }

      return t -> {
        for (var exceptionClass : retryableExceptions) {
          if (exceptionClass.isInstance(t)) {
            return true;
          }
        }
        return false;
      };
    }
  }
}
