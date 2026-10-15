/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SessionOptions;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Capacity for one session, taken by {@link SessionRegistry#reserve(Principal)} before anything of
 * the session exists. It is spent exactly once: {@link #register(SessionOptions)} turns it into a
 * registered session, and {@link #close()} gives it back if it was not. A failed registration gives
 * it back too, so a reservation used in try-with-resources never holds capacity past its block.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Of concurrent {@code register} and {@code close} calls, the first spends the
 * reservation and the others find it spent.
 */
public final class SessionReservation implements AutoCloseable {

  private final SessionTable table;
  private final Function<SessionOptions, AgentSession> factory;
  private final Principal owner;
  private final AtomicBoolean spent = new AtomicBoolean();

  SessionReservation(
      SessionTable table, Function<SessionOptions, AgentSession> factory, Principal owner) {
    this.table = table;
    this.factory = factory;
    this.owner = owner;
  }

  /**
   * Build the session through the registry's factory and register it under its session id, owned by
   * the principal that reserved it. If the factory fails or the id is already registered, the
   * reservation is given back and the session the factory built is closed.
   *
   * @param options the session's composition; non-null
   * @return the freshly-created, unstarted session
   * @throws NullPointerException if {@code options} is null or the factory returned null
   * @throws IllegalStateException if the reservation is already spent, or a session with the same
   *     id is already registered
   */
  public AgentSession register(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    if (!spent.compareAndSet(false, true)) {
      throw new IllegalStateException("reservation already spent");
    }
    var registered = false;
    try {
      var session = Objects.requireNonNull(factory.apply(options), "factory returned null session");
      if (!table.register(options.sessionId(), session, owner)) {
        session.close();
        throw new IllegalStateException("session id already registered: " + options.sessionId());
      }
      registered = true;
      return session;
    } finally {
      if (!registered) {
        table.release(owner);
      }
    }
  }

  /** Give the reservation back unless it was spent. Idempotent. */
  @Override
  public void close() {
    if (spent.compareAndSet(false, true)) {
      table.release(owner);
    }
  }
}
