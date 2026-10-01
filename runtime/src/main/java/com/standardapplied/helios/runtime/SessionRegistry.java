/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import ai.singlr.session.AgentSession;
import ai.singlr.session.SessionOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * In-memory registry of live sessions. The HTTP service holds one per process; route handlers
 * lookup sessions by id to dispatch send / interrupt / events / close.
 *
 * <p>Sessions are created via {@link #create(SessionOptions)} — a {@link Function} factory wired at
 * construction (typically {@code AgentSession::create}) builds the impl. The factory is injectable
 * so tests can substitute a stub session.
 *
 * <p>Sessions remain in the registry until {@link #close(String)} is called, even after they reach
 * a terminal {@link ai.singlr.session.ResultMessage ResultMessage} — keeping them around lets late
 * SSE subscribers fetch the final {@code LoopEnded} event after termination, and lets the {@code
 * DELETE /sessions/{id}} route be the explicit cleanup boundary.
 *
 * <h2>Retention</h2>
 *
 * The registry can keep terminal sessions indefinitely; for long-running services that creates a
 * slow leak. Two opt-in eviction surfaces address this:
 *
 * <ul>
 *   <li>{@link #purgeTerminalOlderThan(Duration)} — sweep terminal sessions older than the supplied
 *       age. Live sessions are never touched. Best called periodically (every minute or so) from
 *       the deployer's scheduler.
 *   <li>{@code SessionRegistry.newBuilder().withMaxSessions(int)} — cap on registered sessions.
 *       When {@link #create(SessionOptions)} would push the count over the cap, the registry evicts
 *       the oldest terminal session first; if none is available, the create call throws {@link
 *       IllegalStateException}. Live sessions are never evicted.
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. All routes share one registry; concurrent create / get / close are common. Backed by
 * {@link ConcurrentHashMap}; {@link #create(SessionOptions)} rejects duplicate ids. The cap check
 * is best-effort under contention — under a flood of concurrent creates the count may briefly
 * exceed the cap before evictions catch up; the cap is an SLA hint, not a hard barrier.
 */
public final class SessionRegistry {

  private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
  private final Function<SessionOptions, AgentSession> factory;
  private final Clock clock;
  private final int maxSessions;

  /**
   * Registry that constructs sessions via {@link AgentSession#create(SessionOptions)} with system
   * clock and no cap.
   *
   * @return a fresh registry
   */
  public static SessionRegistry inMemory() {
    return newBuilder().build();
  }

  /**
   * Registry that constructs sessions via a custom factory, system clock, no cap. Intended for
   * tests; production sessions use {@link #inMemory()} or {@link #newBuilder()}.
   *
   * @param factory non-null function mapping options to a fresh session
   * @return a fresh registry
   * @throws NullPointerException if {@code factory} is null
   */
  public static SessionRegistry withFactory(Function<SessionOptions, AgentSession> factory) {
    return newBuilder().withFactory(factory).build();
  }

  /**
   * Start building a registry. Set any of factory / clock / maxSessions; defaults are {@code
   * AgentSession::create}, {@link Clock#systemUTC()}, and no cap.
   *
   * @return a fresh builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  private SessionRegistry(
      Function<SessionOptions, AgentSession> factory, Clock clock, int maxSessions) {
    this.factory = Objects.requireNonNull(factory, "factory must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    if (maxSessions <= 0) {
      throw new IllegalArgumentException("maxSessions must be positive, got " + maxSessions);
    }
    this.maxSessions = maxSessions;
  }

  /**
   * Create a new session from the given options and register it under its session id.
   *
   * @param options the composition record; non-null
   * @return the freshly-created, unstarted session
   * @throws NullPointerException if {@code options} is null
   * @throws IllegalStateException if a session with the same id is already registered, or if the
   *     registry is at its configured {@code maxSessions} cap and no terminal session is available
   *     to evict
   */
  public AgentSession create(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    if (sessions.size() >= maxSessions && !tryEvictOldestTerminal()) {
      throw new IllegalStateException(
          "registry at capacity "
              + maxSessions
              + " and no terminal sessions are available to evict");
    }
    var session = factory.apply(options);
    Objects.requireNonNull(session, "factory returned null session");
    var entry = new SessionEntry(session, new AtomicReference<>());
    var prev = sessions.putIfAbsent(options.sessionId(), entry);
    if (prev != null) {
      session.close();
      throw new IllegalStateException("session id already registered: " + options.sessionId());
    }
    session.result().whenComplete((r, t) -> entry.terminatedAt().set(clock.instant()));
    return session;
  }

  /**
   * Look up a registered session by id.
   *
   * @param sessionId non-null id
   * @return the session if present
   * @throws NullPointerException if {@code sessionId} is null
   */
  public Optional<AgentSession> get(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    var entry = sessions.get(sessionId);
    return entry == null ? Optional.empty() : Optional.of(entry.session());
  }

  /**
   * Close and unregister the session. If no session is registered under {@code sessionId} this is a
   * no-op.
   *
   * @param sessionId non-null id
   * @return {@code true} if a session was found and closed; {@code false} if no session was
   *     registered
   * @throws NullPointerException if {@code sessionId} is null
   */
  public boolean close(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    var entry = sessions.remove(sessionId);
    if (entry == null) {
      return false;
    }
    entry.session().close();
    return true;
  }

  /**
   * Snapshot of currently-registered session ids. Stable point-in-time view; mutations after this
   * call are not reflected.
   *
   * @return defensive snapshot
   */
  public Collection<String> sessionIds() {
    return Set.copyOf(sessions.keySet());
  }

  /**
   * Number of registered sessions.
   *
   * @return non-negative count
   */
  public int size() {
    return sessions.size();
  }

  /** Close and unregister every session. Idempotent. */
  public void closeAll() {
    for (var id : Set.copyOf(sessions.keySet())) {
      close(id);
    }
  }

  /**
   * Sweep every terminal session whose termination instant is older than {@code age} relative to
   * the registry's {@link Clock}. Live sessions are not touched, even if the registry has held them
   * far longer than {@code age}. Returns the count of sessions closed + unregistered.
   *
   * <p>A session is "terminal" once its {@link AgentSession#result()} future has completed — the
   * registry captures the wall-clock instant of completion when the future settles, and this method
   * compares that instant against {@code now - age}.
   *
   * @param age non-null, non-negative; sessions terminated at-or-before {@code now - age} are
   *     purged
   * @return number of sessions purged
   * @throws NullPointerException if {@code age} is null
   * @throws IllegalArgumentException if {@code age} is negative
   */
  public int purgeTerminalOlderThan(Duration age) {
    Objects.requireNonNull(age, "age must not be null");
    if (age.isNegative()) {
      throw new IllegalArgumentException("age must be non-negative, got " + age);
    }
    var cutoff = clock.instant().minus(age);
    int purged = 0;
    for (var entry : sessions.entrySet()) {
      var terminated = entry.getValue().terminatedAt().get();
      if (terminated != null && !terminated.isAfter(cutoff)) {
        if (close(entry.getKey())) {
          purged++;
        }
      }
    }
    return purged;
  }

  /**
   * Evict the single oldest terminal session, if any. Returns {@code true} when an entry was
   * removed and closed; {@code false} when no terminal session was available.
   */
  private boolean tryEvictOldestTerminal() {
    var oldestId =
        sessions.entrySet().stream()
            .filter(e -> e.getValue().terminatedAt().get() != null)
            .min(
                Comparator.comparing(
                    e -> e.getValue().terminatedAt().get(), Comparator.naturalOrder()))
            .map(Map.Entry::getKey);
    return oldestId.map(this::close).orElse(false);
  }

  /** Per-session bookkeeping: the session itself + the instant its result-future completed. */
  private record SessionEntry(AgentSession session, AtomicReference<Instant> terminatedAt) {}

  /** Fluent builder for {@link SessionRegistry}. */
  public static final class Builder {

    private Function<SessionOptions, AgentSession> factory = AgentSession::create;
    private Clock clock = Clock.systemUTC();
    private int maxSessions = Integer.MAX_VALUE;

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
     * Override the clock used to stamp terminal sessions. Tests use a fixed clock for deterministic
     * eviction; production keeps {@link Clock#systemUTC()}.
     *
     * @param clock non-null clock
     * @return this builder
     * @throws NullPointerException if {@code clock} is null
     */
    public Builder withClock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    /**
     * Cap registered sessions. When {@link #create(SessionOptions)} would push the count over this
     * number, the registry evicts the oldest terminal session first; if no terminal session is
     * available, the create call throws.
     *
     * @param maxSessions positive cap
     * @return this builder
     * @throws IllegalArgumentException if {@code maxSessions} is not positive
     */
    public Builder withMaxSessions(int maxSessions) {
      if (maxSessions <= 0) {
        throw new IllegalArgumentException("maxSessions must be positive, got " + maxSessions);
      }
      this.maxSessions = maxSessions;
      return this;
    }

    /**
     * Build the immutable registry.
     *
     * @return a fresh registry
     */
    public SessionRegistry build() {
      return new SessionRegistry(factory, clock, maxSessions);
    }
  }
}
