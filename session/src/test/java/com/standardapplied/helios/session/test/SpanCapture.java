/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An OpenTelemetry SDK that keeps every span it ends in memory, exported by a {@link
 * SimpleSpanProcessor}, so a span can be read as soon as the call that ended it returned: a
 * session's run span once the run's result completed. It also tracks the spans started and not
 * ended yet.
 */
public final class SpanCapture {

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final Set<String> open = ConcurrentHashMap.newKeySet();
  private final OpenTelemetrySdk sdk;

  public SpanCapture() {
    sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(new OpenSpans())
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();
  }

  /** The SDK to hand a session's {@code withOpenTelemetry}. */
  public OpenTelemetry openTelemetry() {
    return sdk;
  }

  /** A tracer of the application's own, outside Helios's instrumentation scope. */
  public Tracer tracer() {
    return sdk.getTracer("application");
  }

  /** Every span ended so far, in the order they ended. */
  public List<SpanData> spans() {
    return exporter.getFinishedSpanItems();
  }

  /** The ended spans named {@code name}, in the order they ended. */
  public List<SpanData> named(String name) {
    return spans().stream().filter(span -> span.getName().equals(name)).toList();
  }

  /** The one ended span named {@code name}. */
  public SpanData only(String name) {
    var named = named(name);
    assertEquals(1, named.size(), () -> name + " among " + names());
    return named.getFirst();
  }

  /** The ended spans whose name starts with {@code prefix}, in the order they ended. */
  public List<SpanData> starting(String prefix) {
    return spans().stream().filter(span -> span.getName().startsWith(prefix)).toList();
  }

  /** The one ended run span. */
  public SpanData run() {
    var runs = starting("invoke_agent ");
    assertEquals(1, runs.size(), () -> "run spans among " + names());
    return runs.getFirst();
  }

  /** The names of the ended spans, in the order they ended. */
  public List<String> names() {
    return spans().stream().map(SpanData::getName).toList();
  }

  /** The ids of the spans started and not ended yet. */
  public Set<String> unended() {
    return Set.copyOf(open);
  }

  /** Tracks the spans started and not ended yet. */
  private final class OpenSpans implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
      open.add(span.getSpanContext().getSpanId());
    }

    @Override
    public boolean isStartRequired() {
      return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
      open.remove(span.getSpanContext().getSpanId());
    }

    @Override
    public boolean isEndRequired() {
      return true;
    }
  }
}
