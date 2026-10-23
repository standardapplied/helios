/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.runtime.SessionCapacityException.Scope;
import java.util.HashMap;
import java.util.Map;

/**
 * The two session caps, and the sessions each principal and the whole registry hold against them. A
 * reservation counts from the moment it is taken; it stops counting when it is released, or when
 * the session it became is unregistered once its result has completed. A principal holding nothing
 * has no entry.
 *
 * <p>Not thread-safe: {@link SessionTable} changes it only under its lock.
 */
final class SessionQuota {

  private final int maxSessions;
  private final int maxSessionsPerPrincipal;
  private final Map<Principal, Integer> byPrincipal = new HashMap<>();
  private int total;

  SessionQuota(int maxSessions, int maxSessionsPerPrincipal) {
    this.maxSessions = maxSessions;
    this.maxSessionsPerPrincipal = maxSessionsPerPrincipal;
  }

  /** Whether one more session of {@code owner} would pass a cap. */
  boolean full(Principal owner) {
    return held(owner) >= maxSessionsPerPrincipal || total >= maxSessions;
  }

  /**
   * Count one more session for {@code owner}, or refuse. The owner's own cap is checked first, so
   * an owner at it is refused the same whatever other principals hold.
   */
  void reserve(Principal owner) {
    require(held(owner), maxSessionsPerPrincipal, Scope.PRINCIPAL, "sessions of the principal");
    require(total, maxSessions, Scope.SERVER, "sessions");
    change(owner, 1);
  }

  /** One of {@code owner}'s sessions or reservations is gone. */
  void release(Principal owner) {
    change(owner, -1);
  }

  /** Number of principals holding a session or a reservation. Visible for tests. */
  int principalsForTests() {
    return byPrincipal.size();
  }

  private int held(Principal owner) {
    return byPrincipal.getOrDefault(owner, 0);
  }

  private void change(Principal owner, int sessions) {
    total += sessions;
    byPrincipal.compute(
        owner,
        (principal, held) -> {
          var next = (held == null ? 0 : held) + sessions;
          return next == 0 ? null : next;
        });
  }

  private static void require(int held, int cap, Scope scope, String what) {
    if (held >= cap) {
      throw new SessionCapacityException(scope, what + " at cap " + cap);
    }
  }
}
