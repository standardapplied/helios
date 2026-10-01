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
   * Get the number of attempts made before giving up.
   *
   * @return the number of attempts
   */
  public int attempts() {
    return attempts;
  }
}
