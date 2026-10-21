/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.SessionLimits;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure decision function that maps a just-completed turn to an optional terminal {@link
 * ResultMessage}.
 *
 * <p>Called by the agent loop after every turn. The classifier inspects, in priority order:
 *
 * <ol>
 *   <li>Wall-clock ceiling — elapsed time exceeds {@code limits.maxWallClock()}. Checked
 *       <i>before</i> the cancellation branch because the wall-clock deadline scheduler implements
 *       itself by cancelling the session token; without this ordering the resulting terminal would
 *       be {@link ResultMessage.Cancelled} instead of the more informative {@link
 *       ResultMessage.ErrorMaxWallClock}.
 *   <li>Budget exhaustion — accumulated cost exceeds {@code limits.maxBudgetMicroUsd()}.
 *   <li>Cancellation — the session's {@link
 *       com.standardapplied.helios.core.runtime.CancellationToken CancellationToken} is signalled
 *       by a path other than wall-clock expiry (explicit {@code close()}, host-initiated cancel).
 *   <li>Refusal — the provider reported {@link FinishReason#CONTENT_FILTER} or {@link
 *       FinishReason#REFUSAL}.
 *   <li>Provider error — the provider reported {@link FinishReason#ERROR}.
 *   <li>Response truncation — the provider reported {@link FinishReason#LENGTH} (its
 *       max_output_tokens cap fired). Classifying this as terminal avoids the otherwise-silent
 *       re-issue loop where the model keeps hitting the same cap until {@code maxTurns} elapses.
 *   <li>Natural completion — the provider reported {@link FinishReason#STOP} and no further user
 *       messages are queued.
 *   <li>Turn ceiling — the turn would continue the loop ({@link FinishReason#TOOL_CALLS}, or {@link
 *       FinishReason#STOP} with messages queued) and the current turn index has reached {@code
 *       limits.maxTurns()}, so {@code maxTurns = N} admits N model turns.
 * </ol>
 *
 * <p>Any other outcome returns {@link Optional#empty()}, signalling the loop to continue.
 *
 * <p>Every {@link SerializedError} an error terminal carries has the messages of its chain redacted
 * against the session's secret registry.
 *
 * <h2>Thread-safety</h2>
 *
 * Holds only the thread-safe registry. Safe to share or instantiate per-call. The classifier reads
 * {@link SessionState} fields that are themselves thread-safe; the loop is the only writer, so the
 * read snapshot is coherent.
 */
public final class StopClassifier {

  private final SecretRegistry secrets;

  /**
   * Build a classifier for one session.
   *
   * @param secrets the session's secret registry, which error text is redacted against; non-null
   * @throws NullPointerException if {@code secrets} is null
   */
  public StopClassifier(SecretRegistry secrets) {
    this.secrets = Objects.requireNonNull(secrets, "secrets must not be null");
  }

  /**
   * Classify the turn outcome.
   *
   * @param state the session state at the moment of the call; non-null
   * @param limits the session limits in force; non-null
   * @param outcome the just-completed turn; non-null. Its assistant content is surfaced as the
   *     result string on {@link ResultMessage.Success} and as the refusal text on {@link
   *     ResultMessage.Refusal}; its stream error is carried through the cause chain via {@link
   *     SerializedError#of(Throwable)} on every error terminal; its metadata supplies the refusal
   *     category and explanation when the provider reported them
   * @param hasPendingMessages {@code true} if the steering queue still has user messages at the
   *     iteration boundary
   * @return a terminal {@code ResultMessage} when one applies, or empty to continue
   * @throws NullPointerException if any argument is null
   */
  public Optional<ResultMessage> classify(
      SessionState state, SessionLimits limits, TurnOutcome outcome, boolean hasPendingMessages) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    if (state.elapsed().compareTo(limits.maxWallClock()) > 0) {
      return Optional.of(
          new ResultMessage.ErrorMaxWallClock(
              state.sessionId(), state.totals().usage(), state.totals().cost(), state.elapsed()));
    }

    if (limits.maxBudgetMicroUsd().isPresent()
        && state.totals().cost().microUsd() > limits.maxBudgetMicroUsd().getAsLong()) {
      return Optional.of(
          new ResultMessage.ErrorMaxBudgetUsd(
              state.sessionId(),
              state.totals().cost().microUsd(),
              state.totals().usage(),
              state.totals().cost(),
              state.elapsed()));
    }

    if (state.cancellation().isCancelled()) {
      return Optional.of(
          new ResultMessage.Cancelled(
              state.sessionId(),
              state.cancellation().reason().orElseThrow(),
              state.totals().usage(),
              state.totals().cost(),
              state.elapsed()));
    }

    return byFinishReason(state, outcome, hasPendingMessages).or(() -> turnCeiling(state, limits));
  }

  /**
   * The terminal a turn's own ending implies, or empty when the turn would continue the loop: a
   * {@link FinishReason#TOOL_CALLS} turn, or a {@link FinishReason#STOP} turn with messages queued.
   */
  private Optional<ResultMessage> byFinishReason(
      SessionState state, TurnOutcome outcome, boolean hasPendingMessages) {
    var assistantContent = outcome.assistantContent();
    return switch (outcome.finishReason()) {
      case CONTENT_FILTER, REFUSAL -> Optional.of(buildRefusal(state, outcome));
      case ERROR ->
          Optional.of(
              buildErrorTerminal(
                  state, assistantContent, outcome.streamError(), outcome.streamAttempts()));
      case STOP ->
          hasPendingMessages
              ? Optional.empty()
              : Optional.of(
                  new ResultMessage.Success(
                      state.sessionId(),
                      assistantContent,
                      state.totals().usage(),
                      state.totals().cost(),
                      state.elapsed(),
                      state.totals().citations()));
      case LENGTH ->
          Optional.of(
              new ResultMessage.ErrorDuringExecution(
                  state.sessionId(),
                  redacted(
                      SerializedError.of(
                          "max-tokens",
                          "response truncated at provider max_output_tokens cap"
                              + (Strings.isBlank(assistantContent)
                                  ? ""
                                  : "; partial content: " + assistantContent))),
                  state.totals().usage(),
                  state.totals().cost(),
                  state.elapsed()));
      case TOOL_CALLS -> Optional.empty();
    };
  }

  /** {@link ResultMessage.ErrorMaxTurns} when continuing would start a turn past the limit. */
  private static Optional<ResultMessage> turnCeiling(SessionState state, SessionLimits limits) {
    if (state.currentTurnIndex() < limits.maxTurns()) {
      return Optional.empty();
    }
    return Optional.of(
        new ResultMessage.ErrorMaxTurns(
            state.sessionId(),
            Math.toIntExact(state.currentTurnIndex()),
            state.totals().usage(),
            state.totals().cost(),
            state.elapsed()));
  }

  /**
   * Build the terminal for a refused turn. The refusal text is the provider's explanation when it
   * gave one — any assistant text on such a turn is output cut short by the refusal — else the
   * assistant's own words, else a placeholder; the category is whatever the provider reported under
   * {@link Response#REFUSAL_CATEGORY_KEY}.
   */
  private static ResultMessage buildRefusal(SessionState state, TurnOutcome outcome) {
    var explanation = outcome.metadata().get(Response.REFUSAL_EXPLANATION_KEY);
    return new ResultMessage.Refusal(
        state.sessionId(),
        Strings.orDefault(
            explanation, Strings.orDefault(outcome.assistantContent(), "[refused without text]")),
        state.totals().usage(),
        state.totals().cost(),
        state.elapsed(),
        outcome.metadata().get(Response.REFUSAL_CATEGORY_KEY));
  }

  /**
   * Build the terminal for {@link FinishReason#ERROR}. Routes the throwable into one of three
   * shapes:
   *
   * <ul>
   *   <li>{@link TransientStreamException} ⇒ {@link ResultMessage.ErrorTransientStream} carrying
   *       the provider name, the attempt count, and the full {@link SerializedError} cause chain
   *       (kind = throwable class, message, stack trace, recursive {@code cause()}). Surfaces only
   *       after the loop's retry budget is exhausted — bounded retry happens upstream in {@link
   *       TurnRunner}.
   *   <li>Any other non-{@code null} {@code streamError} ⇒ {@link
   *       ResultMessage.ErrorDuringExecution} with {@link SerializedError#of(Throwable)} so the
   *       cause chain survives. Replaces the pre-fix opaque {@code SerializedError.of("Provider
   *       Error", message)} that dropped both class name and cause.
   *   <li>{@code null} {@code streamError} (legacy path: a turn that finished with {@code
   *       FinishReason.ERROR} but had no throwable recorded) ⇒ {@link
   *       ResultMessage.ErrorDuringExecution} with the assistant content (or a placeholder) as the
   *       message and no cause. Preserves behaviour for providers that haven't migrated to
   *       reporting errors via the subscriber's throwable channel.
   * </ul>
   */
  private ResultMessage buildErrorTerminal(
      SessionState state, String assistantContent, Throwable streamError, int streamAttempts) {
    if (streamError instanceof TransientStreamException tse) {
      return new ResultMessage.ErrorTransientStream(
          state.sessionId(),
          tse.providerName(),
          streamAttempts,
          redacted(SerializedError.of(tse)),
          state.totals().usage(),
          state.totals().cost(),
          state.elapsed());
    }
    if (streamError != null) {
      return new ResultMessage.ErrorDuringExecution(
          state.sessionId(),
          redacted(SerializedError.of(streamError)),
          state.totals().usage(),
          state.totals().cost(),
          state.elapsed());
    }
    return new ResultMessage.ErrorDuringExecution(
        state.sessionId(),
        redacted(
            SerializedError.of(
                "ProviderError",
                Strings.isBlank(assistantContent) ? "provider reported ERROR" : assistantContent)),
        state.totals().usage(),
        state.totals().cost(),
        state.elapsed());
  }

  private SerializedError redacted(SerializedError error) {
    return error.redactedBy(secrets.redactor());
  }
}
