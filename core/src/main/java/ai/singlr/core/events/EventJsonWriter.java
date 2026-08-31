/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

import ai.singlr.core.events.HeliosEvent.AfterTurn;
import ai.singlr.core.events.HeliosEvent.AssistantText;
import ai.singlr.core.events.HeliosEvent.AssistantTextDelta;
import ai.singlr.core.events.HeliosEvent.AssistantThinkingComplete;
import ai.singlr.core.events.HeliosEvent.AssistantThinkingDelta;
import ai.singlr.core.events.HeliosEvent.BeforeApiCall;
import ai.singlr.core.events.HeliosEvent.BeforeCompaction;
import ai.singlr.core.events.HeliosEvent.CompactionTriggered;
import ai.singlr.core.events.HeliosEvent.Custom;
import ai.singlr.core.events.HeliosEvent.IterationCompleted;
import ai.singlr.core.events.HeliosEvent.IterationStarted;
import ai.singlr.core.events.HeliosEvent.MemoryRead;
import ai.singlr.core.events.HeliosEvent.MemoryWritten;
import ai.singlr.core.events.HeliosEvent.OptimizerCandidateProposed;
import ai.singlr.core.events.HeliosEvent.OptimizerCandidateScored;
import ai.singlr.core.events.HeliosEvent.RunCompleted;
import ai.singlr.core.events.HeliosEvent.RunFailed;
import ai.singlr.core.events.HeliosEvent.RunStarted;
import ai.singlr.core.events.HeliosEvent.SessionEnd;
import ai.singlr.core.events.HeliosEvent.SpanClosed;
import ai.singlr.core.events.HeliosEvent.SpanOpened;
import ai.singlr.core.events.HeliosEvent.SubAgentCompleted;
import ai.singlr.core.events.HeliosEvent.SubAgentStarted;
import ai.singlr.core.events.HeliosEvent.ToolCallCompleted;
import ai.singlr.core.events.HeliosEvent.ToolCallFailed;
import ai.singlr.core.events.HeliosEvent.ToolCallStarted;
import ai.singlr.core.trace.Trace;
import java.util.Map;
import java.util.Optional;

/**
 * Package-private JSONL encoder for {@link HeliosEvent}. Hand-rolled to keep {@code core}
 * dependency-free.
 *
 * <p>The format is a deliberately narrow subset of JSON: each event is a single-line JSON object
 * with a {@code type} discriminator plus variant-specific fields. Numbers are emitted as their
 * canonical Java string (finite values only); strings are escaped per RFC 8259; maps are encoded as
 * objects with string keys and best-effort {@code toString()} for non-primitive Object values.
 */
final class EventJsonWriter {

  private EventJsonWriter() {}

  static String encode(HeliosEvent event) {
    var sb = new StringBuilder(256);
    sb.append('{');
    writeBaseFields(sb, event);
    switch (event) {
      case RunStarted e -> {
        appendString(sb, "type", "RunStarted");
        appendString(sb, "harnessKind", e.harnessKind());
        appendStringMap(sb, "attributes", e.attributes());
      }
      case RunCompleted e -> {
        appendString(sb, "type", "RunCompleted");
        appendTraceSummary(sb, e.trace());
      }
      case RunFailed e -> {
        appendString(sb, "type", "RunFailed");
        appendString(sb, "error", e.error());
        appendTraceSummary(sb, e.trace());
      }
      case IterationStarted e -> {
        appendString(sb, "type", "IterationStarted");
        appendNumber(sb, "iteration", e.iteration());
        appendNumber(sb, "maxIterations", e.maxIterations());
      }
      case IterationCompleted e -> {
        appendString(sb, "type", "IterationCompleted");
        appendNumber(sb, "iteration", e.iteration());
      }
      case BeforeApiCall e -> {
        appendString(sb, "type", "BeforeApiCall");
        appendOptionalString(sb, "userId", Optional.ofNullable(e.userId()));
        appendString(sb, "sessionId", e.sessionId() == null ? "" : e.sessionId().toString());
        appendNumber(sb, "messageCount", e.messages().size());
        appendNumber(sb, "iteration", e.iteration());
      }
      case AfterTurn e -> {
        appendString(sb, "type", "AfterTurn");
        appendOptionalString(sb, "userId", Optional.ofNullable(e.userId()));
        appendString(sb, "sessionId", e.sessionId() == null ? "" : e.sessionId().toString());
        appendNumber(sb, "toolMessageCount", e.toolMessages().size());
        appendNumber(sb, "iteration", e.iteration());
      }
      case BeforeCompaction e -> {
        appendString(sb, "type", "BeforeCompaction");
        appendOptionalString(sb, "userId", Optional.ofNullable(e.userId()));
        appendString(sb, "sessionId", e.sessionId() == null ? "" : e.sessionId().toString());
        appendNumber(sb, "messageCount", e.messages().size());
      }
      case SessionEnd e -> {
        appendString(sb, "type", "SessionEnd");
        appendOptionalString(sb, "userId", Optional.ofNullable(e.userId()));
        appendString(sb, "sessionId", e.sessionId() == null ? "" : e.sessionId().toString());
        appendString(sb, "termination", e.termination().name());
        appendNumber(sb, "finalMessageCount", e.finalMessages().size());
      }
      case AssistantTextDelta e -> {
        appendString(sb, "type", "AssistantTextDelta");
        appendString(sb, "text", e.text());
      }
      case AssistantText e -> {
        appendString(sb, "type", "AssistantText");
        appendString(sb, "fullText", e.fullText());
      }
      case AssistantThinkingDelta e -> {
        appendString(sb, "type", "AssistantThinkingDelta");
        appendString(sb, "thinkingText", e.thinkingText());
      }
      case AssistantThinkingComplete e -> {
        appendString(sb, "type", "AssistantThinkingComplete");
        appendString(sb, "fullThinking", e.fullThinking());
        appendOptionalString(sb, "signature", e.signature());
      }
      case ToolCallStarted e -> {
        appendString(sb, "type", "ToolCallStarted");
        appendString(sb, "toolCallId", e.toolCallId());
        appendString(sb, "toolName", e.toolName());
        appendObjectMap(sb, "args", e.args());
      }
      case ToolCallCompleted e -> {
        appendString(sb, "type", "ToolCallCompleted");
        appendString(sb, "toolCallId", e.toolCallId());
        appendBoolean(sb, "success", e.result().success());
        appendNumber(sb, "tookNanos", e.took().toNanos());
      }
      case ToolCallFailed e -> {
        appendString(sb, "type", "ToolCallFailed");
        appendString(sb, "toolCallId", e.toolCallId());
        appendString(sb, "error", e.error());
      }
      case MemoryWritten e -> {
        appendString(sb, "type", "MemoryWritten");
        appendString(sb, "blockName", e.blockName());
        appendString(sb, "operation", e.operation());
      }
      case MemoryRead e -> {
        appendString(sb, "type", "MemoryRead");
        appendString(sb, "blockName", e.blockName());
      }
      case SpanOpened e -> {
        appendString(sb, "type", "SpanOpened");
        appendString(sb, "openedSpanId", e.openedSpanId().toString());
        appendOptionalString(sb, "parentSpanId", e.parentSpanId().map(Object::toString));
        appendString(sb, "name", e.name());
      }
      case SpanClosed e -> {
        appendString(sb, "type", "SpanClosed");
        appendString(sb, "closedSpanId", e.closedSpanId().toString());
        appendNumber(sb, "durationNanos", e.duration().toNanos());
        appendBoolean(sb, "success", e.success());
        appendOptionalString(sb, "error", e.error());
      }
      case SubAgentStarted e -> {
        appendString(sb, "type", "SubAgentStarted");
        appendString(sb, "subAgentName", e.subAgentName());
        appendString(sb, "parentSpanId", e.parentSpanId().toString());
      }
      case SubAgentCompleted e -> {
        appendString(sb, "type", "SubAgentCompleted");
        appendString(sb, "subAgentName", e.subAgentName());
        appendNumber(sb, "durationNanos", e.duration().toNanos());
      }
      case CompactionTriggered e -> {
        appendString(sb, "type", "CompactionTriggered");
        appendString(sb, "phase", e.phase());
        appendNumber(sb, "beforeTokens", e.beforeTokens());
        appendNumber(sb, "afterTokens", e.afterTokens());
      }
      case OptimizerCandidateProposed e -> {
        appendString(sb, "type", "OptimizerCandidateProposed");
        appendString(sb, "candidateId", e.candidateId().toString());
        appendOptionalString(sb, "parentCandidateId", e.parentCandidateId().map(Object::toString));
        appendString(sb, "source", e.source());
      }
      case OptimizerCandidateScored e -> {
        appendString(sb, "type", "OptimizerCandidateScored");
        appendString(sb, "candidateId", e.candidateId().toString());
        appendNumber(sb, "aggregateScore", e.aggregateScore());
        appendDoubleArray(sb, "perInstanceScores", e.perInstanceScores());
      }
      case Custom e -> {
        appendString(sb, "type", "Custom");
        appendString(sb, "kind", e.kind());
        appendObjectMap(sb, "data", e.data());
      }
    }
    trimTrailingComma(sb);
    sb.append('}');
    return sb.toString();
  }

  static String encodeMetadataOnly(HeliosEvent event) {
    var sb = new StringBuilder(192);
    sb.append('{');
    writeBaseFields(sb, event);
    switch (event) {
      case RunStarted e -> {
        appendString(sb, "type", "RunStarted");
        appendApiVersion(sb, e.attributes());
      }
      case RunCompleted e -> {
        appendString(sb, "type", "RunCompleted");
        appendMetadataTraceSummary(sb, e.trace(), true);
      }
      case RunFailed e -> {
        appendString(sb, "type", "RunFailed");
        appendString(sb, "errorCategory", "run_failed");
        appendMetadataTraceSummary(sb, e.trace(), false);
      }
      case IterationStarted e -> {
        appendString(sb, "type", "IterationStarted");
        appendNumber(sb, "iteration", e.iteration());
        appendNumber(sb, "maxIterations", e.maxIterations());
      }
      case IterationCompleted e -> {
        appendString(sb, "type", "IterationCompleted");
        appendNumber(sb, "iteration", e.iteration());
      }
      case BeforeApiCall e -> {
        appendString(sb, "type", "BeforeApiCall");
        appendNumber(sb, "messageCount", e.messages().size());
        appendNumber(sb, "iteration", e.iteration());
      }
      case AfterTurn e -> {
        appendString(sb, "type", "AfterTurn");
        appendNumber(sb, "toolMessageCount", e.toolMessages().size());
        appendNumber(sb, "iteration", e.iteration());
      }
      case BeforeCompaction e -> {
        appendString(sb, "type", "BeforeCompaction");
        appendNumber(sb, "messageCount", e.messages().size());
      }
      case SessionEnd e -> {
        appendString(sb, "type", "SessionEnd");
        appendString(sb, "termination", e.termination().name());
        appendNumber(sb, "finalMessageCount", e.finalMessages().size());
      }
      case AssistantTextDelta e -> appendString(sb, "type", "AssistantTextDelta");
      case AssistantText e -> appendString(sb, "type", "AssistantText");
      case AssistantThinkingDelta e -> appendString(sb, "type", "AssistantThinkingDelta");
      case AssistantThinkingComplete e -> appendString(sb, "type", "AssistantThinkingComplete");
      case ToolCallStarted e -> {
        appendString(sb, "type", "ToolCallStarted");
        appendString(sb, "toolCallId", e.toolCallId());
        appendString(sb, "toolName", e.toolName());
      }
      case ToolCallCompleted e -> {
        appendString(sb, "type", "ToolCallCompleted");
        appendString(sb, "toolCallId", e.toolCallId());
        appendBoolean(sb, "success", e.result().success());
        appendNumber(sb, "tookNanos", e.took().toNanos());
      }
      case ToolCallFailed e -> {
        appendString(sb, "type", "ToolCallFailed");
        appendString(sb, "toolCallId", e.toolCallId());
        appendBoolean(sb, "success", false);
        appendString(sb, "errorCategory", "tool_failed");
      }
      case MemoryWritten e -> {
        appendString(sb, "type", "MemoryWritten");
      }
      case MemoryRead e -> appendString(sb, "type", "MemoryRead");
      case SpanOpened e -> {
        appendString(sb, "type", "SpanOpened");
        appendString(sb, "openedSpanId", e.openedSpanId().toString());
        appendOptionalString(sb, "parentSpanId", e.parentSpanId().map(Object::toString));
      }
      case SpanClosed e -> {
        appendString(sb, "type", "SpanClosed");
        appendString(sb, "closedSpanId", e.closedSpanId().toString());
        appendNumber(sb, "durationNanos", e.duration().toNanos());
        appendBoolean(sb, "success", e.success());
        if (!e.success()) {
          appendString(sb, "errorCategory", "span_failed");
        }
      }
      case SubAgentStarted e -> {
        appendString(sb, "type", "SubAgentStarted");
        appendString(sb, "parentSpanId", e.parentSpanId().toString());
      }
      case SubAgentCompleted e -> {
        appendString(sb, "type", "SubAgentCompleted");
        appendNumber(sb, "durationNanos", e.duration().toNanos());
      }
      case CompactionTriggered e -> {
        appendString(sb, "type", "CompactionTriggered");
        appendNumber(sb, "beforeTokens", e.beforeTokens());
        appendNumber(sb, "afterTokens", e.afterTokens());
      }
      case OptimizerCandidateProposed e -> {
        appendString(sb, "type", "OptimizerCandidateProposed");
        appendString(sb, "candidateId", e.candidateId().toString());
        appendOptionalString(sb, "parentCandidateId", e.parentCandidateId().map(Object::toString));
      }
      case OptimizerCandidateScored e -> {
        appendString(sb, "type", "OptimizerCandidateScored");
        appendString(sb, "candidateId", e.candidateId().toString());
        appendNumber(sb, "aggregateScore", e.aggregateScore());
        appendDoubleArray(sb, "perInstanceScores", e.perInstanceScores());
      }
      case Custom e -> {
        appendString(sb, "type", "Custom");
      }
    }
    trimTrailingComma(sb);
    sb.append('}');
    return sb.toString();
  }

  private static void appendMetadataTraceSummary(StringBuilder sb, Trace trace, boolean success) {
    writeKey(sb, "trace");
    sb.append('{');
    appendString(sb, "id", trace.id().toString());
    if (trace.duration() != null) {
      appendNumber(sb, "durationNanos", trace.duration().toNanos());
    }
    appendNumber(sb, "spanCount", trace.spans().size());
    appendBoolean(sb, "success", success);
    if (trace.modelId() != null) {
      appendString(sb, "modelId", trace.modelId());
    }
    appendApiVersion(sb, trace.attributes());
    if (trace.usage() != null) {
      appendNumber(sb, "inputTokens", trace.usage().inputTokens());
      appendNumber(sb, "outputTokens", trace.usage().outputTokens());
      appendNumber(sb, "cacheCreationInputTokens", trace.usage().cacheCreationInputTokens());
      appendNumber(sb, "cacheReadInputTokens", trace.usage().cacheReadInputTokens());
      appendNumber(sb, "totalTokens", trace.usage().totalTokens());
    } else {
      appendNumber(sb, "totalTokens", trace.totalTokens());
    }
    trimTrailingComma(sb);
    sb.append('}').append(',');
  }

  private static void appendApiVersion(StringBuilder sb, Map<String, String> attributes) {
    var apiVersion = attributes.get("gemini.apiVersion");
    if (apiVersion == null) {
      apiVersion = attributes.get("apiVersion");
    }
    if ("v1".equals(apiVersion) || "v1beta".equals(apiVersion) || "custom".equals(apiVersion)) {
      appendString(sb, "apiVersion", apiVersion);
    }
  }

  /**
   * Emits a compact JSON object summarizing the trace — id, duration, top-level span count, total
   * tokens. The full nested span tree is intentionally omitted from JSONL to keep one event per
   * line tractable. Consumers needing the full {@code Trace} use programmatic {@link EventSink}
   * subscription, not JSONL replay.
   */
  private static void appendTraceSummary(StringBuilder sb, Trace trace) {
    writeKey(sb, "trace");
    sb.append('{');
    writeKey(sb, "id");
    writeQuotedString(sb, trace.id().toString());
    sb.append(',');
    writeKey(sb, "durationNanos");
    if (trace.duration() == null) {
      sb.append("null");
    } else {
      sb.append(trace.duration().toNanos());
    }
    sb.append(',');
    writeKey(sb, "spanCount");
    sb.append(trace.spans().size());
    sb.append(',');
    writeKey(sb, "totalTokens");
    sb.append(trace.totalTokens());
    sb.append('}');
    sb.append(',');
  }

  private static void trimTrailingComma(StringBuilder sb) {
    var last = sb.length() - 1;
    if (last >= 0 && sb.charAt(last) == ',') {
      sb.deleteCharAt(last);
    }
  }

  private static void writeBaseFields(StringBuilder sb, HeliosEvent event) {
    writeKey(sb, "at");
    sb.append('"').append(event.at().toString()).append('"');
    sb.append(',');
    writeKey(sb, "runId");
    sb.append('"').append(event.runId().toString()).append('"');
    sb.append(',');
    writeKey(sb, "spanId");
    if (event.spanId().isPresent()) {
      sb.append('"').append(event.spanId().get().toString()).append('"');
    } else {
      sb.append("null");
    }
    sb.append(',');
  }

  private static void appendString(StringBuilder sb, String key, String value) {
    writeKey(sb, key);
    writeQuotedString(sb, value);
    sb.append(',');
  }

  private static void appendBoolean(StringBuilder sb, String key, boolean value) {
    writeKey(sb, key);
    sb.append(value ? "true" : "false");
    sb.append(',');
  }

  private static void appendNumber(StringBuilder sb, String key, long value) {
    writeKey(sb, key);
    sb.append(value);
    sb.append(',');
  }

  private static void appendNumber(StringBuilder sb, String key, double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("Cannot encode non-finite number: " + value);
    }
    writeKey(sb, key);
    sb.append(value);
    sb.append(',');
  }

  private static void appendOptionalString(
      StringBuilder sb, String key, java.util.Optional<String> value) {
    writeKey(sb, key);
    if (value.isPresent()) {
      writeQuotedString(sb, value.get());
    } else {
      sb.append("null");
    }
    sb.append(',');
  }

  private static void appendStringMap(StringBuilder sb, String key, Map<String, String> map) {
    writeKey(sb, key);
    sb.append('{');
    var first = true;
    for (var entry : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      writeQuotedString(sb, entry.getKey());
      sb.append(':');
      if (entry.getValue() == null) {
        sb.append("null");
      } else {
        writeQuotedString(sb, entry.getValue());
      }
    }
    sb.append('}');
    sb.append(',');
  }

  private static void appendObjectMap(StringBuilder sb, String key, Map<String, Object> map) {
    writeKey(sb, key);
    sb.append('{');
    var first = true;
    for (var entry : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      writeQuotedString(sb, entry.getKey());
      sb.append(':');
      writeAnyValue(sb, entry.getValue());
    }
    sb.append('}');
    sb.append(',');
  }

  private static void appendDoubleArray(StringBuilder sb, String key, double[] values) {
    writeKey(sb, key);
    sb.append('[');
    for (var i = 0; i < values.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      var v = values[i];
      if (Double.isNaN(v) || Double.isInfinite(v)) {
        throw new IllegalArgumentException("Cannot encode non-finite number at index " + i);
      }
      sb.append(v);
    }
    sb.append(']');
    sb.append(',');
  }

  private static void writeAnyValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Boolean b) {
      sb.append(b);
    } else if (value instanceof Number n) {
      var d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        writeQuotedString(sb, n.toString());
      } else {
        sb.append(n);
      }
    } else if (value instanceof CharSequence cs) {
      writeQuotedString(sb, cs.toString());
    } else {
      writeQuotedString(sb, value.toString());
    }
  }

  private static void writeKey(StringBuilder sb, String key) {
    sb.append('"').append(key).append('"').append(':');
  }

  private static void writeQuotedString(StringBuilder sb, String s) {
    sb.append('"');
    for (var i = 0; i < s.length(); i++) {
      var c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
