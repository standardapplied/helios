/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.model.ProviderException;

/** Exception thrown when Anthropic API operations fail. */
public class AnthropicException extends ProviderException {

  public AnthropicException(String message) {
    super(message);
  }

  public AnthropicException(String message, Throwable cause) {
    super(message, cause);
  }

  public AnthropicException(String message, int statusCode) {
    super(message, statusCode);
  }

  public AnthropicException(String message, int statusCode, Throwable cause) {
    super(message, statusCode, cause);
  }
}
