/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Citation;
import ai.singlr.core.model.Response.Usage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal result of an agent session.
 *
 * <p>Sealed: every termination path produces exactly one of the eight subtypes below. Callers
 * branch on the subtype via {@code switch} pattern matching; they never parse assistant text to
 * discover whether the session succeeded.
 *
 * <p>Every subtype carries the same four common fields ({@code sessionId}, {@code usage}, {@code
 * cost}, {@code duration}) plus subtype-specific detail. Common-field validation lives in {@link
 * #validateCommon(String, Usage, CostEstimate, Duration)} so the eight record bodies stay tight and
 * the validation contract is testable in one place.
 */
public sealed interface ResultMessage
    permits ResultMessage.Success,
        ResultMessage.ErrorMaxTurns,
        ResultMessage.ErrorMaxBudgetUsd,
        ResultMessage.ErrorMaxWallClock,
        ResultMessage.ErrorDuringExecution,
        ResultMessage.ErrorTransientStream,
        ResultMessage.ErrorProviderUnavailable,
        ResultMessage.Refusal,
        ResultMessage.Cancelled {

  /**
   * The stable identifier of the session that produced this result.
   *
   * @return non-blank session id
   */
  String sessionId();

  /**
   * Token usage accumulated across every model call in the session.
   *
   * @return non-null usage
   */
  Usage usage();

  /**
   * Estimated dollar cost accumulated across the session.
   *
   * @return non-null cost estimate
   */
  CostEstimate cost();

  /**
   * Wall-clock duration of the session, from creation to terminal state.
   *
   * @return non-null, non-negative duration
   */
  Duration duration();

  /**
   * Grounding citations accumulated across the session (e.g. Google Search / web grounding),
   * deduplicated and in document order. Carried only by {@link Success} — the provenance of a
   * delivered answer; every other terminal returns an empty list. Mirrors the per-turn {@link
   * QueryEvent.AssistantCitations} events on the streaming surface.
   *
   * @return the citations; non-null, immutable, empty when the run did no grounding or did not
   *     succeed
   */
  default List<Citation> citations() {
    return List.of();
  }

  /**
   * Return a redacted copy with any carried {@link SerializedError} stripped of stack-trace frames.
   * Returns {@code this} unchanged when the terminal carries no error or the carried error is
   * already free of frames. Used at trust boundaries (HTTP responses, SSE serialisation) so
   * library-internal class names and file:line numbers do not leak to clients.
   *
   * <p>Default implementation: no-op. Overridden by {@link ErrorDuringExecution}.
   *
   * @return a stack-trace-free copy, or {@code this} when no redaction was needed
   */
  default ResultMessage withoutStackTraces() {
    return this;
  }

  /**
   * Validate the four fields every subtype shares. Each record's canonical constructor calls this
   * before applying its own field-specific checks.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code duration} is negative
   */
  static void validateCommon(String sessionId, Usage usage, CostEstimate cost, Duration duration) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(cost, "cost must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative, got " + duration);
    }
  }

  /**
   * The session terminated naturally with a final assistant answer.
   *
   * @param sessionId the session's id
   * @param result the final answer text
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   * @param citations grounding citations accumulated across the run; non-null, defensively copied,
   *     may be empty
   */
  record Success(
      String sessionId,
      String result,
      Usage usage,
      CostEstimate cost,
      Duration duration,
      List<Citation> citations)
      implements ResultMessage {

    public Success {
      validateCommon(sessionId, usage, cost, duration);
      Objects.requireNonNull(result, "result must not be null");
      Objects.requireNonNull(citations, "citations must not be null");
      citations = List.copyOf(citations);
    }

    /** Convenience for a success with no grounding citations. */
    public Success(
        String sessionId, String result, Usage usage, CostEstimate cost, Duration duration) {
      this(sessionId, result, usage, cost, duration, List.of());
    }
  }

  /**
   * The session terminated because the {@code maxTurns} ceiling was reached without the model
   * producing a stop.
   *
   * @param sessionId the session's id
   * @param turnsUsed turns consumed before termination
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   */
  record ErrorMaxTurns(
      String sessionId, int turnsUsed, Usage usage, CostEstimate cost, Duration duration)
      implements ResultMessage {

    public ErrorMaxTurns {
      validateCommon(sessionId, usage, cost, duration);
      if (turnsUsed < 0) {
        throw new IllegalArgumentException("turnsUsed must be non-negative, got " + turnsUsed);
      }
    }
  }

  /**
   * The session terminated because the configured USD budget was exhausted.
   *
   * @param sessionId the session's id
   * @param microUsdSpent the amount spent before termination, in micro-USD
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   */
  record ErrorMaxBudgetUsd(
      String sessionId, long microUsdSpent, Usage usage, CostEstimate cost, Duration duration)
      implements ResultMessage {

    public ErrorMaxBudgetUsd {
      validateCommon(sessionId, usage, cost, duration);
      if (microUsdSpent < 0L) {
        throw new IllegalArgumentException(
            "microUsdSpent must be non-negative, got " + microUsdSpent);
      }
    }
  }

  /**
   * The session terminated because the configured wall-clock ceiling was exceeded. The {@code
   * duration} field carries the elapsed wall clock (which is &ge; the configured ceiling);
   * consumers read the configured ceiling from their session settings if needed.
   *
   * @param sessionId the session's id
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock at termination
   */
  record ErrorMaxWallClock(String sessionId, Usage usage, CostEstimate cost, Duration duration)
      implements ResultMessage {

    public ErrorMaxWallClock {
      validateCommon(sessionId, usage, cost, duration);
    }
  }

  /**
   * The session terminated because of an unrecoverable error (provider error, hook abort, tool
   * crash, etc).
   *
   * @param sessionId the session's id
   * @param error the serialized error captured at termination
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   */
  record ErrorDuringExecution(
      String sessionId, SerializedError error, Usage usage, CostEstimate cost, Duration duration)
      implements ResultMessage {

    public ErrorDuringExecution {
      validateCommon(sessionId, usage, cost, duration);
      Objects.requireNonNull(error, "error must not be null");
    }

    @Override
    public ResultMessage withoutStackTraces() {
      var redacted = error.withoutStackTrace();
      return redacted == error
          ? this
          : new ErrorDuringExecution(sessionId, redacted, usage, cost, duration);
    }
  }

  /**
   * The session terminated because a model stream was interrupted by a recoverable transport
   * failure (socket reset, half-closed connection, idle-read timeout on a provider-classified
   * retryable status such as 408 / 429 / 5xx) and the configured {@link
   * ai.singlr.session.SessionLimits#streamRetryPolicy() streamRetryPolicy} budget was exhausted
   * without recovery.
   *
   * <p>Distinguished from {@link ErrorDuringExecution} so callers can pattern-match the failure
   * mode and decide independently whether to surface a "retry your request later" message, retry
   * the entire session, or surface the failure as opaque. The {@link #error} field carries the
   * originating throwable's full cause chain (kind = throwable class name, message, stack trace,
   * recursive {@code cause()}) via {@link SerializedError#of(Throwable)}, so deployers can
   * distinguish a TCP reset from an idle timeout from a half-closed framing error without string-
   * matching the {@code message} field.
   *
   * @param sessionId the session's id
   * @param providerName the provider's short identifier carried from {@link
   *     ai.singlr.core.model.TransientStreamException#providerName()}; non-blank
   * @param attemptsMade total number of stream attempts the loop made — always {@code >= 1}; equals
   *     {@code 1 + retriesConsumed}. A value of {@code 1} means retry was disabled
   * @param error the serialised throwable that caused the final attempt to fail, with cause chain
   *     intact
   * @param usage accumulated token usage (sums every attempt's billed tokens)
   * @param cost accumulated cost (sums every attempt's spend)
   * @param duration elapsed wall clock
   */
  record ErrorTransientStream(
      String sessionId,
      String providerName,
      int attemptsMade,
      SerializedError error,
      Usage usage,
      CostEstimate cost,
      Duration duration)
      implements ResultMessage {

    public ErrorTransientStream {
      validateCommon(sessionId, usage, cost, duration);
      Objects.requireNonNull(providerName, "providerName must not be null");
      if (Strings.isBlank(providerName)) {
        throw new IllegalArgumentException("providerName must not be blank");
      }
      if (attemptsMade < 1) {
        throw new IllegalArgumentException("attemptsMade must be >= 1, got " + attemptsMade);
      }
      Objects.requireNonNull(error, "error must not be null");
    }

    @Override
    public ResultMessage withoutStackTraces() {
      var redacted = error.withoutStackTrace();
      return redacted == error
          ? this
          : new ErrorTransientStream(
              sessionId, providerName, attemptsMade, redacted, usage, cost, duration);
    }
  }

  /**
   * The session terminated before the agent loop began because an {@link
   * ai.singlr.session.execution.ExecutionProvider}'s {@code onSessionStart} returned a {@link
   * ai.singlr.session.execution.SessionStartOutcome.Refuse Refuse} — typically pool saturation,
   * per-session auth failure, in-flight provider shutdown, or a caught exception during sandbox
   * spawn.
   *
   * @param sessionId the session's id
   * @param providerName a short, stable name identifying which provider refused (e.g. the provider
   *     class's simple name); non-blank
   * @param reason the human-readable refusal reason carried from {@code
   *     SessionStartOutcome.Refuse}; non-blank
   * @param cause the underlying error serialised from {@code SessionStartOutcome.Refuse#cause()},
   *     or {@code null} when the refusal is a policy decision (saturation, auth) rather than a
   *     caught exception. Deployers inspecting failures programmatically read the typed kind /
   *     message / stack trace and walk the cause chain via {@link SerializedError#cause()}
   * @param usage accumulated token usage (always zero at this point)
   * @param cost accumulated cost (always zero at this point)
   * @param duration elapsed wall clock (typically tiny)
   */
  record ErrorProviderUnavailable(
      String sessionId,
      String providerName,
      String reason,
      SerializedError cause,
      Usage usage,
      CostEstimate cost,
      Duration duration)
      implements ResultMessage {

    public ErrorProviderUnavailable {
      validateCommon(sessionId, usage, cost, duration);
      Objects.requireNonNull(providerName, "providerName must not be null");
      if (Strings.isBlank(providerName)) {
        throw new IllegalArgumentException("providerName must not be blank");
      }
      Objects.requireNonNull(reason, "reason must not be null");
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }

    /**
     * The underlying serialised error, if any.
     *
     * @return an optional view of {@link #cause()}; empty for policy-driven refusals
     */
    public Optional<SerializedError> causeOpt() {
      return Optional.ofNullable(cause);
    }
  }

  /**
   * The model refused to answer (safety filter or model-side policy).
   *
   * @param sessionId the session's id
   * @param refusalText the refusal text the model returned
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   */
  record Refusal(
      String sessionId, String refusalText, Usage usage, CostEstimate cost, Duration duration)
      implements ResultMessage {

    public Refusal {
      validateCommon(sessionId, usage, cost, duration);
      Objects.requireNonNull(refusalText, "refusalText must not be null");
      if (Strings.isBlank(refusalText)) {
        throw new IllegalArgumentException("refusalText must not be blank");
      }
    }
  }

  /**
   * The session was cancelled via {@code AgentSession.interrupt(...)} or wall-clock cancellation.
   *
   * @param sessionId the session's id
   * @param reason the reason recorded on the cancellation token
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock at cancellation
   */
  record Cancelled(
      String sessionId, String reason, Usage usage, CostEstimate cost, Duration duration)
      implements ResultMessage {

    public Cancelled {
      validateCommon(sessionId, usage, cost, duration);
      Objects.requireNonNull(reason, "reason must not be null");
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }
}
