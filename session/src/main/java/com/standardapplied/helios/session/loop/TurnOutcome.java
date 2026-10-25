/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of one model turn: what {@link TurnReader} read once the turn's stream ended, with the
 * attempt count {@link ModelCall} made, or an outcome the loop synthesises itself. Produced by
 * {@link TurnRunner#runTurn(SessionState, com.standardapplied.helios.session.SessionLimits)} and
 * consumed by {@link StopClassifier#classify(SessionState,
 * com.standardapplied.helios.session.SessionLimits, TurnOutcome, boolean)}.
 *
 * <p>{@code assistantContent} is the assistant text accumulated from every {@link
 * com.standardapplied.helios.core.model.ModelChunk.TextDelta TextDelta} chunk during the turn —
 * never null, possibly empty (a tool-call-only turn produces empty content); on a failed turn it is
 * the text streamed before the failure. {@code usage} is the {@link
 * com.standardapplied.helios.core.model.ModelChunk.MessageStop MessageStop} usage from the same
 * turn — the authoritative final tally for the turn. {@code metadata} carries provider-round-trip
 * data (Gemini thought signatures, Anthropic citation pointers, …) lifted off the {@code
 * MessageStop} chunk; the agent loop stores it on the assistant message so it survives into the
 * next turn's follow-up request.
 *
 * <p>{@code streamError} carries the throwable that ended the turn's stream — including its full
 * cause chain — so {@link StopClassifier} can surface {@code SerializedError.of(streamError)}
 * instead of collapsing the failure to a bare message string. {@code null} on every non-error turn.
 *
 * <p>{@code streamAttempts} is the total number of {@code model.chatStream(...)} attempts the loop
 * made for this turn before producing the recorded outcome. Always {@code >= 1}; a value of {@code
 * 1} means no retry occurred (either the first attempt succeeded or retry is disabled).
 *
 * @param finishReason the parsed finish reason, {@link FinishReason#ERROR} when {@code streamError}
 *     is set; non-null
 * @param assistantContent assembled assistant text; non-null, possibly empty
 * @param toolCalls the tool calls the stream completed, in order; non-null, copied
 * @param citations the grounding citations on the stream's message stop; non-null, copied, may be
 *     empty
 * @param usage final usage at message end; non-null
 * @param metadata provider-specific assistant-message metadata; non-null, copied, may be empty
 * @param streamError the throwable that terminated the final attempt of the turn, or {@code null}
 *     when the stream completed
 * @param streamAttempts total number of stream attempts made for this turn; must be {@code >= 1}
 */
public record TurnOutcome(
    FinishReason finishReason,
    String assistantContent,
    List<ToolCall> toolCalls,
    List<Citation> citations,
    Usage usage,
    Map<String, String> metadata,
    Throwable streamError,
    int streamAttempts) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument but {@code streamError} is null
   * @throws IllegalArgumentException if {@code streamAttempts < 1}
   */
  public TurnOutcome {
    Objects.requireNonNull(finishReason, "finishReason must not be null");
    Objects.requireNonNull(assistantContent, "assistantContent must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    if (streamAttempts < 1) {
      throw new IllegalArgumentException("streamAttempts must be >= 1, got " + streamAttempts);
    }
    toolCalls = List.copyOf(toolCalls);
    citations = List.copyOf(citations);
    metadata = Map.copyOf(metadata);
  }

  /**
   * An outcome the loop synthesises itself: no tool calls, citations, provider metadata or stream
   * error, and a single attempt.
   */
  public TurnOutcome(FinishReason finishReason, String assistantContent, Usage usage) {
    this(finishReason, assistantContent, List.of(), List.of(), usage, Map.of(), null, 1);
  }

  /** This outcome with {@code finishReason} in place of its own. */
  TurnOutcome withFinishReason(FinishReason finishReason) {
    return new TurnOutcome(
        finishReason,
        assistantContent,
        toolCalls,
        citations,
        usage,
        metadata,
        streamError,
        streamAttempts);
  }

  /** This outcome as the result of {@code streamAttempts} attempts. */
  TurnOutcome withStreamAttempts(int streamAttempts) {
    return new TurnOutcome(
        finishReason,
        assistantContent,
        toolCalls,
        citations,
        usage,
        metadata,
        streamError,
        streamAttempts);
  }
}
