/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

/**
 * Thrown by {@link AgentSession#open} when the session's execution provider does not accept it: its
 * {@code onSessionStart} refused, returned {@code null}, threw an {@link Exception}, or did not
 * return within {@link SessionLimits#startTimeout()}. The message names the provider and carries
 * its reason, redacted against the session's secret registry; the cause is the exception the
 * provider refused with or threw, if any. Everything {@code open} acquired is released before it is
 * thrown.
 */
public final class SessionUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message the provider and its redacted reason
   * @param cause the provider's exception, or {@code null} for a refusal without one
   */
  public SessionUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
