/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

/**
 * Outcome of a single {@link ProvenanceValidator} check. {@code ok=true} indicates the entry
 * satisfied the validator. {@code ok=false} carries a model-readable message naming the field and
 * the violated rule.
 *
 * @param ok {@code true} when the entry passed validation
 * @param message null when {@code ok}; non-blank otherwise
 */
public record ValidationResult(boolean ok, String message) {

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if {@code ok} is {@code false} and {@code message} is null or
   *     blank, or if {@code ok} is {@code true} and {@code message} is not null
   */
  public ValidationResult {
    if (!ok && Strings.isBlank(message)) {
      throw new IllegalArgumentException("failure ValidationResult requires a message");
    }
    if (ok && message != null) {
      throw new IllegalArgumentException("success ValidationResult must have a null message");
    }
  }

  /**
   * Singleton success — every success has a {@code null} message so the cached instance is safe.
   */
  private static final ValidationResult SUCCESS = new ValidationResult(true, null);

  /**
   * Returns the shared success result.
   *
   * @return the singleton success result, whose {@code message} is {@code null}
   */
  public static ValidationResult success() {
    return SUCCESS;
  }

  /**
   * Creates a failure result.
   *
   * @param message model-readable explanation of the failure; required and must not be blank
   * @return a failure result carrying the given message
   * @throws IllegalArgumentException if {@code message} is null or blank
   */
  public static ValidationResult failure(String message) {
    return new ValidationResult(false, message);
  }
}
