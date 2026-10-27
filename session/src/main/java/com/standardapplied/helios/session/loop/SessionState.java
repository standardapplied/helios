/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.ResultMessage;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable state of one run of a session, owned by the agent loop.
 *
 * <p>Carries the run's id, its turn index, its start on the wall clock, its cancellation token and
 * its first-wins terminal {@link ResultMessage}, and is composed of the run's {@link SessionTotals}
 * and the session's {@link ConversationHistory} and {@link SessionEnding}, which every run of the
 * session shares. Threaded through every loop collaborator (the loop itself, the turn runner, the
 * stop classifier, the hook runner). {@link #SessionState(SessionState, CancellationToken)} builds
 * the state of the session's next run.
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
  private final String runId;
  private final CancellationToken cancellation;
  private final InstantSource clock;
  private final ConversationHistory history;
  private final SessionTotals totals;
  private final SessionEnding ending;
  private CompletableFuture<Void> cancelling;
  private final AtomicLong turnIndex = new AtomicLong(0);
  private final AtomicReference<Optional<ResultMessage>> terminal =
      new AtomicReference<>(Optional.empty());

  /**
   * Build the state of a new session, which is also the state of a run on the session's own token.
   * The state starts at turn {@code 0} with empty history.
   *
   * @param sessionId stable session identifier; non-blank
   * @param cancellation the session's cancellation token; non-null
   * @param clock the time source that drives {@link SessionTotals#elapsed()}; non-null. Tests pass
   *     one they advance by hand; real deployments pass {@link java.time.Clock#systemUTC()}
   * @throws NullPointerException if {@code sessionId}, {@code cancellation}, or {@code clock} is
   *     null
   * @throws IllegalArgumentException if {@code sessionId} is blank
   */
  public SessionState(String sessionId, CancellationToken cancellation, InstantSource clock) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    this(
        sessionId,
        cancellation,
        clock,
        new ConversationHistory(),
        new SessionEnding(sessionId, cancellation));
  }

  private SessionState(
      String sessionId,
      CancellationToken cancellation,
      InstantSource clock,
      ConversationHistory history,
      SessionEnding ending) {
    this.sessionId = sessionId;
    this.runId = "run-" + Ids.newId();
    this.cancellation = cancellation;
    this.clock = clock;
    this.totals = new SessionTotals(clock);
    this.history = history;
    this.ending = ending;
  }

  /**
   * Build the state of the next run of {@code session}'s session: the same session id, clock,
   * history and ending; a fresh run id, its own token, turn {@code 0}, empty totals, no terminal,
   * started now.
   *
   * @param session a state of the session; non-null
   * @param runToken the run's cancellation token; non-null
   * @throws NullPointerException if an argument is null
   */
  public SessionState(SessionState session, CancellationToken runToken) {
    this(
        Objects.requireNonNull(session, "session must not be null").sessionId,
        Objects.requireNonNull(runToken, "runToken must not be null"),
        session.clock,
        session.history,
        session.ending);
  }

  /**
   * The run id, {@code "run-"} followed by a fresh id.
   *
   * @return non-blank run id
   */
  public String runId() {
    return runId;
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
   * The run's cancellation token, which the run's model stream, tool calls, hooks and questions
   * observe.
   *
   * @return non-null token
   */
  public CancellationToken cancellation() {
    return cancellation;
  }

  /**
   * Cancel the run's token, the one way a run is cancelled: by an interrupt, its wall-clock
   * deadline, the session's cancellation and its own end. Its callbacks run on a thread of their
   * own, each {@link Error} they throw recorded by the session's {@link SessionEnding#fail}. The
   * first call's {@code reason} wins, and every call returns the same future.
   *
   * @param reason why the run is cancelled; non-blank
   * @return a future that completes once every callback of the token has run
   */
  public synchronized CompletableFuture<Void> cancel(String reason) {
    if (cancelling == null) {
      cancelling = cancellation.cancelAsync(reason, ending::fail);
    }
    return cancelling;
  }

  /**
   * The run's current turn index (starts at 0, incremented by {@link #beginTurn()}, so the run's
   * first turn is 1).
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
   * The session's conversation history, which every run shares.
   *
   * @return the history; never null
   */
  public ConversationHistory history() {
    return history;
  }

  /**
   * The run's usage, cost, citation and duration totals.
   *
   * @return the totals; never null
   */
  public SessionTotals totals() {
    return totals;
  }

  /**
   * How the session ends early: its one cancellation path and the sink of its host {@link Error}s.
   *
   * @return the session's ending
   */
  public SessionEnding ending() {
    return ending;
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
   * @return the result, or empty if the run is still running
   */
  public Optional<ResultMessage> terminal() {
    return terminal.get();
  }
}
