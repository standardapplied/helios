/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import java.time.Duration;
import java.util.HashSet;
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

        boolean shouldRetry;
        try {
          shouldRetry = retryOn.test(t);
        } catch (Exception predicateEx) {
          t.addSuppressed(predicateEx);
          break;
        }

        if (attempt == maxAttempts || !shouldRetry) {
          break;
        }

        try {
          var delay = backoff.delay(attempt, jitter);
          Thread.sleep(delay.toMillis());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw ie;
        }
      }
    }

    throw new RetryExhaustedException(maxAttempts, lastException);
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
      this.customRetryOn = predicate;
      return this;
    }

    public RetryPolicy build() {
      if (maxAttempts < 1) {
        throw new IllegalStateException("maxAttempts must be >= 1");
      }
      Predicate<Throwable> retryPredicate = buildRetryPredicate();
      return new RetryPolicy(maxAttempts, backoff, jitter, retryPredicate);
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
