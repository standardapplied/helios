/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import java.util.Objects;

/**
 * A {@link SessionStore} could not load or append. A session whose append fails ends its run with
 * {@code ErrorDuringExecution} of kind {@code SessionStoreFailed} and closes itself.
 */
public class SessionStoreException extends RuntimeException {

  /**
   * Creates the exception a store throws when it cannot load or append.
   *
   * @param message what failed; non-null
   * @param cause the underlying failure; nullable
   * @throws NullPointerException if {@code message} is null
   */
  public SessionStoreException(String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message must not be null"), cause);
  }
}
