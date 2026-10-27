/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.Response.Usage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal result of one run of an agent session.
 *
 * <p>Sealed: every way a run ends produces exactly one of the eight subtypes below. Callers branch
 * on the subtype via {@code switch} pattern matching; they never parse assistant text to discover
 * whether the run succeeded.
 *
 * <p>Every subtype carries the same five common fields ({@code sessionId}, {@code runId}, {@code
 * usage}, {@code cost}, {@code duration}) plus subtype-specific detail. Common-field validation
 * lives in {@link #validateCommon(String, String, Usage, CostEstimate, Duration)} so the eight
 * record bodies stay tight and the validation contract is testable in one place.
 */
public sealed interface ResultMessage
    permits ResultMessage.Success,
        ResultMessage.ErrorMaxTurns,
        ResultMessage.ErrorMaxBudgetUsd,
        ResultMessage.ErrorMaxWallClock,
        ResultMessage.ErrorDuringExecution,
        ResultMessage.ErrorTransientStream,
        ResultMessage.Refusal,
        ResultMessage.Cancelled {

  /**
   * The stable identifier of the session that produced this result.
   *
   * @return non-blank session id
   */
  String sessionId();

  /**
   * The identifier of the run this result ends.
   *
   * @return non-blank run id
   */
  String runId();

  /**
   * Token usage accumulated across every model call in the run.
   *
   * @return non-null usage
   */
  Usage usage();

  /**
   * Estimated dollar cost accumulated across the run.
   *
   * @return non-null cost estimate
   */
  CostEstimate cost();

  /**
   * Wall-clock duration of the run, from its start to its terminal state.
   *
   * @return non-null, non-negative duration
   */
  Duration duration();

  /**
   * Grounding citations accumulated across the run (e.g. Google Search / web grounding),
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
   * Validate the five fields every subtype shares. Each record's canonical constructor calls this
   * before applying its own field-specific checks.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code sessionId} or {@code runId} is blank or {@code
   *     duration} is negative
   */
  static void validateCommon(
      String sessionId, String runId, Usage usage, CostEstimate cost, Duration duration) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(runId, "runId must not be null");
    if (Strings.isBlank(runId)) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(cost, "cost must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative, got " + duration);
    }
  }

  /**
   * The run ended naturally with a final assistant answer.
   *
   * @param sessionId the session's id
   * @param runId the run's id
   * @param result the final answer text
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   * @param citations grounding citations accumulated across the run; non-null, defensively copied,
   *     may be empty
   */
  record Success(
      String sessionId,
      String runId,
      String result,
      Usage usage,
      CostEstimate cost,
      Duration duration,
      List<Citation> citations)
      implements ResultMessage {

    public Success {
      validateCommon(sessionId, runId, usage, cost, duration);
      Objects.requireNonNull(result, "result must not be null");
      Objects.requireNonNull(citations, "citations must not be null");
      citations = List.copyOf(citations);
    }
  }

  /**
   * The run ended because it needed a turn past the {@code maxTurns} ceiling: its last allowed turn
   * would have continued the loop (tool calls, or a message queued for another turn).
   *
   * @param sessionId the session's id
   * @param runId the run's id
   * @param turnsUsed turns consumed before termination
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   */
  record ErrorMaxTurns(
      String sessionId,
      String runId,
      int turnsUsed,
      Usage usage,
      CostEstimate cost,
      Duration duration)
      implements ResultMessage {

    public ErrorMaxTurns {
      validateCommon(sessionId, runId, usage, cost, duration);
      if (turnsUsed < 0) {
        throw new IllegalArgumentException("turnsUsed must be non-negative, got " + turnsUsed);
      }
    }
  }

  /**
   * The run ended because the configured USD budget was exhausted.
   *
   * @param sessionId the session's id
   * @param runId the run's id
   * @param microUsdSpent the amount spent before termination, in micro-USD
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   */
  record ErrorMaxBudgetUsd(
      String sessionId,
      String runId,
      long microUsdSpent,
      Usage usage,
      CostEstimate cost,
      Duration duration)
      implements ResultMessage {

    public ErrorMaxBudgetUsd {
      validateCommon(sessionId, runId, usage, cost, duration);
      if (microUsdSpent < 0L) {
        throw new IllegalArgumentException(
            "microUsdSpent must be non-negative, got " + microUsdSpent);
      }
    }
  }

  /**
   * The run ended because the configured wall-clock ceiling was exceeded. The {@code duration}
   * field carries the elapsed wall clock (which is &ge; the configured ceiling); consumers read the
   * configured ceiling from their session settings if needed.
   *
   * @param sessionId the session's id
   * @param runId the run's id
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock at termination
   */
  record ErrorMaxWallClock(
      String sessionId, String runId, Usage usage, CostEstimate cost, Duration duration)
      implements ResultMessage {

    public ErrorMaxWallClock {
      validateCommon(sessionId, runId, usage, cost, duration);
    }
  }

  /**
   * The run ended because of an unrecoverable error (provider error, hook abort, tool crash, etc).
   *
   * @param sessionId the session's id
   * @param runId the run's id
   * @param error the serialized error captured at termination
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   */
  record ErrorDuringExecution(
      String sessionId,
      String runId,
      SerializedError error,
      Usage usage,
      CostEstimate cost,
      Duration duration)
      implements ResultMessage {

    public ErrorDuringExecution {
      validateCommon(sessionId, runId, usage, cost, duration);
      Objects.requireNonNull(error, "error must not be null");
    }

    @Override
    public ResultMessage withoutStackTraces() {
      var redacted = error.withoutStackTrace();
      return redacted == error
          ? this
          : new ErrorDuringExecution(sessionId, runId, redacted, usage, cost, duration);
    }
  }

  /**
   * The run ended because a model stream failed with a {@link
   * com.standardapplied.helios.core.model.TransientStreamException} (a transport failure such as a
   * socket reset or a half-closed connection, a 408, 429 or 5xx answer to the stream request, or an
   * error event the provider marks retryable mid-stream) and the configured {@link
   * com.standardapplied.helios.session.SessionLimits#streamRetryPolicy() streamRetryPolicy} budget
   * was exhausted without recovery.
   *
   * <p>Distinguished from {@link ErrorDuringExecution} so callers can pattern-match the failure
   * mode and decide independently whether to surface a "retry your request later" message, retry
   * the entire session, or surface the failure as opaque. The {@link #error} field carries the
   * originating throwable's full cause chain (kind = throwable class name, message, stack trace,
   * recursive {@code cause()}) via {@link SerializedError#of(Throwable)}, so deployers can
   * distinguish a TCP reset from an idle timeout from a half-closed framing error without string-
   * matching the {@code message} field. For a retryable status its {@code cause()} is the
   * provider's exception, whose message starts {@code API error (status <n>)}.
   *
   * @param sessionId the session's id
   * @param runId the run's id
   * @param providerName the provider's short identifier carried from {@link
   *     com.standardapplied.helios.core.model.TransientStreamException#providerName()}; non-blank
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
      String runId,
      String providerName,
      int attemptsMade,
      SerializedError error,
      Usage usage,
      CostEstimate cost,
      Duration duration)
      implements ResultMessage {

    public ErrorTransientStream {
      validateCommon(sessionId, runId, usage, cost, duration);
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
              sessionId, runId, providerName, attemptsMade, redacted, usage, cost, duration);
    }
  }

  /**
   * The model refused to answer (safety filter or model-side policy).
   *
   * @param sessionId the session's id
   * @param runId the run's id
   * @param refusalText the provider's explanation of the refusal when it gave one, otherwise the
   *     text the model returned
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock
   * @param category the provider-reported policy area behind the refusal (e.g. Anthropic's {@code
   *     cyber}, {@code bio}, {@code frontier_llm}, {@code reasoning_extraction}, {@code
   *     general_harms}), or {@code null} when the provider names none. An open set — branch on the
   *     values you know and treat the rest as opaque
   */
  record Refusal(
      String sessionId,
      String runId,
      String refusalText,
      Usage usage,
      CostEstimate cost,
      Duration duration,
      String category)
      implements ResultMessage {

    public Refusal {
      validateCommon(sessionId, runId, usage, cost, duration);
      Objects.requireNonNull(refusalText, "refusalText must not be null");
      if (Strings.isBlank(refusalText)) {
        throw new IllegalArgumentException("refusalText must not be blank");
      }
    }

    /**
     * The provider-reported policy area, if any.
     *
     * @return an optional view of {@link #category()}; empty when the provider named none
     */
    public Optional<String> categoryOpt() {
      return Optional.ofNullable(category);
    }
  }

  /**
   * The run was cancelled: by {@code AgentSession.interrupt()}, the session's close or a host
   * failure.
   *
   * @param sessionId the session's id
   * @param runId the run's id
   * @param reason the reason recorded on the cancellation token
   * @param usage accumulated token usage
   * @param cost accumulated cost
   * @param duration elapsed wall clock at cancellation
   */
  record Cancelled(
      String sessionId,
      String runId,
      String reason,
      Usage usage,
      CostEstimate cost,
      Duration duration)
      implements ResultMessage {

    public Cancelled {
      validateCommon(sessionId, runId, usage, cost, duration);
      Objects.requireNonNull(reason, "reason must not be null");
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }
}
