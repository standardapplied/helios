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

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Execute an operation with this retry policy.
   *
   * @param operation the operation to execute
   * @param <T> the return type
   * @return the result of the operation
   * @throws RetryExhaustedException if all retries are exhausted
   * @throws InterruptedException if the thread is interrupted while waiting
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

  public static class Builder {
    private int maxAttempts = 3;
    private Backoff backoff = Backoff.exponential(Duration.ofMillis(500), 2.0);
    private double jitter = 0.1;
    private final Set<Class<? extends Throwable>> retryableExceptions = new HashSet<>();
    private Predicate<Throwable> customRetryOn;

    private Builder() {}

    public Builder withMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    public Builder withBackoff(Backoff backoff) {
      this.backoff = backoff;
      return this;
    }

    public Builder withJitter(double jitter) {
      this.jitter = jitter;
      return this;
    }

    @SafeVarargs
    public final Builder withRetryOn(Class<? extends Throwable>... exceptions) {
      for (var exception : exceptions) {
        retryableExceptions.add(exception);
      }
      return this;
    }

    public Builder withRetryOn(Predicate<Throwable> predicate) {
      this.customRetryOn = Objects.requireNonNull(predicate, "retryOn must not be null");
      return this;
    }

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
