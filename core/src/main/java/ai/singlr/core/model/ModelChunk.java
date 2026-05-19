/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.model;

import ai.singlr.core.model.Response.Usage;
import java.util.Map;
import java.util.Objects;

/**
 * Normalized streaming chunk emitted by a {@link Model} during {@code chatStream}.
 *
 * <p>Provider modules ({@code helios-anthropic}, {@code helios-openai}, {@code helios-gemini})
 * translate wire-level streaming events into the seven subtypes below so the agent loop never sees
 * provider-specific payloads. Subscribers pattern-match the subtype via {@code switch}; the sealed
 * permits list is the closed contract.
 *
 * <h2>Adding a subtype</h2>
 *
 * Breaking change for {@code switch} consumers without a {@code default} branch — by design, so the
 * compiler flags consumers that need updating.
 *
 * <h2>Relationship to {@code StreamEvent}</h2>
 *
 * Coexists with the iterator-shaped {@link StreamEvent} that older non-session callers consume. The
 * v2 session loop consumes {@code Flow.Publisher<ModelChunk>} via {@link
 * Model#chatStream(java.util.List, java.util.List, ai.singlr.core.runtime.CancellationToken)}.
 */
public sealed interface ModelChunk
    permits ModelChunk.TextDelta,
        ModelChunk.ThinkingDelta,
        ModelChunk.ToolUseStart,
        ModelChunk.ToolUseDelta,
        ModelChunk.ToolUseStop,
        ModelChunk.MessageStop,
        ModelChunk.UsageDelta {

  /**
   * A fragment of assistant text. {@code text} may be empty (some providers emit empty deltas);
   * subscribers concatenate across the stream to assemble the full message.
   *
   * @param text the text fragment; non-null
   */
  record TextDelta(String text) implements ModelChunk {

    public TextDelta {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /**
   * A fragment of extended-thinking / reasoning text (Anthropic extended-thinking, OpenAI reasoning
   * summary, Gemini thought parts). Providers that do not surface thinking emit no chunks of this
   * kind.
   *
   * @param text the thinking fragment; non-null
   */
  record ThinkingDelta(String text) implements ModelChunk {

    public ThinkingDelta {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /**
   * A new tool call has started. Subscribers track {@code callId} to correlate subsequent {@link
   * ToolUseDelta}s and the terminating {@link ToolUseStop}.
   *
   * @param callId provider-assigned correlation id; non-null and non-blank
   * @param toolName the name of the tool being called; non-null and non-blank
   */
  record ToolUseStart(String callId, String toolName) implements ModelChunk {

    public ToolUseStart {
      Objects.requireNonNull(callId, "callId must not be null");
      if (callId.isBlank()) {
        throw new IllegalArgumentException("callId must not be blank");
      }
      Objects.requireNonNull(toolName, "toolName must not be null");
      if (toolName.isBlank()) {
        throw new IllegalArgumentException("toolName must not be blank");
      }
    }
  }

  /**
   * Incremental arguments for an ongoing tool call. {@code argumentsDelta} is a raw JSON fragment
   * to append to the call's accumulating argument string; the loop parses the assembled string when
   * {@link ToolUseStop} arrives.
   *
   * @param callId the correlation id from the matching {@link ToolUseStart}; non-null and non-blank
   * @param argumentsDelta JSON fragment to append; non-null, may be empty
   */
  record ToolUseDelta(String callId, String argumentsDelta) implements ModelChunk {

    public ToolUseDelta {
      Objects.requireNonNull(callId, "callId must not be null");
      if (callId.isBlank()) {
        throw new IllegalArgumentException("callId must not be blank");
      }
      Objects.requireNonNull(argumentsDelta, "argumentsDelta must not be null");
    }
  }

  /**
   * A tool call is fully assembled. The {@code toolCall} carries the parsed argument map ready for
   * dispatch.
   *
   * @param toolCall the assembled call; non-null
   */
  record ToolUseStop(ToolCall toolCall) implements ModelChunk {

    public ToolUseStop {
      Objects.requireNonNull(toolCall, "toolCall must not be null");
    }
  }

  /**
   * The model finished its message. {@code stopReason} is the provider's finish reason ({@code
   * end_turn}, {@code tool_use}, {@code max_tokens}, {@code stop_sequence}, …); {@code usage}
   * carries the accumulated token counts at message end; {@code metadata} carries provider-specific
   * round-trip data (Gemini thought signatures, Anthropic citation pointers, etc.) that must be
   * preserved when the assistant message is replayed on a follow-up turn.
   *
   * <p>This is the terminal chunk for a single model message. A successful turn ends with exactly
   * one {@code MessageStop}; the publisher then signals {@code onComplete()}.
   *
   * @param stopReason the provider-reported finish reason; non-null and non-blank
   * @param usage final usage at message end; non-null
   * @param metadata provider-specific assistant-message metadata; non-null, defensively copied, may
   *     be empty
   */
  record MessageStop(String stopReason, Usage usage, java.util.Map<String, String> metadata)
      implements ModelChunk {

    public MessageStop {
      Objects.requireNonNull(stopReason, "stopReason must not be null");
      if (stopReason.isBlank()) {
        throw new IllegalArgumentException("stopReason must not be blank");
      }
      Objects.requireNonNull(usage, "usage must not be null");
      Objects.requireNonNull(metadata, "metadata must not be null");
      metadata = Map.copyOf(metadata);
    }

    /** Back-compat convenience for callers that don't supply metadata. */
    public MessageStop(String stopReason, Usage usage) {
      this(stopReason, usage, Map.of());
    }
  }

  /**
   * An incremental usage update emitted mid-stream by providers that surface token-by-token usage
   * (e.g. cached-prompt readouts arriving before {@link MessageStop}). The final cumulative usage
   * still arrives on {@link MessageStop}; consumers that only need the final tally can ignore
   * {@code UsageDelta} entirely.
   *
   * @param usage the usage snapshot at this delta; non-null
   */
  record UsageDelta(Usage usage) implements ModelChunk {

    public UsageDelta {
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }
}
