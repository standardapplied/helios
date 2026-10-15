/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.runtime.SessionCapacityException.Scope;
import java.util.HashMap;
import java.util.Map;

/**
 * The session caps, and the sessions each principal and the whole registry hold against them. A
 * reservation counts as a session and as an active one from the moment it is taken; a session stops
 * counting as active when it reaches its terminal result, and stops counting at all when it is
 * unregistered or its reservation is released. A principal holding nothing has no entry.
 *
 * <p>Not thread-safe: {@link SessionTable} changes it only under its lock.
 */
final class SessionQuota {

  private final int maxSessions;
  private final int maxActiveSessions;
  private final int maxSessionsPerPrincipal;
  private final int maxActiveSessionsPerPrincipal;
  private final Map<Principal, Usage> byPrincipal = new HashMap<>();
  private Usage total = Usage.NONE;

  SessionQuota(
      int maxSessions,
      int maxActiveSessions,
      int maxSessionsPerPrincipal,
      int maxActiveSessionsPerPrincipal) {
    this.maxSessions = maxSessions;
    this.maxActiveSessions = maxActiveSessions;
    this.maxSessionsPerPrincipal = maxSessionsPerPrincipal;
    this.maxActiveSessionsPerPrincipal = maxActiveSessionsPerPrincipal;
  }

  /**
   * Refuse unless {@code owner} may hold one more active session. Evicting a terminal session never
   * makes room for an active one, so this is checked before any eviction.
   */
  void requireActiveRoom(Principal owner) {
    require(
        usage(owner).active(),
        maxActiveSessionsPerPrincipal,
        Scope.PRINCIPAL,
        "active sessions of the principal");
    require(total.active(), maxActiveSessions, Scope.SERVER, "active sessions");
  }

  /** Whether one more session of {@code owner} would pass a cap on sessions held. */
  boolean sessionsFull(Principal owner) {
    return usage(owner).sessions() >= maxSessionsPerPrincipal || total.sessions() >= maxSessions;
  }

  /** Count one more session and active session for {@code owner}, or refuse if a cap is full. */
  void reserve(Principal owner) {
    require(
        usage(owner).sessions(),
        maxSessionsPerPrincipal,
        Scope.PRINCIPAL,
        "sessions of the principal");
    require(total.sessions(), maxSessions, Scope.SERVER, "sessions");
    change(owner, 1, 1);
  }

  /** One of {@code owner}'s sessions reached its terminal result. */
  void terminated(Principal owner) {
    change(owner, 0, -1);
  }

  /**
   * One of {@code owner}'s sessions or reservations is gone; {@code active} if it had not ended.
   */
  void release(Principal owner, boolean active) {
    change(owner, -1, active ? -1 : 0);
  }

  private Usage usage(Principal owner) {
    return byPrincipal.getOrDefault(owner, Usage.NONE);
  }

  private void change(Principal owner, int sessions, int active) {
    total = total.plus(sessions, active);
    byPrincipal.compute(
        owner,
        (principal, held) -> {
          var next = (held == null ? Usage.NONE : held).plus(sessions, active);
          return next.sessions() == 0 ? null : next;
        });
  }

  private static void require(int held, int cap, Scope scope, String what) {
    if (held >= cap) {
      throw new SessionCapacityException(scope, what + " at cap " + cap);
    }
  }

  private record Usage(int sessions, int active) {

    private static final Usage NONE = new Usage(0, 0);

    private Usage plus(int moreSessions, int moreActive) {
      return new Usage(sessions + moreSessions, active + moreActive);
    }
  }
}
