/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.session.ResultMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable per-session state owned by the agent loop.
 *
 * <p>Carries the conversation history, the running turn index, the accumulated usage / cost / wall
 * clock, the cancellation token, and the first-wins terminal {@link ResultMessage}. Built by {@code
 * AgentSessionImpl} at session start and threaded through every loop collaborator (the loop itself,
 * the turn runner, the stop classifier, the hook runner). One instance per session.
 *
 * <h2>Thread-safety</h2>
 *
 * The agent loop runs on a single virtual thread that is the only writer; observer reads from HTTP
 * endpoints, subscribers, and tests are common. Every field is either {@code final}, an {@code
 * Atomic*}, or a {@code CopyOnWriteArrayList}, so concurrent observation is safe. There is no
 * external synchronisation — readers see a consistent snapshot per-field, not necessarily a
 * cross-field snapshot. Cross-field invariants (e.g. {@code terminal.isPresent} implies {@code
 * currentTurnIndex} is stable) are maintained because only the loop thread writes.
 */
public final class SessionState {

  private final String sessionId;
  private final CancellationToken cancellation;
  private final Clock clock;
  private final Instant startedAt;
  private final CopyOnWriteArrayList<Message> history = new CopyOnWriteArrayList<>();
  private final AtomicLong turnIndex = new AtomicLong(0);
  private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.of(0, 0));
  private final AtomicReference<CostEstimate> cost = new AtomicReference<>(CostEstimate.zero());
  private final AtomicReference<Optional<ResultMessage>> terminal =
      new AtomicReference<>(Optional.empty());
  private final AtomicBoolean contextWarningFired = new AtomicBoolean(false);

  /**
   * Build a fresh state for a new session. The state starts at turn {@code 0} with empty history.
   *
   * @param sessionId stable session identifier; non-blank
   * @param cancellation the session's cancellation token; non-null
   * @param clock the clock that drives {@link #elapsed()}; non-null. Tests pass a fixed clock; real
   *     deployments pass {@link Clock#systemUTC()}
   * @throws NullPointerException if {@code sessionId}, {@code cancellation}, or {@code clock} is
   *     null
   * @throws IllegalArgumentException if {@code sessionId} is blank
   */
  public SessionState(String sessionId, CancellationToken cancellation, Clock clock) {
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
   * Append a message to the conversation history.
   *
   * @param message the message to append; non-null
   * @throws NullPointerException if {@code message} is null
   */
  public void appendMessage(Message message) {
    Objects.requireNonNull(message, "message must not be null");
    history.add(message);
  }

  /**
   * Immutable snapshot of the conversation history at the moment of the call. The returned list
   * never reflects subsequent appends — a fresh call returns a fresh snapshot.
   *
   * @return a defensive immutable copy of the history
   */
  public List<Message> historySnapshot() {
    return List.copyOf(history);
  }

  /**
   * Replace the entire history with the given list. Used by context compaction and by a {@code
   * PreModelTurnHook} returning {@code MutateInput} carrying a fresh history. The supplied list is
   * defensively copied so subsequent caller mutations don't leak in.
   *
   * @param newHistory the replacement history; non-null. Contents are defensively copied
   * @throws NullPointerException if {@code newHistory} or any element is null
   */
  public void replaceHistory(List<Message> newHistory) {
    Objects.requireNonNull(newHistory, "newHistory must not be null");
    var copy = new ArrayList<Message>(newHistory.size());
    for (var m : newHistory) {
      copy.add(Objects.requireNonNull(m, "newHistory must not contain null"));
    }
    history.clear();
    history.addAll(copy);
  }

  /**
   * Add the given usage delta to the running totals.
   *
   * @param delta usage to accumulate; non-null
   * @throws NullPointerException if {@code delta} is null
   */
  public void accumulateUsage(Usage delta) {
    Objects.requireNonNull(delta, "delta must not be null");
    usage.updateAndGet(
        prev ->
            new Usage(
                prev.inputTokens() + delta.inputTokens(),
                prev.outputTokens() + delta.outputTokens(),
                prev.cacheCreationInputTokens() + delta.cacheCreationInputTokens(),
                prev.cacheReadInputTokens() + delta.cacheReadInputTokens(),
                prev.totalTokens() + delta.totalTokens()));
  }

  /**
   * The accumulated usage across every model call in this session.
   *
   * @return the running usage; non-null
   */
  public Usage usage() {
    return usage.get();
  }

  /**
   * Add the given cost delta to the running total.
   *
   * @param delta cost to accumulate; non-null
   * @throws NullPointerException if {@code delta} is null
   */
  public void accumulateCost(CostEstimate delta) {
    Objects.requireNonNull(delta, "delta must not be null");
    cost.updateAndGet(prev -> prev.plus(delta));
  }

  /**
   * The accumulated cost across this session.
   *
   * @return the running cost; non-null
   */
  public CostEstimate cost() {
    return cost.get();
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

  /**
   * Mark the {@code ContextWarning} watermark as fired for this session. The flag is sticky until
   * {@link #resetContextWarningFlag()} is called — once the watermark crosses {@code 0.85}, the
   * loop should not re-emit a warning on every subsequent turn that stays above the threshold.
   *
   * @return {@code true} if this call flipped the flag (i.e. the caller should emit the event);
   *     {@code false} if it was already set
   */
  public boolean tryFireContextWarning() {
    return contextWarningFired.compareAndSet(false, true);
  }

  /**
   * Clear the {@code ContextWarning}-fired flag. Called after a successful compaction so a future
   * climb back through {@code 0.85} re-fires the watermark. Day 1 (watermark-only) never calls
   * this; Day 2 (compaction) wires it after {@code ContextEdited}.
   */
  public void resetContextWarningFlag() {
    contextWarningFired.set(false);
  }

  /**
   * Whether the {@code ContextWarning} watermark has been emitted in this session.
   *
   * @return {@code true} if emitted at least once
   */
  public boolean contextWarningFired() {
    return contextWarningFired.get();
  }
}
