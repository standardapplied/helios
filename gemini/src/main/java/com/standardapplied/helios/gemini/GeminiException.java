/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import ai.singlr.core.model.ProviderException;

/** Exception thrown when Gemini API operations fail. */
public class GeminiException extends ProviderException {

  public GeminiException(String message) {
    super(message);
  }

  public GeminiException(String message, Throwable cause) {
    super(message, cause);
  }

  public GeminiException(String message, int statusCode) {
    super(message, statusCode);
  }

  public GeminiException(String message, int statusCode, Throwable cause) {
    super(message, statusCode, cause);
  }
}
