/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

/**
 * A session refused by {@link
 * SessionRegistry#create(com.standardapplied.helios.session.SessionOptions, Principal)} because a
 * session cap is reached and no terminal session of the requesting principal could be evicted to
 * make room. Nothing of the session was allocated. {@link #scope()} says whose cap refused it,
 * which is all a caller learns: never which other principals hold sessions.
 */
public final class SessionCapacityException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** Whose cap refused the session. */
  public enum Scope {
    /** The requesting principal holds its maximum of sessions. */
    PRINCIPAL,
    /** The registry holds its maximum of sessions, across all principals. */
    SERVER
  }

  private final Scope scope;

  SessionCapacityException(Scope scope, String message) {
    super(message);
    this.scope = scope;
  }

  /**
   * Whose cap refused the session.
   *
   * @return the principal's own cap or the registry's
   */
  public Scope scope() {
    return scope;
  }
}
