/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import com.standardapplied.helios.core.events.HeliosEvent.CompactionTriggered;
import com.standardapplied.helios.core.events.HeliosEvent.Custom;
import com.standardapplied.helios.core.events.HeliosEvent.OptimizerCandidateProposed;
import com.standardapplied.helios.core.events.HeliosEvent.OptimizerCandidateScored;
import com.standardapplied.helios.core.events.HeliosEvent.SpanClosed;
import com.standardapplied.helios.core.events.HeliosEvent.SpanOpened;
import com.standardapplied.helios.core.events.HeliosEvent.SubAgentCompleted;
import com.standardapplied.helios.core.events.HeliosEvent.SubAgentStarted;

/**
 * Encoders for span, sub-agent, compaction and optimizer events, and the {@link Custom} escape
 * hatch. Names, error messages and custom data are written only in full detail.
 */
final class ObservabilityEventEncoders {

  private ObservabilityEventEncoders() {}

  static void spanOpened(SpanOpened e, JsonObjectWriter json, boolean full) {
    json.string("openedSpanId", e.openedSpanId().toString())
        .optionalString("parentSpanId", e.parentSpanId().map(Object::toString));
    if (full) {
      json.string("name", e.name());
    }
  }

  static void spanClosed(SpanClosed e, JsonObjectWriter json, boolean full) {
    json.string("closedSpanId", e.closedSpanId().toString())
        .number("durationNanos", e.duration().toNanos())
        .bool("success", e.success());
    if (full) {
      json.optionalString("error", e.error());
    } else if (!e.success()) {
      json.string("errorCategory", "span_failed");
    }
  }

  static void subAgentStarted(SubAgentStarted e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("subAgentName", e.subAgentName());
    }
    json.string("parentSpanId", e.parentSpanId().toString());
  }

  static void subAgentCompleted(SubAgentCompleted e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("subAgentName", e.subAgentName());
    }
    json.number("durationNanos", e.duration().toNanos());
  }

  static void compactionTriggered(CompactionTriggered e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("phase", e.phase());
    }
    json.number("beforeTokens", e.beforeTokens()).number("afterTokens", e.afterTokens());
  }

  static void optimizerCandidateProposed(
      OptimizerCandidateProposed e, JsonObjectWriter json, boolean full) {
    json.string("candidateId", e.candidateId().toString())
        .optionalString("parentCandidateId", e.parentCandidateId().map(Object::toString));
    if (full) {
      json.string("source", e.source());
    }
  }

  static void optimizerCandidateScored(
      OptimizerCandidateScored e, JsonObjectWriter json, boolean full) {
    json.string("candidateId", e.candidateId().toString())
        .number("aggregateScore", e.aggregateScore())
        .doubleArray("perInstanceScores", e.perInstanceScores());
  }

  static void custom(Custom e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("kind", e.kind()).map("data", e.data());
    }
  }
}
