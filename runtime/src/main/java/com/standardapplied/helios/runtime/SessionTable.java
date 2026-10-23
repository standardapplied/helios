/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.AgentSession;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry's sessions by id, and the capacity they and the open reservations hold. Every change
 * to what is reserved, registered or terminal happens under the table's lock, so admission is
 * atomic: concurrent creates never pass a cap. Lookups read without the lock. The table never
 * closes a session; it returns the sessions it unregistered so the registry closes them outside the
 * lock.
 *
 * <p>An unregistered session that has not reached its terminal result keeps its charge: closing it
 * only asks its loop to stop, so it is released when the result completes, never before.
 */
final class SessionTable {

  private static final Comparator<Entry> BY_TERMINATION =
      Comparator.comparing(entry -> entry.terminatedAt);

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final SessionQuota quota;
  private final InstantSource clock;
  private final Duration retention;

  SessionTable(SessionQuota quota, InstantSource clock, Duration retention) {
    this.quota = quota;
    this.clock = clock;
    this.retention = retention;
  }

  /**
   * Reserve one session for {@code owner}. When a cap stands in the way, the oldest terminal
   * session of {@code owner} is unregistered to make room; another principal's session never is.
   *
   * @return the session evicted to make room, which the caller closes
   * @throws SessionCapacityException if a cap is reached and nothing of {@code owner} can make room
   */
  synchronized Optional<AgentSession> reserve(Principal owner) {
    var victim =
        quota.full(owner)
            ? entries.values().stream().filter(entry -> entry.terminalOf(owner)).min(BY_TERMINATION)
            : Optional.<Entry>empty();
    victim.ifPresent(this::unregister);
    quota.reserve(owner);
    return victim.map(Entry::session);
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
    var entry = new Entry(id, session, owner, clock.instant());
    if (entries.putIfAbsent(id, entry) != null) {
      return false;
    }
    session
        .result()
        .whenComplete(
            (result, failure) -> {
              synchronized (this) {
                entry.terminatedAt = clock.instant();
                if (entries.get(id) != entry) {
                  quota.release(owner);
                }
              }
            });
    return true;
  }

  /** The session registered under {@code id} if {@code requester} owns it. */
  Optional<AgentSession> find(String id, Principal requester) {
    return Optional.ofNullable(entries.get(id))
        .filter(entry -> entry.owner.equals(requester))
        .map(Entry::session);
  }

  /** Unregister the session under {@code id} if {@code requester} owns it. */
  synchronized Optional<AgentSession> remove(String id, Principal requester) {
    var owned = Optional.ofNullable(entries.get(id)).filter(entry -> entry.owner.equals(requester));
    owned.ifPresent(this::unregister);
    return owned.map(Entry::session);
  }

  /**
   * Unregister every session not running for the retention: a terminal one measured from its end,
   * one never started from its registration. A started session that has not ended is kept, however
   * old.
   */
  synchronized List<AgentSession> removeExpired() {
    var now = clock.instant();
    var expired = entries.values().stream().filter(entry -> entry.expired(now, retention)).toList();
    expired.forEach(this::unregister);
    return expired.stream().map(Entry::session).toList();
  }

  /** Unregister every session. */
  synchronized List<AgentSession> removeAll() {
    var all = List.copyOf(entries.values());
    all.forEach(this::unregister);
    return all.stream().map(Entry::session).toList();
  }

  int size() {
    return entries.size();
  }

  private void unregister(Entry entry) {
    entries.remove(entry.id);
    if (entry.terminatedAt != null) {
      quota.release(entry.owner);
    }
  }

  /**
   * One registered session. {@link #terminatedAt} is written and read only under the table's lock;
   * {@code null} while the session has not reached its terminal result.
   */
  private static final class Entry {

    private final String id;
    private final AgentSession session;
    private final Principal owner;
    private final Instant registeredAt;
    private Instant terminatedAt;

    private Entry(String id, AgentSession session, Principal owner, Instant registeredAt) {
      this.id = id;
      this.session = session;
      this.owner = owner;
      this.registeredAt = registeredAt;
    }

    private AgentSession session() {
      return session;
    }

    private boolean terminalOf(Principal principal) {
      return terminatedAt != null && owner.equals(principal);
    }

    private boolean expired(Instant now, Duration retention) {
      if (terminatedAt != null) {
        return Duration.between(terminatedAt, now).compareTo(retention) >= 0;
      }
      return !session.isStarted() && Duration.between(registeredAt, now).compareTo(retention) >= 0;
    }
  }
}
