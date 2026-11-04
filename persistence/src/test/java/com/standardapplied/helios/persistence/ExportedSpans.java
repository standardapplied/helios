/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Builds the finished spans a session hands {@link PgSpanExporter}: one trace id, the Helios
 * instrumentation scope, each span starting a given number of seconds after {@link #START} and
 * lasting one second.
 */
final class ExportedSpans {

  static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  static final OffsetDateTime START = OffsetDateTime.of(2026, 11, 3, 10, 0, 0, 0, ZoneOffset.UTC);
  static final InstrumentationScopeInfo HELIOS =
      InstrumentationScopeInfo.create("com.standardapplied.helios");

  private ExportedSpans() {}

  /** A run's {@code invoke_agent} span named for {@code agent}, plus {@code attributes}. */
  static SpanData run(String runId, String spanId, String agent, int second, Attributes more) {
    var attributes =
        Attributes.builder()
            .put("gen_ai.operation.name", "invoke_agent")
            .put("gen_ai.agent.name", agent)
            .put("helios.run.id", runId)
            .putAll(more)
            .build();
    return span(spanId, "invoke_agent " + agent, second, attributes).build();
  }

  /** A step span of {@code runId}, its {@code gen_ai.operation.name} {@code operation}, if any. */
  static SpanData step(
      String runId, String spanId, String name, String operation, int second, Attributes more) {
    var attributes = Attributes.builder().put("helios.run.id", runId).putAll(more);
    if (operation != null) {
      attributes.put("gen_ai.operation.name", operation);
    }
    return span(spanId, name, second, attributes.build()).build();
  }

  /** An ended Helios-scope span, unset status, with {@code attributes}. */
  static TestSpanData.Builder span(String spanId, String name, int second, Attributes attributes) {
    var start = START.plusSeconds(second).toInstant();
    return TestSpanData.builder()
        .setSpanContext(
            SpanContext.create(TRACE_ID, spanId, TraceFlags.getSampled(), TraceState.getDefault()))
        .setInstrumentationScopeInfo(HELIOS)
        .setName(name)
        .setKind(SpanKind.INTERNAL)
        .setStartEpochNanos(nanos(start.getEpochSecond()))
        .setEndEpochNanos(nanos(start.getEpochSecond() + 1))
        .setAttributes(attributes)
        .setStatus(StatusData.unset())
        .setHasEnded(true);
  }

  private static long nanos(long epochSecond) {
    return Math.multiplyExact(epochSecond, 1_000_000_000L);
  }
}
