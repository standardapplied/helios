/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

/**
 * Unchecked exception thrown when a PostgreSQL operation of {@link PgTraceStore}, {@link
 * PgAnnotationStore} or {@link PgPromptRegistry} fails, wrapping the database's exception as its
 * cause. {@link PgSessionStore} throws the session store's own exceptions instead.
 */
public class PgException extends RuntimeException {

  /**
   * Creates the exception.
   *
   * @param message what failed, naming the record or query involved
   * @param cause the underlying failure
   */
  public PgException(String message, Throwable cause) {
    super(message, cause);
  }
}
