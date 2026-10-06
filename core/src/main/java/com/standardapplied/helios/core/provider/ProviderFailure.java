/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.ProviderException;

/**
 * Creates a provider's own exception type, the one its callers catch. A provider passes its
 * exception's {@code (message, statusCode, cause)} constructor, e.g. {@code
 * AnthropicException::new}.
 */
@FunctionalInterface
public interface ProviderFailure {

  /**
   * The provider's exception.
   *
   * @param message what failed
   * @param statusCode the HTTP status, or {@code 0} when the failure is not an HTTP status
   * @param cause the underlying failure, or {@code null}
   * @return the exception, for the caller to throw
   */
  ProviderException create(String message, int statusCode, Throwable cause);
}
