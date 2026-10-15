/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SessionOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory registry of the sessions one process serves. The HTTP service holds one per process;
 * route handlers look sessions up by id to dispatch send / interrupt / events / close.
 *
 * <p>A session is admitted in two steps: {@link #reserve(Principal)} takes capacity for it before
 * anything of the session exists, and {@link SessionReservation#register(SessionOptions)} builds it
 * through the factory wired at construction (typically {@code AgentSession::create}) and registers
 * it. {@link #create(SessionOptions, Principal)} does both at once.
 *
 * <h2>Ownership</h2>
 *
 * Every session is registered with the {@link Principal} that created it, and that owner never
 * changes. {@link #find(String, Principal)} and {@link #close(String, Principal)} reach a session
 * only for an equal principal; for anyone else they answer exactly as for an id that was never
 * registered, so a caller cannot learn whether another owner's id exists.
 *
 * <h2>Admission</h2>
 *
 * Four caps are hard limits, checked and taken atomically with every other create, and count open
 * reservations as well as registered sessions: sessions held and active sessions, each across the
 * registry and per principal. A session is active until its {@link AgentSession#result()}
 * completes; after that it is terminal, and it is held until it is closed or swept. When only a cap
 * on sessions held refuses a reservation, the oldest terminal session of the same principal is
 * closed to make room. A session of another principal is never evicted, so a refusal is the same
 * whatever other principals hold. Otherwise the reservation is refused with a {@link
 * SessionCapacityException} naming whose cap was reached. Active sessions are never evicted; {@link
 * com.standardapplied.helios.session.SessionLimits#maxWallClock()} bounds how long one runs.
 *
 * <p>Defaults, all configurable through {@link #newBuilder()}: 1024 sessions and 256 active
 * sessions across the registry; 64 sessions and 16 active sessions per principal. The active cap
 * also bounds concurrent model calls, since a session waits for each of its own.
 *
 * <h2>Retention</h2>
 *
 * A terminal session stays registered so a late subscriber still sees its {@code LoopEnded} event
 * and its result, until its owner closes it or {@link #sweep()} finds it held past the terminal
 * retention (default 15 minutes). The sweep also closes a session never started (see {@link
 * AgentSession#isStarted()}) within the idle timeout of its registration (default 5 minutes). Both
 * are measured on the registry's {@link InstantSource}. {@link RuntimeServer} sweeps on a schedule;
 * a deployer embedding the registry otherwise calls {@link #sweep()} from its own scheduler.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. All routes share one registry; concurrent create / find / close are common. A
 * session the registry unregisters is closed outside its lock, and a failure to close it is logged,
 * never thrown.
 *
 * <h2>Release</h2>
 *
 * Unregistering a session removes it from lookup at once. A terminal session gives back its
 * capacity at the same moment; one still at work keeps counting as a held, active session until its
 * {@link AgentSession#result()} completes, since closing it only asks its loop to stop. So a client
 * deleting running sessions and creating new ones never runs more sessions than the caps.
 */
public final class SessionRegistry {

  private static final Logger LOGGER = Logger.getLogger(SessionRegistry.class.getName());

  private final Function<SessionOptions, AgentSession> factory;
  private final SessionTable table;

  /**
   * Registry that constructs sessions via {@link AgentSession#create(SessionOptions)} with the
   * system clock and the default caps and retention.
   *
   * @return a fresh registry
   */
  public static SessionRegistry inMemory() {
    return newBuilder().build();
  }

  /**
   * Registry that constructs sessions via a custom factory, with the system clock and the default
   * caps and retention. Intended for tests; production sessions use {@link #inMemory()} or {@link
   * #newBuilder()}.
   *
   * @param factory non-null function mapping options to a fresh session
   * @return a fresh registry
   * @throws NullPointerException if {@code factory} is null
   */
  public static SessionRegistry withFactory(Function<SessionOptions, AgentSession> factory) {
    return newBuilder().withFactory(factory).build();
  }

  /**
   * Start building a registry.
   *
   * @return a fresh builder holding the defaults
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  private SessionRegistry(Builder builder) {
    this.factory = builder.factory;
    this.table =
        new SessionTable(
            new SessionQuota(
                builder.maxSessions,
                builder.maxActiveSessions,
                builder.maxSessionsPerPrincipal,
                builder.maxActiveSessionsPerPrincipal),
            builder.clock,
            builder.terminalRetention,
            builder.idleTimeout);
  }

  /**
   * Take capacity for one session of {@code owner}, closing the owner's oldest terminal session if
   * only a cap on sessions held is full. Nothing of the session exists yet; spend the reservation
   * with {@link SessionReservation#register(SessionOptions)} or give it back with {@link
   * SessionReservation#close()}.
   *
   * @param owner the principal the session will belong to; non-null
   * @return the reservation
   * @throws NullPointerException if {@code owner} is null
   * @throws SessionCapacityException if a cap is reached and no terminal session of {@code owner}
   *     can make room
   */
  public SessionReservation reserve(Principal owner) {
    Objects.requireNonNull(owner, "owner must not be null");
    table.reserve(owner).ifPresent(SessionRegistry::closeUnregistered);
    return new SessionReservation(table, factory, owner);
  }

  /**
   * Reserve, build and register a session owned by {@code owner} under its session id. A duplicate
   * id is rejected before anything is registered, so an existing session's owner is never replaced.
   *
   * @param options the composition record; non-null
   * @param owner the principal the session belongs to; non-null
   * @return the freshly-created, unstarted session
   * @throws NullPointerException if {@code options} or {@code owner} is null, or the factory
   *     returned null
   * @throws SessionCapacityException if a cap is reached and no terminal session of {@code owner}
   *     can make room
   * @throws IllegalStateException if a session with the same id is already registered
   */
  public AgentSession create(SessionOptions options, Principal owner) {
    Objects.requireNonNull(options, "options must not be null");
    try (var reservation = reserve(owner)) {
      return reservation.register(options);
    }
  }

  /**
   * Look up a session {@code requester} owns.
   *
   * @param sessionId non-null id
   * @param requester the principal asking; non-null
   * @return the session when it is registered and owned by {@code requester}; empty otherwise
   * @throws NullPointerException if either argument is null
   */
  public Optional<AgentSession> find(String sessionId, Principal requester) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(requester, "requester must not be null");
    return table.find(sessionId, requester);
  }

  /**
   * Unregister and close a session {@code requester} owns; its capacity is given back once its
   * result completes. A session that is not registered, or belongs to another principal, is left
   * untouched.
   *
   * @param sessionId non-null id
   * @param requester the principal asking; non-null
   * @return {@code true} if the session was found, owned by {@code requester} and unregistered
   * @throws NullPointerException if either argument is null
   */
  public boolean close(String sessionId, Principal requester) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(requester, "requester must not be null");
    var removed = table.remove(sessionId, requester);
    removed.ifPresent(SessionRegistry::closeUnregistered);
    return removed.isPresent();
  }

  /**
   * Number of registered sessions, terminal ones included; open reservations are not counted.
   *
   * @return non-negative count
   */
  public int size() {
    return table.size();
  }

  /** Unregister and close every session. Idempotent. */
  public void closeAll() {
    table.removeAll().forEach(SessionRegistry::closeUnregistered);
  }

  /**
   * Unregister and close every terminal session held past the terminal retention and every session
   * never started within the idle timeout of its registration, as of the registry's clock. A
   * started session that has not ended is never swept.
   *
   * @return the number of sessions swept
   */
  public int sweep() {
    var expired = table.removeExpired();
    expired.forEach(SessionRegistry::closeUnregistered);
    return expired.size();
  }

  private static void closeUnregistered(AgentSession session) {
    try {
      session.close();
    } catch (Throwable e) {
      LOGGER.log(
          Level.WARNING, "closing unregistered session " + session.sessionId() + " failed", e);
    }
  }

  /** Fluent builder for {@link SessionRegistry}. */
  public static final class Builder {

    private Function<SessionOptions, AgentSession> factory = AgentSession::create;
    private InstantSource clock = Clock.systemUTC();
    private int maxSessions = 1024;
    private int maxActiveSessions = 256;
    private int maxSessionsPerPrincipal = 64;
    private int maxActiveSessionsPerPrincipal = 16;
    private Duration terminalRetention = Duration.ofMinutes(15);
    private Duration idleTimeout = Duration.ofMinutes(5);

    private Builder() {}

    /**
     * Override the session factory. Production code keeps the default; tests supply a stub.
     *
     * @param factory non-null factory
     * @return this builder
     * @throws NullPointerException if {@code factory} is null
     */
    public Builder withFactory(Function<SessionOptions, AgentSession> factory) {
      this.factory = Objects.requireNonNull(factory, "factory must not be null");
      return this;
    }

    /**
     * Override the time source that stamps registration and termination and measures retention and
     * idleness. Tests advance one by hand; production keeps {@link Clock#systemUTC()}.
     *
     * @param clock non-null time source
     * @return this builder
     * @throws NullPointerException if {@code clock} is null
     */
    public Builder withClock(InstantSource clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    /**
     * Cap sessions held across the registry, terminal ones and open reservations included. Default
     * 1024.
     *
     * @param maxSessions positive cap
     * @return this builder
     * @throws IllegalArgumentException if {@code maxSessions} is not positive
     */
    public Builder withMaxSessions(int maxSessions) {
      this.maxSessions = positive("maxSessions", maxSessions);
      return this;
    }

    /**
     * Cap active sessions across the registry: open reservations and sessions that have not reached
     * their terminal result. Default 256.
     *
     * @param maxActiveSessions positive cap
     * @return this builder
     * @throws IllegalArgumentException if {@code maxActiveSessions} is not positive
     */
    public Builder withMaxActiveSessions(int maxActiveSessions) {
      this.maxActiveSessions = positive("maxActiveSessions", maxActiveSessions);
      return this;
    }

    /**
     * Cap sessions one principal holds, terminal ones and open reservations included. Default 64.
     *
     * @param maxSessionsPerPrincipal positive cap
     * @return this builder
     * @throws IllegalArgumentException if {@code maxSessionsPerPrincipal} is not positive
     */
    public Builder withMaxSessionsPerPrincipal(int maxSessionsPerPrincipal) {
      this.maxSessionsPerPrincipal = positive("maxSessionsPerPrincipal", maxSessionsPerPrincipal);
      return this;
    }

    /**
     * Cap active sessions one principal holds. Default 16.
     *
     * @param maxActiveSessionsPerPrincipal positive cap
     * @return this builder
     * @throws IllegalArgumentException if {@code maxActiveSessionsPerPrincipal} is not positive
     */
    public Builder withMaxActiveSessionsPerPrincipal(int maxActiveSessionsPerPrincipal) {
      this.maxActiveSessionsPerPrincipal =
          positive("maxActiveSessionsPerPrincipal", maxActiveSessionsPerPrincipal);
      return this;
    }

    /**
     * How long a terminal session stays registered before {@link #sweep()} closes it. Default 15
     * minutes.
     *
     * @param terminalRetention positive duration
     * @return this builder
     * @throws NullPointerException if {@code terminalRetention} is null
     * @throws IllegalArgumentException if {@code terminalRetention} is not positive
     */
    public Builder withTerminalRetention(Duration terminalRetention) {
      this.terminalRetention = positive("terminalRetention", terminalRetention);
      return this;
    }

    /**
     * How long a registered session may go unstarted before {@link #sweep()} closes it. Default 5
     * minutes.
     *
     * @param idleTimeout positive duration
     * @return this builder
     * @throws NullPointerException if {@code idleTimeout} is null
     * @throws IllegalArgumentException if {@code idleTimeout} is not positive
     */
    public Builder withIdleTimeout(Duration idleTimeout) {
      this.idleTimeout = positive("idleTimeout", idleTimeout);
      return this;
    }

    /**
     * Build the registry.
     *
     * @return a fresh, empty registry
     */
    public SessionRegistry build() {
      return new SessionRegistry(this);
    }

    private static int positive(String name, int value) {
      if (value <= 0) {
        throw new IllegalArgumentException(name + " must be positive, got " + value);
      }
      return value;
    }

    private static Duration positive(String name, Duration value) {
      Objects.requireNonNull(value, name + " must not be null");
      if (!value.isPositive()) {
        throw new IllegalArgumentException(name + " must be positive, got " + value);
      }
      return value;
    }
  }
}
