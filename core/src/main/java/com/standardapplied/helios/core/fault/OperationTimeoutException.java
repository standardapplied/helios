/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import java.time.Duration;

/**
 * Exception thrown when an operation exceeds its configured timeout.
 *
 * <p>This is the operation-level timeout that wraps the entire execution including retries, not the
 * HTTP-level timeouts (connect/response).
 */
public class OperationTimeoutException extends Exception {

  private final Duration timeout;

  /**
   * Create a new OperationTimeoutException.
   *
   * @param timeout the timeout that was exceeded
   */
  public OperationTimeoutException(Duration timeout) {
    super("Operation timed out after " + timeout);
    this.timeout = timeout;
  }

  /**
   * Get the timeout duration that was exceeded.
   *
   * @return the timeout duration
   */
  public Duration timeout() {
    return timeout;
  }
}
