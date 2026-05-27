/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.core.model.ProviderException;

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
