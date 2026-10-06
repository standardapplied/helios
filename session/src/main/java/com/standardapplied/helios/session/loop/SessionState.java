/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.ResultMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable per-session state owned by the agent loop.
 *
 * <p>Carries the running turn index, the wall clock, the cancellation token and the first-wins
 * terminal {@link ResultMessage}, and is composed of the {@link ConversationHistory}, the {@link
 * SessionTotals} and the context watermark flag. Built by {@code AgentSessionImpl} at session start
 * and threaded through every loop collaborator (the loop itself, the turn runner, the stop
 * classifier, the hook runner). One instance per session.
 *
 * <h2>Thread-safety</h2>
 *
 * The agent loop runs on a single virtual thread that is the only writer; observer reads from HTTP
 * endpoints, subscribers, and tests are common. Every field is either {@code final} or an {@code
 * Atomic*}, and each part is safe for concurrent observation. There is no external synchronisation
 * — readers see a consistent snapshot per-field, not necessarily a cross-field snapshot.
 * Cross-field invariants (e.g. {@code terminal.isPresent} implies {@code currentTurnIndex} is
 * stable) are maintained because only the loop thread writes.
 */
public final class SessionState {

  private final String sessionId;
  private final CancellationToken cancellation;
  private final InstantSource clock;
  private final Instant startedAt;
  private final ConversationHistory history = new ConversationHistory();
  private final SessionTotals totals = new SessionTotals();
  private final ContextWatermark contextWatermark = new ContextWatermark();
  private final AtomicLong turnIndex = new AtomicLong(0);
  private final AtomicReference<Optional<ResultMessage>> terminal =
      new AtomicReference<>(Optional.empty());

  /**
   * Build a fresh state for a new session. The state starts at turn {@code 0} with empty history.
   *
   * @param sessionId stable session identifier; non-blank
   * @param cancellation the session's cancellation token; non-null
   * @param clock the time source that drives {@link #elapsed()}; non-null. Tests pass one they
   *     advance by hand; real deployments pass {@link java.time.Clock#systemUTC()}
   * @throws NullPointerException if {@code sessionId}, {@code cancellation}, or {@code clock} is
   *     null
   * @throws IllegalArgumentException if {@code sessionId} is blank
   */
  public SessionState(String sessionId, CancellationToken cancellation, InstantSource clock) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    this.sessionId = sessionId;
    this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.startedAt = clock.instant();
  }

  /**
   * The session id.
   *
   * @return non-blank session id
   */
  public String sessionId() {
    return sessionId;
  }

  /**
   * The session's cancellation token.
   *
   * @return non-null token
   */
  public CancellationToken cancellation() {
    return cancellation;
  }

  /**
   * The current turn index (0-based; starts at 0, incremented by {@link #beginTurn()}).
   *
   * @return non-negative turn index
   */
  public long currentTurnIndex() {
    return turnIndex.get();
  }

  /**
   * Advance to the next turn and return its index. Called by the loop at iteration boundary.
   *
   * @return the new turn index
   */
  public long beginTurn() {
    return turnIndex.incrementAndGet();
  }

  /**
   * The conversation history.
   *
   * @return the history; never null
   */
  public ConversationHistory history() {
    return history;
  }

  /**
   * The running usage, cost and citation totals.
   *
   * @return the totals; never null
   */
  public SessionTotals totals() {
    return totals;
  }

  ContextWatermark contextWatermark() {
    return contextWatermark;
  }

  /**
   * The wall-clock duration since the state was constructed.
   *
   * @return a non-negative duration
   */
  public Duration elapsed() {
    return Duration.between(startedAt, clock.instant());
  }

  /**
   * The instant at which the state was constructed.
   *
   * @return non-null instant
   */
  public Instant startedAt() {
    return startedAt;
  }

  /**
   * Record the terminal result. First call wins; subsequent calls are no-ops and the first terminal
   * value is preserved.
   *
   * @param result the terminal result message; non-null
   * @return {@code true} if this call set the terminal; {@code false} if it was already set
   * @throws NullPointerException if {@code result} is null
   */
  public boolean setTerminal(ResultMessage result) {
    Objects.requireNonNull(result, "result must not be null");
    return terminal.compareAndSet(Optional.empty(), Optional.of(result));
  }

  /**
   * Whether {@link #setTerminal(ResultMessage)} has been called.
   *
   * @return {@code true} if terminal
   */
  public boolean isTerminal() {
    return terminal.get().isPresent();
  }

  /**
   * The terminal result, if recorded.
   *
   * @return the result, or empty if the session is still running
   */
  public Optional<ResultMessage> terminal() {
    return terminal.get();
  }
}
