/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl;

/**
 * Unchecked exception for REPL session and sandbox errors: thrown when a {@link ReplSession} cannot
 * be created (no permit left, sandbox start failed), when code is sent to a closed session or a
 * dead sandbox, and when the sandbox refuses the host-function wrappers at start.
 */
public final class ReplException extends RuntimeException {

  /**
   * Creates the exception with a message and no cause.
   *
   * @param message what failed; may be {@code null}
   */
  public ReplException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a message and the failure that caused it.
   *
   * @param message what failed; may be {@code null}
   * @param cause the underlying failure; may be {@code null}
   */
  public ReplException(String message, Throwable cause) {
    super(message, cause);
  }
}
