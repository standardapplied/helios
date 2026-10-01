/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

/**
 * Thrown by {@link JacksonReader} and {@link JacksonWriter} when (de)serialization fails. Wraps the
 * originating Jackson exception so route handlers can translate it to a 4xx/5xx response.
 */
public final class JacksonRuntimeException extends RuntimeException {

  /**
   * @param message a human-readable description
   * @param cause the originating Jackson exception
   */
  public JacksonRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }
}
