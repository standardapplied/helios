/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.persistence.PgAnnotationStore;
import com.standardapplied.helios.persistence.PgConfig;
import com.standardapplied.helios.persistence.PgPromptRegistry;
import com.standardapplied.helios.persistence.PgSessionStore;
import com.standardapplied.helios.persistence.PgSpanExporter;
import com.standardapplied.helios.persistence.PgTraceStore;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import io.helidon.dbclient.DbClient;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

/**
 * The {@code docs/observability-and-persistence.md} blocks, one method per block, so the build
 * compiles every one of them. A block's free variables are its method's parameters. {@link
 * QuickStartsDocsTest} holds each block to its method's body; none of these methods runs.
 */
final class ObservabilitySnippets {

  /** Where {@link #auditEvents} logs. */
  interface Log {

    void info(String format, Object... arguments);
  }

  /** Where {@link #auditEvents} records context fill. */
  interface Metrics {

    void recordContextFill(double usagePct);
  }

  /** Where {@link #auditEvents} sends a run's terminal. */
  interface AuditSink {

    void flush(ResultMessage result);
  }

  private ObservabilitySnippets() {}

  static void auditEvents(AgentSession session, Log log, Metrics metrics, AuditSink auditSink) {
    try (var events = session.events()) {
      for (var ev : events) {
        switch (ev) {
          case QueryEvent.ToolUse t ->
              log.info("tool: {} args={}", t.call().name(), t.call().arguments());
          case QueryEvent.ContextWarning w -> metrics.recordContextFill(w.usagePct());
          case QueryEvent.RunEnded e -> auditSink.flush(e.result());
          default -> {}
        }
      }
    }
  }

  static void tracing(PgConfig pgConfig, SessionOptions.Builder options) {
    var tracing =
        SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(new PgSpanExporter(pgConfig)).build())
            .build();
    options.withOpenTelemetry(OpenTelemetrySdk.builder().setTracerProvider(tracing).build());
  }

  static void hostSpan(Tracer tracer, AgentSession session, UserMessage message) {
    var span = tracer.spanBuilder("answer").startSpan();
    try (var ignored = span.makeCurrent()) {
      var result = session.send(message).join();
      span.setAttribute("helios.run.id", result.runId());
      span.setAttribute("app.reply.fallback", render(result));
    } finally {
      span.end();
    }
  }

  static void persistence(DbClient dbClient) {
    var pgConfig = PgConfig.newBuilder().withDbClient(dbClient).withSchema("helios").build();

    var spanExporter = new PgSpanExporter(pgConfig);
    var traceStore = new PgTraceStore(pgConfig);
    var annotationStore = new PgAnnotationStore(pgConfig);
    var promptRegistry = new PgPromptRegistry(pgConfig);
    var sessionStore = new PgSessionStore(pgConfig);
  }

  private static boolean render(ResultMessage result) {
    return !(result instanceof ResultMessage.Success);
  }
}
