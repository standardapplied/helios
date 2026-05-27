/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import ai.singlr.core.model.ProviderException;

/** Exception thrown when OpenAI API operations fail. */
public class OpenAIException extends ProviderException {

  public OpenAIException(String message) {
    super(message);
  }

  public OpenAIException(String message, Throwable cause) {
    super(message, cause);
  }

  public OpenAIException(String message, int statusCode) {
    super(message, statusCode);
  }

  public OpenAIException(String message, int statusCode, Throwable cause) {
    super(message, statusCode, cause);
  }
}
