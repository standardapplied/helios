/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import com.standardapplied.helios.core.events.HeliosEvent.AfterTurn;
import com.standardapplied.helios.core.events.HeliosEvent.AssistantText;
import com.standardapplied.helios.core.events.HeliosEvent.AssistantTextDelta;
import com.standardapplied.helios.core.events.HeliosEvent.AssistantThinkingComplete;
import com.standardapplied.helios.core.events.HeliosEvent.AssistantThinkingDelta;
import com.standardapplied.helios.core.events.HeliosEvent.BeforeApiCall;
import com.standardapplied.helios.core.events.HeliosEvent.BeforeCompaction;
import com.standardapplied.helios.core.events.HeliosEvent.CompactionTriggered;
import com.standardapplied.helios.core.events.HeliosEvent.Custom;
import com.standardapplied.helios.core.events.HeliosEvent.IterationCompleted;
import com.standardapplied.helios.core.events.HeliosEvent.IterationStarted;
import com.standardapplied.helios.core.events.HeliosEvent.MemoryRead;
import com.standardapplied.helios.core.events.HeliosEvent.MemoryWritten;
import com.standardapplied.helios.core.events.HeliosEvent.OptimizerCandidateProposed;
import com.standardapplied.helios.core.events.HeliosEvent.OptimizerCandidateScored;
import com.standardapplied.helios.core.events.HeliosEvent.RunCompleted;
import com.standardapplied.helios.core.events.HeliosEvent.RunFailed;
import com.standardapplied.helios.core.events.HeliosEvent.RunStarted;
import com.standardapplied.helios.core.events.HeliosEvent.SessionEnd;
import com.standardapplied.helios.core.events.HeliosEvent.SpanClosed;
import com.standardapplied.helios.core.events.HeliosEvent.SpanOpened;
import com.standardapplied.helios.core.events.HeliosEvent.SubAgentCompleted;
import com.standardapplied.helios.core.events.HeliosEvent.SubAgentStarted;
import com.standardapplied.helios.core.events.HeliosEvent.ToolCallCompleted;
import com.standardapplied.helios.core.events.HeliosEvent.ToolCallFailed;
import com.standardapplied.helios.core.events.HeliosEvent.ToolCallStarted;
import java.util.UUID;

/**
 * Package-private JSONL encoder for {@link HeliosEvent}. Hand-rolled to keep {@code core}
 * dependency-free.
 *
 * <p>The format is a deliberately narrow subset of JSON: each event is a single-line JSON object
 * with the base fields, a {@code type} discriminator naming the subtype, then variant-specific
 * fields written by that subtype's encoder. Full encoding keeps content for replay; metadata-only
 * encoding keeps identifiers, counts and outcomes. Numbers are emitted as their canonical Java
 * string (finite values only); strings are escaped per RFC 8259; maps are encoded as objects with
 * string keys and best-effort {@code toString()} for non-primitive Object values.
 */
final class EventJsonWriter {

  private EventJsonWriter() {}

  static String encode(HeliosEvent event) {
    return write(event, true);
  }

  static String encodeMetadataOnly(HeliosEvent event) {
    return write(event, false);
  }

  private static String write(HeliosEvent event, boolean full) {
    var json =
        new JsonObjectWriter()
            .string("at", event.at().toString())
            .string("runId", event.runId().toString())
            .optionalString("spanId", event.spanId().map(UUID::toString))
            .string("type", event.getClass().getSimpleName());
    switch (event) {
      case RunStarted e -> RunEventEncoders.runStarted(e, json, full);
      case RunCompleted e -> RunEventEncoders.runCompleted(e, json, full);
      case RunFailed e -> RunEventEncoders.runFailed(e, json, full);
      case IterationStarted e -> LoopEventEncoders.iterationStarted(e, json, full);
      case IterationCompleted e -> LoopEventEncoders.iterationCompleted(e, json, full);
      case BeforeApiCall e -> LoopEventEncoders.beforeApiCall(e, json, full);
      case AfterTurn e -> LoopEventEncoders.afterTurn(e, json, full);
      case BeforeCompaction e -> LoopEventEncoders.beforeCompaction(e, json, full);
      case SessionEnd e -> LoopEventEncoders.sessionEnd(e, json, full);
      case AssistantTextDelta e -> ContentEventEncoders.assistantTextDelta(e, json, full);
      case AssistantText e -> ContentEventEncoders.assistantText(e, json, full);
      case AssistantThinkingDelta e -> ContentEventEncoders.assistantThinkingDelta(e, json, full);
      case AssistantThinkingComplete e ->
          ContentEventEncoders.assistantThinkingComplete(e, json, full);
      case ToolCallStarted e -> ContentEventEncoders.toolCallStarted(e, json, full);
      case ToolCallCompleted e -> ContentEventEncoders.toolCallCompleted(e, json, full);
      case ToolCallFailed e -> ContentEventEncoders.toolCallFailed(e, json, full);
      case MemoryWritten e -> ContentEventEncoders.memoryWritten(e, json, full);
      case MemoryRead e -> ContentEventEncoders.memoryRead(e, json, full);
      case SpanOpened e -> ObservabilityEventEncoders.spanOpened(e, json, full);
      case SpanClosed e -> ObservabilityEventEncoders.spanClosed(e, json, full);
      case SubAgentStarted e -> ObservabilityEventEncoders.subAgentStarted(e, json, full);
      case SubAgentCompleted e -> ObservabilityEventEncoders.subAgentCompleted(e, json, full);
      case CompactionTriggered e -> ObservabilityEventEncoders.compactionTriggered(e, json, full);
      case OptimizerCandidateProposed e ->
          ObservabilityEventEncoders.optimizerCandidateProposed(e, json, full);
      case OptimizerCandidateScored e ->
          ObservabilityEventEncoders.optimizerCandidateScored(e, json, full);
      case Custom e -> ObservabilityEventEncoders.custom(e, json, full);
    }
    return json.toString();
  }
}
