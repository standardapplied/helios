/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.List;
import java.util.Map;

/**
 * What one model stream produced, as {@link TurnSubscriber#awaitDone} found it once the stream
 * ended.
 *
 * @param content the text accumulated from the stream's text deltas; never null. When {@code error}
 *     is set it holds the assistant's pre-error tokens, if any
 * @param toolCalls the tool calls the stream completed, in order; immutable
 * @param citations the grounding citations on the stream's message stop; immutable, may be empty
 * @param finishReason the parsed finish reason, {@link FinishReason#ERROR} when {@code error} is
 *     set
 * @param usage the usage reported at message stop
 * @param metadata provider metadata from the message stop
 * @param error the throwable that ended the stream, or {@code null} when it completed normally
 */
record StreamedTurn(
    String content,
    List<ToolCall> toolCalls,
    List<Citation> citations,
    FinishReason finishReason,
    Usage usage,
    Map<String, String> metadata,
    Throwable error) {

  /**
   * The turn outcome with an explicit {@code streamAttempts} count, which surfaces on {@link
   * com.standardapplied.helios.session.ResultMessage.ErrorTransientStream} when the retry budget is
   * exhausted. The recorded throwable is carried through unchanged so downstream consumers can walk
   * the full cause chain instead of seeing only the wrapper's message.
   *
   * @param streamAttempts the attempt count to record; must be {@code >= 1}
   */
  TurnOutcome toOutcome(int streamAttempts) {
    var assistantContent =
        error != null
            ? (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
            : content;
    return new TurnOutcome(finishReason, assistantContent, usage, metadata, error, streamAttempts);
  }
}
