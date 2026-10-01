/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Response.Usage;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of one model turn, produced by {@link TurnRunner#runTurn(SessionState,
 * ai.singlr.session.SessionLimits)} and consumed by {@link StopClassifier#classify(SessionState,
 * ai.singlr.session.SessionLimits, FinishReason, String, Throwable, int, boolean)}.
 *
 * <p>{@code assistantContent} is the fully-assembled assistant text accumulated from every {@link
 * ai.singlr.core.model.ModelChunk.TextDelta TextDelta} chunk during the turn — never null, possibly
 * empty (a tool-call-only turn produces empty content). {@code usage} is the {@link
 * ai.singlr.core.model.ModelChunk.MessageStop MessageStop} usage from the same turn — the
 * authoritative final tally for the turn. {@code metadata} carries provider-round-trip data (Gemini
 * thought signatures, Anthropic citation pointers, …) lifted off the {@code MessageStop} chunk; the
 * agent loop stores it on the assistant message so it survives into the next turn's follow-up
 * request.
 *
 * <p>{@code streamError} carries the throwable the subscriber recorded when the turn ended with
 * {@link FinishReason#ERROR} — including its full cause chain — so {@link StopClassifier} can
 * surface {@code SerializedError.of(streamError)} instead of collapsing the failure to a bare
 * message string. {@code null} on every non-error turn.
 *
 * <p>{@code streamAttempts} is the total number of {@code model.chatStream(...)} attempts the loop
 * made for this turn before producing the recorded outcome. Always {@code >= 1}; a value of {@code
 * 1} means no retry occurred (either the first attempt succeeded or retry is disabled).
 *
 * @param finishReason parsed finish reason from the {@code MessageStop} chunk; non-null
 * @param assistantContent assembled assistant text; non-null, possibly empty
 * @param usage final usage at message end; non-null
 * @param metadata provider-specific assistant-message metadata; non-null, defensively copied, may
 *     be empty
 * @param streamError the throwable that terminated the final attempt of the turn, or {@code null}
 *     when no error was recorded. Always {@code null} when {@code finishReason} is not {@link
 *     FinishReason#ERROR}
 * @param streamAttempts total number of stream attempts made for this turn; must be {@code >= 1}
 */
public record TurnOutcome(
    FinishReason finishReason,
    String assistantContent,
    Usage usage,
    Map<String, String> metadata,
    Throwable streamError,
    int streamAttempts) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code finishReason}, {@code assistantContent}, {@code usage},
   *     or {@code metadata} is null
   * @throws IllegalArgumentException if {@code streamAttempts < 1}
   */
  public TurnOutcome {
    Objects.requireNonNull(finishReason, "finishReason must not be null");
    Objects.requireNonNull(assistantContent, "assistantContent must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(metadata, "metadata must not be null");
    if (streamAttempts < 1) {
      throw new IllegalArgumentException("streamAttempts must be >= 1, got " + streamAttempts);
    }
    metadata = Map.copyOf(metadata);
  }

  /**
   * Convenience for callers that have no provider metadata, no stream error, and a single attempt.
   * Mirrors the pre-retry shape so existing call sites don't have to thread defaults.
   */
  public TurnOutcome(FinishReason finishReason, String assistantContent, Usage usage) {
    this(finishReason, assistantContent, usage, Map.of(), null, 1);
  }

  /**
   * Convenience that preserves {@link #streamError()} as {@code null} and {@link #streamAttempts()}
   * as {@code 1} — the shape every clean turn produces.
   */
  public TurnOutcome(
      FinishReason finishReason,
      String assistantContent,
      Usage usage,
      Map<String, String> metadata) {
    this(finishReason, assistantContent, usage, metadata, null, 1);
  }
}
