/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.AgentSession;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry's sessions by id, each a {@link RegisteredSession}, and the capacity they and the
 * open reservations hold. Every change to what is reserved, registered, active or idle happens
 * under the table's lock, so admission is atomic: concurrent creates never pass a cap. Lookups read
 * without the lock. The table never closes a session; it returns the sessions it unregistered so
 * the registry closes them outside the lock.
 *
 * <p>An unregistered session keeps its charge until it has stopped: an idle one until its close
 * returned ({@link RegisteredSession#close}), which ends its execution provider, and an active one
 * until its run completed too, which closing it brings about within its stop grace. A session
 * evicted to make room gives its charge to the reservation at once and is closed before that
 * reservation's session is built.
 */
final class SessionTable {

  private static final Comparator<RegisteredSession> BY_IDLENESS =
      Comparator.comparing(RegisteredSession::idleSince);

  private final ConcurrentHashMap<String, RegisteredSession> entries = new ConcurrentHashMap<>();
  private final SessionQuota quota;
  private final InstantSource clock;
  private final Duration retention;

  SessionTable(SessionQuota quota, InstantSource clock, Duration retention) {
    this.quota = quota;
    this.clock = clock;
    this.retention = retention;
  }

  /**
   * Reserve one session for {@code owner}. When a cap stands in the way, the session of {@code
   * owner} idle the longest is unregistered to make room, its charge given to the reservation at
   * once; another principal's session never is.
   *
   * @return the session evicted to make room, which the caller closes
   * @throws SessionCapacityException if a cap is reached and nothing of {@code owner} can make room
   */
  synchronized Optional<RegisteredSession> reserve(Principal owner) {
    var victim =
        quota.full(owner)
            ? entries.values().stream()
                .filter(entry -> entry.isIdle() && entry.ownedBy(owner))
                .min(BY_IDLENESS)
            : Optional.<RegisteredSession>empty();
    victim.ifPresent(
        entry -> {
          unregister(entry);
          entry.release();
        });
    quota.reserve(owner);
    return victim;
  }

  /** Release a reservation of {@code owner} that never became a registered session. */
  synchronized void release(Principal owner) {
    quota.release(owner);
  }

  /**
   * Register {@code session} under {@code id} in place of one reservation of {@code owner}.
   *
   * @return {@code false}, leaving the reservation in place, if {@code id} is already registered
   */
  synchronized boolean register(String id, AgentSession session, Principal owner) {
    var entry = new RegisteredSession(session, owner, this, clock, () -> quota.release(owner));
    return entries.putIfAbsent(id, entry) == null;
  }

  /** The session registered under {@code id} if {@code requester} owns it. */
  Optional<RegisteredSession> lookup(String id, Principal requester) {
    return Optional.ofNullable(entries.get(id)).filter(entry -> entry.ownedBy(requester));
  }

  /** Unregister the session under {@code id} if {@code requester} owns it. */
  synchronized Optional<RegisteredSession> remove(String id, Principal requester) {
    var owned = Optional.ofNullable(entries.get(id)).filter(entry -> entry.ownedBy(requester));
    owned.ifPresent(this::unregister);
    return owned;
  }

  /**
   * Unregister every session idle for the retention: measured from the end of its last run, or from
   * its registration if it never ran. An active session is kept, however old.
   */
  synchronized List<RegisteredSession> removeExpired() {
    var now = clock.instant();
    var expired = entries.values().stream().filter(entry -> entry.expired(now, retention)).toList();
    expired.forEach(this::unregister);
    return expired;
  }

  /** Unregister every session. */
  synchronized List<RegisteredSession> removeAll() {
    var all = List.copyOf(entries.values());
    all.forEach(this::unregister);
    return all;
  }

  int size() {
    return entries.size();
  }

  private void unregister(RegisteredSession entry) {
    entries.remove(entry.session().sessionId());
  }
}
