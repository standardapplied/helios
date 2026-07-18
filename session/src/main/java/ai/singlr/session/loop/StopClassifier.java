/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.TransientStreamException;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SerializedError;
import ai.singlr.session.SessionLimits;
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
 *   <li>Cancellation — the session's {@link ai.singlr.core.runtime.CancellationToken
 *       CancellationToken} is signalled by a path other than wall-clock expiry (explicit {@code
 *       close()}, host-initiated cancel).
 *   <li>Turn ceiling — current turn index has reached {@code limits.maxTurns()}.
 *   <li>Refusal — the provider reported {@link FinishReason#CONTENT_FILTER}.
 *   <li>Provider error — the provider reported {@link FinishReason#ERROR}.
 *   <li>Response truncation — the provider reported {@link FinishReason#LENGTH} (its
 *       max_output_tokens cap fired). Classifying this as terminal avoids the otherwise-silent
 *       re-issue loop where the model keeps hitting the same cap until {@code maxTurns} elapses.
 *   <li>Natural completion — the provider reported {@link FinishReason#STOP} and no further user
 *       messages are queued.
 * </ol>
 *
 * <p>Any other outcome returns {@link Optional#empty()}, signalling the loop to continue (currently
 * only {@link FinishReason#TOOL_CALLS}).
 *
 * <h2>Thread-safety</h2>
 *
 * Stateless. Safe to share or instantiate per-call. The classifier reads {@link SessionState}
 * fields that are themselves thread-safe; the loop is the only writer, so the read snapshot is
 * coherent.
 */
public final class StopClassifier {

  /** Default constructor. */
  public StopClassifier() {}

  /**
   * Classify the turn outcome.
   *
   * @param state the session state at the moment of the call; non-null
   * @param limits the session limits in force; non-null
   * @param finishReason the provider-reported finish reason for the just-completed turn; non-null
   * @param assistantContent the assistant text the turn produced; non-null but may be empty.
   *     Surfaced as the result string on {@link ResultMessage.Success} and as the refusal text on
   *     {@link ResultMessage.Refusal}; empty content forces a placeholder refusal string
   * @param streamError the throwable that terminated the final stream attempt of the just-
   *     completed turn, or {@code null} when no error was recorded; carried through the cause chain
   *     via {@link SerializedError#of(Throwable)} on every error terminal
   * @param streamAttempts the total number of stream attempts the loop made for this turn; {@code
   *     >= 1}. Surfaced on {@link ResultMessage.ErrorTransientStream}
   * @param hasPendingMessages {@code true} if the steering queue still has user messages at the
   *     iteration boundary
   * @return a terminal {@code ResultMessage} when one applies, or empty to continue
   * @throws NullPointerException if any non-nullable argument is null
   * @throws IllegalArgumentException if {@code streamAttempts < 1}
   */
  public Optional<ResultMessage> classify(
      SessionState state,
      SessionLimits limits,
      FinishReason finishReason,
      String assistantContent,
      Throwable streamError,
      int streamAttempts,
      boolean hasPendingMessages) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(finishReason, "finishReason must not be null");
    Objects.requireNonNull(assistantContent, "assistantContent must not be null");
    if (streamAttempts < 1) {
      throw new IllegalArgumentException("streamAttempts must be >= 1, got " + streamAttempts);
    }

    if (state.elapsed().compareTo(limits.maxWallClock()) > 0) {
      return Optional.of(
          new ResultMessage.ErrorMaxWallClock(
              state.sessionId(), state.usage(), state.cost(), state.elapsed()));
    }

    if (limits.maxBudgetMicroUsd().isPresent()
        && state.cost().microUsd() > limits.maxBudgetMicroUsd().getAsLong()) {
      return Optional.of(
          new ResultMessage.ErrorMaxBudgetUsd(
              state.sessionId(),
              state.cost().microUsd(),
              state.usage(),
              state.cost(),
              state.elapsed()));
    }

    if (state.cancellation().isCancelled()) {
      return Optional.of(
          new ResultMessage.Cancelled(
              state.sessionId(),
              state.cancellation().reason().orElseThrow(),
              state.usage(),
              state.cost(),
              state.elapsed()));
    }

    if (state.currentTurnIndex() >= limits.maxTurns()) {
      return Optional.of(
          new ResultMessage.ErrorMaxTurns(
              state.sessionId(),
              Math.toIntExact(state.currentTurnIndex()),
              state.usage(),
              state.cost(),
              state.elapsed()));
    }

    return switch (finishReason) {
      case CONTENT_FILTER, REFUSAL ->
          Optional.of(
              new ResultMessage.Refusal(
                  state.sessionId(),
                  Strings.isBlank(assistantContent) ? "[refused without text]" : assistantContent,
                  state.usage(),
                  state.cost(),
                  state.elapsed()));
      case ERROR ->
          Optional.of(buildErrorTerminal(state, assistantContent, streamError, streamAttempts));
      case STOP ->
          hasPendingMessages
              ? Optional.empty()
              : Optional.of(
                  new ResultMessage.Success(
                      state.sessionId(),
                      assistantContent,
                      state.usage(),
                      state.cost(),
                      state.elapsed(),
                      state.citations()));
      case LENGTH ->
          Optional.of(
              new ResultMessage.ErrorDuringExecution(
                  state.sessionId(),
                  SerializedError.of(
                      "max-tokens",
                      "response truncated at provider max_output_tokens cap"
                          + (Strings.isBlank(assistantContent)
                              ? ""
                              : "; partial content: " + assistantContent)),
                  state.usage(),
                  state.cost(),
                  state.elapsed()));
      case TOOL_CALLS -> Optional.empty();
    };
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
  private static ResultMessage buildErrorTerminal(
      SessionState state, String assistantContent, Throwable streamError, int streamAttempts) {
    if (streamError instanceof TransientStreamException tse) {
      return new ResultMessage.ErrorTransientStream(
          state.sessionId(),
          tse.providerName(),
          streamAttempts,
          SerializedError.of(tse),
          state.usage(),
          state.cost(),
          state.elapsed());
    }
    if (streamError != null) {
      return new ResultMessage.ErrorDuringExecution(
          state.sessionId(),
          SerializedError.of(streamError),
          state.usage(),
          state.cost(),
          state.elapsed());
    }
    return new ResultMessage.ErrorDuringExecution(
        state.sessionId(),
        SerializedError.of(
            "ProviderError",
            Strings.isBlank(assistantContent) ? "provider reported ERROR" : assistantContent),
        state.usage(),
        state.cost(),
        state.elapsed());
  }
}
