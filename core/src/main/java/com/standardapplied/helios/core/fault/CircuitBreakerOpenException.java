/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

/**
 * Exception thrown when an operation is rejected because the circuit breaker is open.
 *
 * <p>This indicates the protected service is considered unavailable and calls are being rejected to
 * prevent cascading failures.
 */
public class CircuitBreakerOpenException extends Exception {

  /** Create a new CircuitBreakerOpenException with a default message. */
  public CircuitBreakerOpenException() {
    super("Circuit breaker is open");
  }

  /**
   * Create a new CircuitBreakerOpenException with a custom message.
   *
   * @param message the detail message
   */
  public CircuitBreakerOpenException(String message) {
    super(message);
  }
}
