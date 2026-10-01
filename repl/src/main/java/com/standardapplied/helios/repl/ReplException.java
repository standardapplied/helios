/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl;

/** Unchecked exception for REPL session and sandbox errors. */
public final class ReplException extends RuntimeException {

  public ReplException(String message) {
    super(message);
  }

  public ReplException(String message, Throwable cause) {
    super(message, cause);
  }
}
