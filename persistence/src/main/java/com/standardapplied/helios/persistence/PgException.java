/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

/** Unchecked exception thrown when PostgreSQL operations fail. */
public class PgException extends RuntimeException {

  public PgException(String message, Throwable cause) {
    super(message, cause);
  }
}
