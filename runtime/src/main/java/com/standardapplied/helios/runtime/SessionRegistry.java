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
 * route handlers look sessions up by id to dispatch send / interrupt / events / close. {@link
 * #create(SessionOptions, Principal)} builds a session through the factory wired at construction
 * (typically {@code AgentSession::create}) and registers it.
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
 * Two caps are hard limits: sessions per principal and sessions across the registry. Each counts
 * what is held: sessions being created, registered sessions, terminal ones included, and
 * unregistered sessions whose {@link AgentSession#result()} has not completed. Capacity is taken
 * atomically with every other create and before the options or the session are built. When a cap is
 * reached, the oldest terminal session of the same principal is closed to make room. A session of
 * another principal is never evicted, and the principal's own cap is checked before the registry's,
 * so a refusal is the same whatever other principals hold. Otherwise the create is refused with a
 * {@link SessionCapacityException} naming whose cap was reached. A session that has not ended is
 * never evicted; {@link com.standardapplied.helios.session.SessionLimits#maxWallClock()} bounds how
 * long one runs.
 *
 * <p>Defaults, both configurable through {@link #newBuilder()}: 256 sessions across the registry
 * and 16 per principal. They also bound concurrent model calls, since a session waits for each of
 * its own.
 *
 * <h2>Retention</h2>
 *
 * A session not running stays registered so a late subscriber still sees its {@code LoopEnded}
 * event and its result, until its owner closes it or {@link #sweep()} finds it not running for the
 * retention (default 15 minutes): a terminal session measured from its end, one never started (see
 * {@link AgentSession#isStarted()}) from its registration. A started session that has not ended is
 * never swept. {@link RuntimeServer} sweeps on a schedule; a deployer embedding the registry
 * otherwise calls {@link #sweep()} from its own scheduler. A first message that arrives in the
 * instant the sweep closes a never-started session can be answered {@code 202} and then never run:
 * the session ends {@code Cancelled}, which its stream and result report.
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
 * capacity at the same moment; one still at work keeps counting until its {@link
 * AgentSession#result()} completes, since closing it only asks its loop to stop. So a client
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
            new SessionQuota(builder.maxSessions, builder.maxSessionsPerPrincipal),
            builder.clock,
            builder.retention);
  }

  /**
   * Take capacity for one session of {@code owner}, closing the owner's oldest terminal session if
   * a cap is full. Nothing of the session exists yet; the reservation is spent by registering it or
   * given back by closing it.
   *
   * @throws NullPointerException if {@code owner} is null
   * @throws SessionCapacityException if a cap is reached and no terminal session of {@code owner}
   *     can make room
   */
  SessionReservation reserve(Principal owner) {
    Objects.requireNonNull(owner, "owner must not be null");
    table.reserve(owner).ifPresent(SessionRegistry::closeUnregistered);
    return new SessionReservation(table, factory, owner);
  }

  /**
   * Take capacity for, build and register a session owned by {@code owner} under its session id.
   * Capacity is taken before the factory runs and given back if it fails. A duplicate id is
   * rejected before anything is registered, so an existing session's owner is never replaced.
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
   * Unregister and close every session not running for the retention: a terminal session measured
   * from its end, one never started from its registration. A started session that has not ended is
   * never swept.
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
    private int maxSessions = 256;
    private int maxSessionsPerPrincipal = 16;
    private Duration retention = Duration.ofMinutes(15);

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

    /** Override the time source that measures retention; tests advance one by hand. */
    Builder withClock(InstantSource clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    /**
     * Cap sessions held across the registry, terminal ones included. Default 256.
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
     * Cap sessions one principal holds, terminal ones included. Default 16.
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
     * How long a session not running, terminal or never started, stays registered before {@link
     * #sweep()} closes it. Default 15 minutes.
     *
     * @param retention positive duration
     * @return this builder
     * @throws NullPointerException if {@code retention} is null
     * @throws IllegalArgumentException if {@code retention} is not positive
     */
    public Builder withRetention(Duration retention) {
      Objects.requireNonNull(retention, "retention must not be null");
      if (!retention.isPositive()) {
        throw new IllegalArgumentException("retention must be positive, got " + retention);
      }
      this.retention = retention;
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
  }
}
