/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/**
 * Base exception for provider API failures. Carries the HTTP status code (0 for non-HTTP errors
 * such as DNS, TCP, or timeout) and classifies errors as client, server, or retryable.
 *
 * <p>Provider modules subclass this with their own type ({@code AnthropicException}, {@code
 * GeminiException}, {@code OpenAIException}) so callers can catch provider-specific or generic
 * provider errors.
 */
public class ProviderException extends RuntimeException {

  private final int statusCode;

  public ProviderException(String message) {
    super(message);
    this.statusCode = 0;
  }

  public ProviderException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
  }

  public ProviderException(String message, int statusCode) {
    super(message);
    this.statusCode = statusCode;
  }

  public ProviderException(String message, int statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /** HTTP status code, or 0 for non-HTTP failures (DNS, TCP, timeout). */
  public int statusCode() {
    return statusCode;
  }

  /** Whether the status code is in the 4xx range. */
  public boolean isClientError() {
    return statusCode >= 400 && statusCode < 500;
  }

  /** Whether the status code is in the 5xx range. */
  public boolean isServerError() {
    return statusCode >= 500;
  }

  /**
   * Whether this error is retryable. Network errors (status 0), request timeouts (408), rate limits
   * (429), overloaded (529), and server errors (5xx) are considered retryable.
   */
  public boolean isRetryable() {
    return statusCode == 0
        || statusCode == 408
        || statusCode == 429
        || statusCode == 529
        || statusCode >= 500;
  }
}
