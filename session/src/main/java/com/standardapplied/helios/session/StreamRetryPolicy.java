/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.fault.Backoff;
import java.time.Duration;
import java.util.Objects;

/**
 * Policy controlling how the agent loop retries a model-stream attempt that failed with a {@link
 * ai.singlr.core.model.TransientStreamException}.
 *
 * <p>The loop owns retry orchestration; the provider classifies which exceptions are transient. On
 * each failure the loop emits a {@link QueryEvent.TurnRetried} carrying the next back-off delay,
 * sleeps with cancellation honoured, and re-invokes {@code model.chatStream(...)} from the same
 * history snapshot. When the attempt budget is exhausted, the loop terminates the session as {@link
 * ResultMessage.ErrorTransientStream}.
 *
 * <p>The {@code maxAttempts} field counts the <i>total</i> attempts including the initial one,
 * mirroring the existing {@link ai.singlr.core.fault.RetryPolicy} convention. {@code maxAttempts =
 * 1} disables retry; {@link #disabled()} is a named alias for that case.
 *
 * <p>Production defaults (see {@link #defaults()}): three attempts (initial plus two retries), 1 s
 * initial back-off with multiplier 4 capped at 30 s, ±25% jitter. Conservative enough that one
 * transient blip is masked; aggressive enough that a real outage terminates well inside {@link
 * SessionLimits#streamIdleTimeout()} multiplied by the attempt count.
 *
 * @param maxAttempts total attempts including the initial one; must be {@code >= 1}. {@code 1}
 *     means retry is disabled
 * @param backoff delay strategy applied between attempts; consulted with {@code attempt =
 *     failedAttemptNumber} (1-based, so the wait before the second attempt asks for {@code
 *     backoff.delay(1, jitter)})
 * @param jitter fractional randomness applied to the back-off delay; in the closed range {@code
 *     [0.0, 1.0]}
 */
public record StreamRetryPolicy(int maxAttempts, Backoff backoff, double jitter) {

  private static final StreamRetryPolicy DEFAULTS =
      new StreamRetryPolicy(
          3, Backoff.exponential(Duration.ofSeconds(1), 4.0, Duration.ofSeconds(30)), 0.25);

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code backoff} is null
   * @throws IllegalArgumentException if {@code maxAttempts < 1} or {@code jitter} is outside {@code
   *     [0.0, 1.0]}
   */
  public StreamRetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
    }
    Objects.requireNonNull(backoff, "backoff must not be null");
    if (jitter < 0.0 || jitter > 1.0) {
      throw new IllegalArgumentException("jitter must be in [0.0, 1.0], got " + jitter);
    }
  }

  /**
   * Returns the shared production-default policy: three attempts total, exponential back-off
   * starting at 1 s with multiplier 4 capped at 30 s, jitter 0.25.
   *
   * @return the production defaults
   */
  public static StreamRetryPolicy defaults() {
    return DEFAULTS;
  }

  /**
   * Returns a policy that disables retry — the loop attempts the stream exactly once and terminates
   * as {@link ResultMessage.ErrorTransientStream} on the first failure.
   *
   * <p>Equivalent to {@code new StreamRetryPolicy(1, Backoff.fixed(Duration.ZERO), 0.0)}. The
   * specific back-off carries no behavioural meaning because no wait ever occurs.
   *
   * @return a single-attempt policy with no back-off
   */
  public static StreamRetryPolicy disabled() {
    return new StreamRetryPolicy(1, Backoff.fixed(Duration.ZERO), 0.0);
  }

  /**
   * Whether retry is enabled — equivalent to {@code maxAttempts > 1}.
   *
   * @return {@code true} when more than one attempt may be made
   */
  public boolean enabled() {
    return maxAttempts > 1;
  }

  /**
   * Calculate the back-off delay before attempt number {@code nextAttempt}. Convenience over {@link
   * Backoff#delay(int, double)} with this policy's {@link #jitter()} applied.
   *
   * @param failedAttempt 1-based number of the attempt that just failed; the next attempt is {@code
   *     failedAttempt + 1}. Must be {@code >= 1}
   * @return the duration to wait before the next attempt
   * @throws IllegalArgumentException if {@code failedAttempt < 1}
   */
  public Duration nextDelay(int failedAttempt) {
    if (failedAttempt < 1) {
      throw new IllegalArgumentException("failedAttempt must be >= 1, got " + failedAttempt);
    }
    return backoff.delay(failedAttempt, jitter);
  }
}
