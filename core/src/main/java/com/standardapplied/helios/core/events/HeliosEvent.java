/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Sealed root of everything observable during a Helios primitive run.
 *
 * <p>One unified event type covering run lifecycle, iteration boundaries, assistant content
 * (including incremental thinking from extended-reasoning models), tool dispatch, memory mutations,
 * span lifecycle, sub-agent delegation, compaction, and optimizer progress. The {@link Custom}
 * variant is the escape hatch for domain-specific events that don't fit the built-in shapes.
 *
 * <p>Every event carries three common fields:
 *
 * <ul>
 *   <li>{@link #at()} — when the event occurred, UTC instant.
 *   <li>{@link #runId()} — UUID v7 from {@code Ids.newId()}, stable across one primitive run, so
 *       consumers can multiplex many concurrent runs through the same sink.
 *   <li>{@link #spanId()} — the enclosing span (if any), enabling consumers to reconstruct the full
 *       span tree from the event stream alone.
 * </ul>
 *
 * <p>Records with collection-typed payloads ({@code Map}, {@code double[]}) defensively copy on
 * construction so the event is immutable from the consumer's perspective. Deep-immutability of
 * {@code Object}-valued maps is the caller's responsibility.
 */
public sealed interface HeliosEvent
    permits HeliosEvent.RunStarted,
        HeliosEvent.RunCompleted,
        HeliosEvent.RunFailed,
        HeliosEvent.IterationStarted,
        HeliosEvent.IterationCompleted,
        HeliosEvent.BeforeApiCall,
        HeliosEvent.AfterTurn,
        HeliosEvent.BeforeCompaction,
        HeliosEvent.SessionEnd,
        HeliosEvent.AssistantTextDelta,
        HeliosEvent.AssistantText,
        HeliosEvent.AssistantThinkingDelta,
        HeliosEvent.AssistantThinkingComplete,
        HeliosEvent.ToolCallStarted,
        HeliosEvent.ToolCallCompleted,
        HeliosEvent.ToolCallFailed,
        HeliosEvent.MemoryWritten,
        HeliosEvent.MemoryRead,
        HeliosEvent.SpanOpened,
        HeliosEvent.SpanClosed,
        HeliosEvent.SubAgentStarted,
        HeliosEvent.SubAgentCompleted,
        HeliosEvent.CompactionTriggered,
        HeliosEvent.OptimizerCandidateProposed,
        HeliosEvent.OptimizerCandidateScored,
        HeliosEvent.Custom {

  /** UTC instant the event occurred. */
  Instant at();

  /** Per-run identifier (UUID v7 from {@code Ids.newId()}). */
  UUID runId();

  /**
   * The span this event belongs to, if any. Use with {@link SpanOpened#parentSpanId()} to
   * reconstruct the full nested span tree from the event stream.
   */
  Optional<UUID> spanId();

  // ---------------------------------------------------------------------------
  // Run lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Emitted when a primitive run begins (Agent, Team, RlmHarness, CodeActHarness, optimizer).
   *
   * @param harnessKind label identifying which primitive started, e.g. {@code "agent"}, {@code
   *     "team"}, {@code "rlm-harness"}. Used by UIs to render the right icon / lane.
   * @param attributes implementation-defined metadata (model id, max iterations, etc.).
   */
  record RunStarted(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String harnessKind,
      Map<String, String> attributes)
      implements HeliosEvent {
    public RunStarted {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(harnessKind)) {
        throw new IllegalArgumentException("harnessKind must not be blank");
      }
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
  }

  /**
   * Emitted when a primitive run completes successfully. Carries the terminal {@link Trace}
   * artifact — duration, spans, attributes, token totals are all reachable through {@code trace()}.
   * This is the unified "give me the final artifact" surface.
   */
  record RunCompleted(Instant at, UUID runId, Optional<UUID> spanId, Trace trace)
      implements HeliosEvent {
    public RunCompleted {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(trace, "trace");
    }
  }

  /**
   * Emitted when a primitive run terminates with an error. {@code error} is kept as a top-level
   * convenience for pattern matching; {@code trace.error()} carries the same value.
   */
  record RunFailed(Instant at, UUID runId, Optional<UUID> spanId, String error, Trace trace)
      implements HeliosEvent {
    public RunFailed {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(error)) {
        throw new IllegalArgumentException("error must not be blank");
      }
      Objects.requireNonNull(trace, "trace");
    }
  }

  // ---------------------------------------------------------------------------
  // Iteration boundaries
  // ---------------------------------------------------------------------------

  /** Emitted at the start of each agent-loop iteration. */
  record IterationStarted(
      Instant at, UUID runId, Optional<UUID> spanId, int iteration, int maxIterations)
      implements HeliosEvent {
    public IterationStarted {
      requireBase(at, runId, spanId);
      if (iteration < 0) {
        throw new IllegalArgumentException("iteration must be >= 0");
      }
      if (maxIterations <= 0) {
        throw new IllegalArgumentException("maxIterations must be > 0");
      }
    }
  }

  /** Emitted at the end of each agent-loop iteration. */
  record IterationCompleted(Instant at, UUID runId, Optional<UUID> spanId, int iteration)
      implements HeliosEvent {
    public IterationCompleted {
      requireBase(at, runId, spanId);
      if (iteration < 0) {
        throw new IllegalArgumentException("iteration must be >= 0");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Agent loop hooks (replaces the legacy MemoryListener / MemoryEvent surface)
  // ---------------------------------------------------------------------------

  /**
   * Fired immediately before each {@code model.chat} call. The {@code messages} list reflects
   * exactly what will be sent (post-compaction, post-system-prompt-refresh). Consumers can use this
   * to prefetch context that should land in the prompt before the API call.
   */
  record BeforeApiCall(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String userId,
      UUID sessionId,
      List<Message> messages,
      int iteration)
      implements HeliosEvent {
    public BeforeApiCall {
      requireBase(at, runId, spanId);
      messages = messages == null ? List.of() : List.copyOf(messages);
      if (iteration < 0) {
        throw new IllegalArgumentException("iteration must be >= 0");
      }
    }
  }

  /**
   * Fired after a model response has been processed for the current iteration. {@code userMessage}
   * is the most recent user message at the time the turn started — may be empty when the message
   * list begins with a system prompt and no user turn. The canonical hook for "observe the
   * user/assistant interaction and react to it" — behavior extractors, consolidators.
   */
  record AfterTurn(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String userId,
      UUID sessionId,
      Optional<Message> userMessage,
      Message assistantMessage,
      List<Message> toolMessages,
      int iteration)
      implements HeliosEvent {
    public AfterTurn {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(userMessage, "userMessage");
      Objects.requireNonNull(assistantMessage, "assistantMessage");
      toolMessages = toolMessages == null ? List.of() : List.copyOf(toolMessages);
      if (iteration < 0) {
        throw new IllegalArgumentException("iteration must be >= 0");
      }
    }
  }

  /**
   * Fired right before the context compactor rewrites the message list. Listeners scan the
   * pre-compaction history for durable signals before middle turns collapse into a summary.
   */
  record BeforeCompaction(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String userId,
      UUID sessionId,
      List<Message> messages)
      implements HeliosEvent {
    public BeforeCompaction {
      requireBase(at, runId, spanId);
      messages = messages == null ? List.of() : List.copyOf(messages);
    }
  }

  /**
   * Fired when the agent loop reaches a terminal state — successful completion, terminal failure,
   * or max-iteration exhaustion. Use to flush pending memory writes or kick off offline
   * consolidation.
   */
  record SessionEnd(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String userId,
      UUID sessionId,
      List<Message> finalMessages,
      Termination termination)
      implements HeliosEvent {

    public SessionEnd {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(termination, "termination");
      finalMessages = finalMessages == null ? List.of() : List.copyOf(finalMessages);
    }

    /** Why the session ended. */
    public enum Termination {
      /** Model produced a final response without tool calls and any guardrails were satisfied. */
      COMPLETED,
      /** Loop hit {@code maxIterations} before completing. */
      MAX_ITERATIONS,
      /** Step returned a failure (exception, parse error, etc). */
      FAILED
    }
  }

  // ---------------------------------------------------------------------------
  // Assistant content (text + thinking)
  // ---------------------------------------------------------------------------

  /** A chunk of assistant text streamed from the model. */
  record AssistantTextDelta(Instant at, UUID runId, Optional<UUID> spanId, String text)
      implements HeliosEvent {
    public AssistantTextDelta {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(text, "text");
    }
  }

  /** A complete assistant text message (terminal aggregation of deltas, or non-streamed turn). */
  record AssistantText(Instant at, UUID runId, Optional<UUID> spanId, String fullText)
      implements HeliosEvent {
    public AssistantText {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(fullText, "fullText");
    }
  }

  /**
   * Incremental thinking / reasoning text from extended-reasoning models. Maps to Anthropic
   * extended-thinking deltas, OpenAI {@code reasoning_summary} deltas, Gemini thought parts.
   */
  record AssistantThinkingDelta(Instant at, UUID runId, Optional<UUID> spanId, String thinkingText)
      implements HeliosEvent {
    public AssistantThinkingDelta {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(thinkingText, "thinkingText");
    }
  }

  /**
   * A complete thinking / reasoning block. {@code signature} carries the provider-side replay
   * signature (e.g. Anthropic's thought signature) when available, so subsequent turns can
   * round-trip the block verbatim for prefix-cache reuse.
   */
  record AssistantThinkingComplete(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String fullThinking,
      Optional<String> signature)
      implements HeliosEvent {
    public AssistantThinkingComplete {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(fullThinking, "fullThinking");
      Objects.requireNonNull(signature, "signature");
    }
  }

  // ---------------------------------------------------------------------------
  // Tool calls
  // ---------------------------------------------------------------------------

  /** A tool call has been dispatched. */
  record ToolCallStarted(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String toolCallId,
      String toolName,
      Map<String, Object> args)
      implements HeliosEvent {
    public ToolCallStarted {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(toolCallId)) {
        throw new IllegalArgumentException("toolCallId must not be blank");
      }
      if (Strings.isBlank(toolName)) {
        throw new IllegalArgumentException("toolName must not be blank");
      }
      args = args == null ? Map.of() : Map.copyOf(args);
    }
  }

  /** A tool call completed (either success or framework-wrapped failure). */
  record ToolCallCompleted(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String toolCallId,
      ToolResult result,
      Duration took)
      implements HeliosEvent {
    public ToolCallCompleted {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(toolCallId)) {
        throw new IllegalArgumentException("toolCallId must not be blank");
      }
      Objects.requireNonNull(result, "result");
      Objects.requireNonNull(took, "took");
    }
  }

  /** A tool call failed catastrophically (thrown exception, not a {@link ToolResult} failure). */
  record ToolCallFailed(
      Instant at, UUID runId, Optional<UUID> spanId, String toolCallId, String error)
      implements HeliosEvent {
    public ToolCallFailed {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(toolCallId)) {
        throw new IllegalArgumentException("toolCallId must not be blank");
      }
      if (Strings.isBlank(error)) {
        throw new IllegalArgumentException("error must not be blank");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Memory mutations
  // ---------------------------------------------------------------------------

  /** A core memory block was written (update / replace / remove). */
  record MemoryWritten(
      Instant at, UUID runId, Optional<UUID> spanId, String blockName, String operation)
      implements HeliosEvent {
    public MemoryWritten {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(blockName)) {
        throw new IllegalArgumentException("blockName must not be blank");
      }
      if (Strings.isBlank(operation)) {
        throw new IllegalArgumentException("operation must not be blank");
      }
    }
  }

  /** A core memory block was read by the model. */
  record MemoryRead(Instant at, UUID runId, Optional<UUID> spanId, String blockName)
      implements HeliosEvent {
    public MemoryRead {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(blockName)) {
        throw new IllegalArgumentException("blockName must not be blank");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Span lifecycle
  // ---------------------------------------------------------------------------

  /**
   * A span was opened. Carries the new span's id and its parent so consumers can reconstruct the
   * full nested span tree from the event stream alone.
   */
  record SpanOpened(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      UUID openedSpanId,
      Optional<UUID> parentSpanId,
      String name)
      implements HeliosEvent {
    public SpanOpened {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(openedSpanId, "openedSpanId");
      Objects.requireNonNull(parentSpanId, "parentSpanId");
      if (Strings.isBlank(name)) {
        throw new IllegalArgumentException("name must not be blank");
      }
    }
  }

  /** A span was closed (success or failure). */
  record SpanClosed(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      UUID closedSpanId,
      Duration duration,
      boolean success,
      Optional<String> error)
      implements HeliosEvent {
    public SpanClosed {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(closedSpanId, "closedSpanId");
      Objects.requireNonNull(duration, "duration");
      Objects.requireNonNull(error, "error");
    }
  }

  // ---------------------------------------------------------------------------
  // Sub-agent (Team) lifecycle
  // ---------------------------------------------------------------------------

  /** A team leader dispatched work to a worker sub-agent. */
  record SubAgentStarted(
      Instant at, UUID runId, Optional<UUID> spanId, String subAgentName, UUID parentSpanId)
      implements HeliosEvent {
    public SubAgentStarted {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(subAgentName)) {
        throw new IllegalArgumentException("subAgentName must not be blank");
      }
      Objects.requireNonNull(parentSpanId, "parentSpanId");
    }
  }

  /** A worker sub-agent completed and returned to the leader. */
  record SubAgentCompleted(
      Instant at, UUID runId, Optional<UUID> spanId, String subAgentName, Duration duration)
      implements HeliosEvent {
    public SubAgentCompleted {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(subAgentName)) {
        throw new IllegalArgumentException("subAgentName must not be blank");
      }
      Objects.requireNonNull(duration, "duration");
    }
  }

  // ---------------------------------------------------------------------------
  // Compaction
  // ---------------------------------------------------------------------------

  /**
   * The context compactor ran.
   *
   * @param phase which compaction phase triggered (e.g. {@code "prune"}, {@code "summarize"})
   * @param beforeTokens token count before compaction
   * @param afterTokens token count after compaction
   */
  record CompactionTriggered(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      String phase,
      int beforeTokens,
      int afterTokens)
      implements HeliosEvent {
    public CompactionTriggered {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(phase)) {
        throw new IllegalArgumentException("phase must not be blank");
      }
      if (beforeTokens < 0) {
        throw new IllegalArgumentException("beforeTokens must be >= 0");
      }
      if (afterTokens < 0) {
        throw new IllegalArgumentException("afterTokens must be >= 0");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Autoresearch optimizer progress
  // ---------------------------------------------------------------------------

  /** A new candidate was proposed by a {@code ReflectiveMutator}. */
  record OptimizerCandidateProposed(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      UUID candidateId,
      Optional<UUID> parentCandidateId,
      String source)
      implements HeliosEvent {
    public OptimizerCandidateProposed {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(candidateId, "candidateId");
      Objects.requireNonNull(parentCandidateId, "parentCandidateId");
      if (Strings.isBlank(source)) {
        throw new IllegalArgumentException("source must not be blank");
      }
    }
  }

  /** A candidate was scored against the validation set. */
  record OptimizerCandidateScored(
      Instant at,
      UUID runId,
      Optional<UUID> spanId,
      UUID candidateId,
      double aggregateScore,
      double[] perInstanceScores)
      implements HeliosEvent {
    public OptimizerCandidateScored {
      requireBase(at, runId, spanId);
      Objects.requireNonNull(candidateId, "candidateId");
      Objects.requireNonNull(perInstanceScores, "perInstanceScores");
      if (Double.isNaN(aggregateScore)) {
        throw new IllegalArgumentException("aggregateScore must not be NaN");
      }
      for (var v : perInstanceScores) {
        if (Double.isNaN(v)) {
          throw new IllegalArgumentException("perInstanceScores must not contain NaN");
        }
      }
      perInstanceScores = perInstanceScores.clone();
    }
  }

  // ---------------------------------------------------------------------------
  // Escape hatch
  // ---------------------------------------------------------------------------

  /**
   * Domain-specific event for cases the built-in variants don't cover. Library users emit these via
   * {@code EventSink.onEvent(new HeliosEvent.Custom(...))} from their own code paths.
   *
   * <p>Recommended convention for {@link #kind()}: {@code "<domain>.<verb>"} (e.g. {@code
   * "kubera.quote-fetched"}, {@code "clinical.column-mapped"}). Not enforced — domain code owns the
   * namespace.
   */
  record Custom(
      Instant at, UUID runId, Optional<UUID> spanId, String kind, Map<String, Object> data)
      implements HeliosEvent {
    public Custom {
      requireBase(at, runId, spanId);
      if (Strings.isBlank(kind)) {
        throw new IllegalArgumentException("Custom.kind must not be blank");
      }
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  // ---------------------------------------------------------------------------
  // Shared base validation
  // ---------------------------------------------------------------------------

  /** Package-private base-field validation reused by every variant's compact constructor. */
  private static void requireBase(Instant at, UUID runId, Optional<UUID> spanId) {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(spanId, "spanId");
  }
}
