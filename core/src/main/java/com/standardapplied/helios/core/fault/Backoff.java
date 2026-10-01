/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Strategy for calculating delay between retry attempts.
 *
 * <p>Implementations provide different backoff strategies such as fixed delays, exponential
 * backoff, or custom algorithms.
 */
public sealed interface Backoff permits Backoff.Fixed, Backoff.Exponential {

  /**
   * Calculate the delay before the next retry attempt.
   *
   * @param attempt the current attempt number (1-based)
   * @param jitter jitter factor (0.0 to 1.0) to add randomness to the delay
   * @return the duration to wait before the next attempt
   */
  Duration delay(int attempt, double jitter);

  /**
   * Calculate the delay without jitter.
   *
   * @param attempt the current attempt number (1-based)
   * @return the duration to wait before the next attempt
   */
  default Duration delay(int attempt) {
    return delay(attempt, 0.0);
  }

  /**
   * Create a fixed backoff strategy.
   *
   * @param delay the constant delay between retries
   * @return a fixed backoff strategy
   */
  static Fixed fixed(Duration delay) {
    return new Fixed(delay);
  }

  /**
   * Create an exponential backoff strategy.
   *
   * @param initialDelay the delay for the first retry
   * @param multiplier factor to multiply the delay by for each subsequent attempt
   * @return an exponential backoff strategy
   */
  static Exponential exponential(Duration initialDelay, double multiplier) {
    return new Exponential(initialDelay, multiplier, Duration.ofMinutes(5));
  }

  /**
   * Create an exponential backoff strategy with a maximum delay.
   *
   * @param initialDelay the delay for the first retry
   * @param multiplier factor to multiply the delay by for each subsequent attempt
   * @param maxDelay the maximum delay cap
   * @return an exponential backoff strategy
   */
  static Exponential exponential(Duration initialDelay, double multiplier, Duration maxDelay) {
    return new Exponential(initialDelay, multiplier, maxDelay);
  }

  /**
   * Fixed backoff strategy with constant delay between attempts.
   *
   * @param delay the constant delay between retries
   */
  record Fixed(Duration delay) implements Backoff {
    public Fixed {
      if (delay == null || delay.isNegative()) {
        throw new IllegalArgumentException("delay must be non-negative");
      }
    }

    @Override
    public Duration delay(int attempt, double jitter) {
      return applyJitter(delay, jitter);
    }
  }

  /**
   * Exponential backoff strategy that increases delay exponentially.
   *
   * <p>Delay is calculated as: initialDelay * (multiplier ^ (attempt - 1)), capped at maxDelay.
   *
   * @param initialDelay the delay for the first retry
   * @param multiplier factor to multiply the delay by for each subsequent attempt
   * @param maxDelay the maximum delay cap
   */
  record Exponential(Duration initialDelay, double multiplier, Duration maxDelay)
      implements Backoff {
    public Exponential {
      if (initialDelay == null || initialDelay.isNegative()) {
        throw new IllegalArgumentException("initialDelay must be non-negative");
      }
      if (multiplier < 1.0) {
        throw new IllegalArgumentException("multiplier must be >= 1.0");
      }
      if (maxDelay == null || maxDelay.isNegative()) {
        throw new IllegalArgumentException("maxDelay must be non-negative");
      }
    }

    @Override
    public Duration delay(int attempt, double jitter) {
      var factor = Math.pow(multiplier, attempt - 1);
      var delayMillis = (long) (initialDelay.toMillis() * factor);
      var cappedMillis = Math.min(delayMillis, maxDelay.toMillis());
      return applyJitter(Duration.ofMillis(cappedMillis), jitter);
    }
  }

  private static Duration applyJitter(Duration delay, double jitter) {
    if (jitter <= 0.0) {
      return delay;
    }
    var jitterFraction = Math.min(jitter, 1.0);
    var delayMillis = delay.toMillis();
    var maxJitterMillis = (long) (delayMillis * jitterFraction);
    var actualJitter = ThreadLocalRandom.current().nextLong(-maxJitterMillis, maxJitterMillis + 1);
    return Duration.ofMillis(Math.max(0, delayMillis + actualJitter));
  }
}
