/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.ProviderException;

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
