/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.util.Objects;

/**
 * What the options factory is given for a {@code POST /sessions}: the session id the runtime
 * generated and the verified {@link Principal} that will own the session, so a deployer can choose
 * a workspace, model, budget or tool set per tenant or subject. The factory must return options
 * whose {@link com.standardapplied.helios.session.SessionOptions#sessionId() sessionId()} equals
 * {@code sessionId}; options for any other id are refused and no session is created.
 *
 * @param sessionId the id of the session being created
 * @param owner the principal creating, and then owning, the session
 */
public record SessionRequest(String sessionId, Principal owner) {

  /**
   * Validate both components.
   *
   * @throws NullPointerException if either component is null
   */
  public SessionRequest {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
  }
}
