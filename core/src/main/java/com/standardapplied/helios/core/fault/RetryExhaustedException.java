/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

/**
 * Exception thrown when all retry attempts have been exhausted.
 *
 * <p>Contains the number of attempts made and the last exception that caused the failure.
 */
public class RetryExhaustedException extends Exception {

  /**
   * The attempt count given at construction; {@link RetryPolicy} passes its {@code maxAttempts},
   * also when a failure it does not retry ended the run sooner.
   */
  private final int attempts;

  /**
   * Create a new RetryExhaustedException.
   *
   * @param attempts the number of attempts made
   * @param cause the last exception that caused the failure
   */
  public RetryExhaustedException(int attempts, Throwable cause) {
    super(
        "Retry exhausted after "
            + attempts
            + " attempts"
            + (cause != null ? ": " + cause.getMessage() : ""),
        cause);
    this.attempts = attempts;
  }

  /**
   * Get the number of attempts the policy allowed before giving up.
   *
   * @return the attempt count given at construction, which {@link RetryPolicy} sets to its {@code
   *     maxAttempts} even when a failure it does not retry ended the run sooner
   */
  public int attempts() {
    return attempts;
  }
}
