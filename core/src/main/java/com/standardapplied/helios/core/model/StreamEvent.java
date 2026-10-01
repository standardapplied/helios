/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/** Events emitted during streaming response from a model. */
public sealed interface StreamEvent {

  /**
   * A chunk of text content.
   *
   * @param text the text fragment emitted by the model for this delta
   */
  record TextDelta(String text) implements StreamEvent {}

  /**
   * A chunk of extended-thinking / reasoning content from the model. Maps to Anthropic
   * extended-thinking deltas, OpenAI {@code reasoning_summary} deltas, and Gemini thought parts.
   * Providers that do not surface thinking emit no events of this kind.
   *
   * @param text the thinking fragment emitted by the model for this delta
   */
  record ThinkingDelta(String text) implements StreamEvent {}

  /**
   * A complete thinking / reasoning block. {@code signature} carries the provider-side replay
   * signature (e.g. Anthropic's thought signature) when available, so subsequent turns can
   * round-trip the block verbatim for prefix-cache reuse. {@code signature} is {@code null} for
   * providers that don't expose one.
   *
   * @param fullThinking the complete thinking text aggregated from {@link ThinkingDelta}s
   * @param signature provider replay signature when available; {@code null} otherwise
   */
  record ThinkingComplete(String fullThinking, String signature) implements StreamEvent {

    /** Convenience for providers that don't surface a signature. */
    public ThinkingComplete(String fullThinking) {
      this(fullThinking, null);
    }
  }

  /**
   * A tool call has started.
   *
   * @param callId provider-assigned identifier correlating subsequent deltas to this call
   * @param toolName which tool the model is about to invoke
   */
  record ToolCallStart(String callId, String toolName) implements StreamEvent {}

  /**
   * Incremental arguments for an ongoing tool call.
   *
   * @param callId the call id originally surfaced by {@link ToolCallStart}
   * @param argumentsDelta JSON fragment to append to the call's accumulating argument string
   */
  record ToolCallDelta(String callId, String argumentsDelta) implements StreamEvent {}

  /**
   * A tool call is complete.
   *
   * @param toolCall fully-assembled tool invocation ready to dispatch
   */
  record ToolCallComplete(ToolCall toolCall) implements StreamEvent {}

  /**
   * The stream is complete.
   *
   * @param response the final aggregated response (accumulated text, tool calls, usage, finish
   *     reason)
   */
  record Done(Response<?> response) implements StreamEvent {}

  /**
   * An error occurred.
   *
   * @param message human-readable error description
   * @param cause originating exception, when known; may be {@code null}
   */
  record Error(String message, Exception cause) implements StreamEvent {
    /**
     * Convenience constructor when no underlying exception is available.
     *
     * @param message human-readable error description
     */
    public Error(String message) {
      this(message, null);
    }
  }
}
