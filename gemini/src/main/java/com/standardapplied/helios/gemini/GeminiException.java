/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.ProviderException;

/**
 * Thrown by a Gemini model or the {@link GeminiFilesClient} when the Gemini API rejects a request,
 * a response stream fails or cannot be read, or a request cannot be built. Callers that serve
 * several providers catch the {@link ProviderException} supertype; {@link
 * ProviderException#statusCode()} is the HTTP status, {@code 0} for a failure that is not one, and
 * {@link ProviderException#isRetryable()} says whether resending may help.
 */
public class GeminiException extends ProviderException {

  /**
   * Creates an exception for a failure that is not an HTTP status, such as a request that cannot be
   * serialised or a response that cannot be read; its {@link #statusCode()} is {@code 0}.
   *
   * @param message what failed
   */
  public GeminiException(String message) {
    super(message);
  }

  /**
   * Creates an exception for a failure that is not an HTTP status, caused by another exception; its
   * {@link #statusCode()} is {@code 0}.
   *
   * @param message what failed
   * @param cause the underlying failure, or {@code null} if none
   */
  public GeminiException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates an exception for a request the API answered with an HTTP error status.
   *
   * @param message what failed, typically with the start of the response body
   * @param statusCode the HTTP status the API answered, or {@code 0} when the failure is not an
   *     HTTP status
   */
  public GeminiException(String message, int statusCode) {
    super(message, statusCode);
  }

  /**
   * Creates an exception with an HTTP status and an underlying cause; this is the constructor the
   * provider passes as its {@link com.standardapplied.helios.core.provider.ProviderFailure}.
   *
   * @param message what failed
   * @param statusCode the HTTP status the API answered, or {@code 0} when the failure is not an
   *     HTTP status
   * @param cause the underlying failure, or {@code null} if none
   */
  public GeminiException(String message, int statusCode, Throwable cause) {
    super(message, statusCode, cause);
  }
}
