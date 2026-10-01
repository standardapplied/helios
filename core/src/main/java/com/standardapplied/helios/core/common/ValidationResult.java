/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

/**
 * Outcome of a single {@link ProvenanceValidator} check. {@code ok=true} indicates the entry
 * satisfied the validator. {@code ok=false} carries a model-readable message naming the field and
 * the violated rule.
 *
 * @param ok {@code true} when the entry passed validation
 * @param message null when {@code ok}; non-blank otherwise
 */
public record ValidationResult(boolean ok, String message) {

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
   * @return a singleton success result
   */
  public static ValidationResult success() {
    return SUCCESS;
  }

  /**
   * @param message model-readable explanation of the failure; required and must not be blank
   * @return a failure result carrying the given message
   */
  public static ValidationResult failure(String message) {
    return new ValidationResult(false, message);
  }
}
