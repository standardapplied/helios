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

/**
 * In-memory registry of the sessions one process serves. The HTTP service holds one per process;
 * route handlers look sessions up by id to dispatch send / interrupt / events / close. {@link
 * #create(SessionOptions, Principal)} opens a session through the factory wired at construction
 * (typically {@code AgentSession::open}) and registers it. A session is <em>active</em> while a run
 * the HTTP service started on it, through the registry's {@code send}, is in progress, and
 * <em>idle</em> otherwise: before its first run and between runs.
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
 * what is held: sessions being created, registered sessions, idle ones included, and unregistered
 * sessions whose run has not completed. Capacity is taken atomically with every other create and
 * before the options or the session are built. When a cap is reached, the session of the same
 * principal idle the longest is closed to make room. A session of another principal is never
 * evicted, and the principal's own cap is checked before the registry's, so a refusal is the same
 * whatever other principals hold. Otherwise the create is refused with a {@link
 * SessionCapacityException} naming whose cap was reached. An active session is never evicted;
 * {@link com.standardapplied.helios.session.SessionLimits#maxWallClock()} bounds how long one run
 * takes.
 *
 * <p>Defaults, both configurable through {@link #newBuilder()}: 256 sessions across the registry
 * and 16 per principal. They also bound concurrent model calls, since a session waits for each of
 * its own.
 *
 * <h2>Retention</h2>
 *
 * An idle session stays registered, so its owner can start its next run and read its last result,
 * until its owner closes it or {@link #sweep()} finds it idle for the retention (default 15
 * minutes), measured from the end of its last run, or from its registration if it never ran. An
 * active session is never swept. {@link RuntimeServer} sweeps on a schedule; a deployer embedding
 * the registry otherwise calls {@link #sweep()} from its own scheduler. A message that arrives in
 * the instant the sweep closes an idle session can start a run that ends {@code Cancelled} at once,
 * which its stream and result report.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. All routes share one registry; concurrent create / find / close are common. A
 * session the registry unregisters is closed outside its lock, and a failure to close it is logged,
 * never thrown.
 *
 * <h2>Release</h2>
 *
 * Unregistering a session removes it from lookup at once. It keeps counting until it has stopped:
 * an idle session until its close returned, which ends its execution provider, and an active one
 * until its run completed too, which closing it brings about within its stop grace. A session
 * evicted to make room is closed before the new session is built. So a client deleting sessions and
 * creating new ones never runs more sessions than the caps.
 */
public final class SessionRegistry {

  private final Function<SessionOptions, AgentSession> factory;
  private final SessionTable table;

  /**
   * Registry that opens sessions via {@link AgentSession#open(SessionOptions)} with the system
   * clock and the default caps and retention.
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
   * Take capacity for one session of {@code owner}, closing the owner's session idle the longest if
   * a cap is full. Nothing of the session exists yet; the reservation is spent by registering it or
   * given back by closing it.
   *
   * @throws NullPointerException if {@code owner} is null
   * @throws SessionCapacityException if a cap is reached and no idle session of {@code owner} can
   *     make room
   */
  SessionReservation reserve(Principal owner) {
    Objects.requireNonNull(owner, "owner must not be null");
    table.reserve(owner).ifPresent(RegisteredSession::close);
    return new SessionReservation(table, factory, owner);
  }

  /**
   * Take capacity for, build and register a session owned by {@code owner} under its session id.
   * Capacity is taken before the factory runs and given back if it fails. A duplicate id is
   * rejected before anything is registered, so an existing session's owner is never replaced.
   *
   * @param options the composition record; non-null
   * @param owner the principal the session belongs to; non-null
   * @return the freshly-opened session, with no run
   * @throws NullPointerException if {@code options} or {@code owner} is null, or the factory
   *     returned null
   * @throws SessionCapacityException if a cap is reached and no idle session of {@code owner} can
   *     make room
   * @throws IllegalStateException if a session with the same id is already registered
   * @throws com.standardapplied.helios.session.SessionUnavailableException if the session's
   *     execution provider did not accept it; the capacity is given back
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
    return lookup(sessionId, requester).map(RegisteredSession::session);
  }

  /**
   * Look up a session {@code requester} owns as registered: {@link RegisteredSession#send} starts
   * the runs that keep it active, and {@link RegisteredSession#latest} is its latest run.
   *
   * @return the registered session when it is registered and owned by {@code requester}
   */
  Optional<RegisteredSession> lookup(String sessionId, Principal requester) {
    return table.lookup(sessionId, requester);
  }

  /**
   * Unregister and close a session {@code requester} owns; its capacity is given back once it has
   * stopped. A session that is not registered, or belongs to another principal, is left untouched.
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
    removed.ifPresent(RegisteredSession::close);
    return removed.isPresent();
  }

  /**
   * Number of registered sessions, idle ones included; open reservations are not counted.
   *
   * @return non-negative count
   */
  public int size() {
    return table.size();
  }

  /** Unregister and close every session. Idempotent. */
  public void closeAll() {
    table.removeAll().forEach(RegisteredSession::close);
  }

  /**
   * Unregister and close every session idle for the retention: measured from the end of its last
   * run, or from its registration if it never ran. An active session is never swept.
   *
   * @return the number of sessions swept
   */
  public int sweep() {
    var expired = table.removeExpired();
    expired.forEach(RegisteredSession::close);
    return expired.size();
  }

  /** Fluent builder for {@link SessionRegistry}. */
  public static final class Builder {

    private Function<SessionOptions, AgentSession> factory = AgentSession::open;
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
     * Cap sessions held across the registry, idle ones included. Default 256.
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
     * Cap sessions one principal holds, idle ones included. Default 16.
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
     * How long an idle session stays registered before {@link #sweep()} closes it. Default 15
     * minutes.
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
