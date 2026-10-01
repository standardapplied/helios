/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

/** Unchecked exception thrown when PostgreSQL operations fail. */
public class PgException extends RuntimeException {

  public PgException(String message, Throwable cause) {
    super(message, cause);
  }
}
