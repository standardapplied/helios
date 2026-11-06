/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

/**
 * Base exception for provider API failures. Carries the HTTP status code (0 for non-HTTP errors
 * such as DNS, TCP, or timeout) and classifies errors as client, server, or retryable.
 *
 * <p>Provider modules subclass this with their own type ({@code AnthropicException}, {@code
 * GeminiException}, {@code OpenAIException}) so callers can catch provider-specific or generic
 * provider errors.
 */
public class ProviderException extends RuntimeException {

  /** The HTTP status the provider answered with, or 0 for a failure that is not an answer. */
  private final int statusCode;

  /**
   * Creates a failure that is not an HTTP answer, with status 0.
   *
   * @param message what failed; may be null
   */
  public ProviderException(String message) {
    super(message);
    this.statusCode = 0;
  }

  /**
   * Creates a failure that is not an HTTP answer, with status 0, caused by {@code cause}.
   *
   * @param message what failed; may be null
   * @param cause the underlying failure; may be null
   */
  public ProviderException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
  }

  /**
   * Creates a failure the provider answered with {@code statusCode}.
   *
   * @param message what failed, typically with the start of the answer's body; may be null
   * @param statusCode the HTTP status; 0 when the failure is not an answer
   */
  public ProviderException(String message, int statusCode) {
    super(message);
    this.statusCode = statusCode;
  }

  /**
   * Creates a failure the provider answered with {@code statusCode}, caused by {@code cause}.
   *
   * @param message what failed; may be null
   * @param statusCode the HTTP status; 0 when the failure is not an answer
   * @param cause the underlying failure; may be null
   */
  public ProviderException(String message, int statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /**
   * HTTP status code, or 0 for non-HTTP failures (DNS, TCP, timeout).
   *
   * @return the status, or 0
   */
  public int statusCode() {
    return statusCode;
  }

  /**
   * Whether the status code is in the 4xx range.
   *
   * @return {@code true} for a status from 400 to 499
   */
  public boolean isClientError() {
    return statusCode >= 400 && statusCode < 500;
  }

  /**
   * Whether the status code is a server error.
   *
   * @return {@code true} for a status of 500 or more
   */
  public boolean isServerError() {
    return statusCode >= 500;
  }

  /**
   * Whether the same request may succeed if sent again: {@code true} for a request timeout (408), a
   * rate limit (429) and every server error (500 or more, 529 included), {@code false} for every
   * other status. A transport failure is never a provider exception but a {@link
   * TransientStreamException}; a status-0 provider exception is a protocol failure (an idle
   * timeout, an oversized line, a malformed or unknown event) and is not retried.
   *
   * @return {@code true} for status 408, 429 or 500 and above
   */
  public boolean isRetryable() {
    return statusCode == 408 || statusCode == 429 || statusCode >= 500;
  }
}
