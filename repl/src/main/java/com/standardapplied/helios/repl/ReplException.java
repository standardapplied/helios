/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

/** Unchecked exception for REPL session and sandbox errors. */
public final class ReplException extends RuntimeException {

  public ReplException(String message) {
    super(message);
  }

  public ReplException(String message, Throwable cause) {
    super(message, cause);
  }
}
