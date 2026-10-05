/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import com.standardapplied.helios.core.events.HeliosEvent.RunCompleted;
import com.standardapplied.helios.core.events.HeliosEvent.RunFailed;
import com.standardapplied.helios.core.events.HeliosEvent.RunStarted;
import com.standardapplied.helios.core.trace.Trace;
import java.util.Map;
import java.util.Set;

/** Encoders for the run-lifecycle events and the trace summary they carry. */
final class RunEventEncoders {

  private static final Set<String> KNOWN_API_VERSIONS = Set.of("v1", "v1beta", "custom");

  private RunEventEncoders() {}

  static void runStarted(RunStarted e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("harnessKind", e.harnessKind()).map("attributes", e.attributes());
    } else {
      apiVersion(json, e.attributes());
    }
  }

  static void runCompleted(RunCompleted e, JsonObjectWriter json, boolean full) {
    json.raw("trace", full ? traceSummary(e.trace()) : metadataTraceSummary(e.trace(), true));
  }

  static void runFailed(RunFailed e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("error", e.error()).raw("trace", traceSummary(e.trace()));
    } else {
      json.string("errorCategory", "run_failed")
          .raw("trace", metadataTraceSummary(e.trace(), false));
    }
  }

  /**
   * A compact summary of the trace — id, duration, top-level span count, total tokens. The full
   * nested span tree is intentionally omitted from JSONL to keep one event per line tractable.
   * Consumers needing the full {@code Trace} use programmatic {@link EventSink} subscription, not
   * JSONL replay.
   */
  private static String traceSummary(Trace trace) {
    var json = new JsonObjectWriter().string("id", trace.id().toString());
    if (trace.duration() == null) {
      json.raw("durationNanos", "null");
    } else {
      json.number("durationNanos", trace.duration().toNanos());
    }
    return json.number("spanCount", trace.spans().size())
        .number("totalTokens", trace.totalTokens())
        .toString();
  }

  private static String metadataTraceSummary(Trace trace, boolean success) {
    var json = new JsonObjectWriter().string("id", trace.id().toString());
    if (trace.duration() != null) {
      json.number("durationNanos", trace.duration().toNanos());
    }
    json.number("spanCount", trace.spans().size()).bool("success", success);
    if (trace.modelId() != null) {
      json.string("modelId", trace.modelId());
    }
    apiVersion(json, trace.attributes());
    var usage = trace.usage();
    if (usage != null) {
      json.number("inputTokens", usage.inputTokens())
          .number("outputTokens", usage.outputTokens())
          .number("cacheCreationInputTokens", usage.cacheCreationInputTokens())
          .number("cacheReadInputTokens", usage.cacheReadInputTokens())
          .number("totalTokens", usage.totalTokens());
    } else {
      json.number("totalTokens", trace.totalTokens());
    }
    return json.toString();
  }

  private static void apiVersion(JsonObjectWriter json, Map<String, String> attributes) {
    var apiVersion = attributes.get("gemini.apiVersion");
    if (apiVersion == null) {
      apiVersion = attributes.get("apiVersion");
    }
    if (apiVersion != null && KNOWN_API_VERSIONS.contains(apiVersion)) {
      json.string("apiVersion", apiVersion);
    }
  }
}
