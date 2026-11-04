/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import static com.standardapplied.helios.persistence.ExportedSpans.START;
import static com.standardapplied.helios.persistence.ExportedSpans.TRACE_ID;
import static com.standardapplied.helios.persistence.ExportedSpans.run;
import static com.standardapplied.helios.persistence.ExportedSpans.span;
import static com.standardapplied.helios.persistence.ExportedSpans.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.session.test.LogCapture;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link PgSpanExporter} writes each Helios run span as a {@code helios_traces} row and each other
 * Helios span as a {@code helios_spans} row, one batch per transaction, and ignores every other
 * span.
 */
final class PgSpanExporterTest {

  private static final String RUN = "run-1";
  private static final String SECRET = "sk-exported-secret-01234";

  private final PgTraceStore store = new PgTraceStore(PgTestSupport.pgConfig());
  private final PgSpanExporter exporter = new PgSpanExporter(PgTestSupport.pgConfig());

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateTraces();
  }

  private static long rows(String table) {
    return PgTestSupport.dbClient()
        .execute()
        .query("SELECT COUNT(*) AS n FROM " + table)
        .findFirst()
        .orElseThrow()
        .column("n")
        .getLong();
  }

  private static Attributes usage(long input, long output, long write, long read, long total) {
    var usage =
        Attributes.builder()
            .put("gen_ai.usage.input_tokens", input)
            .put("gen_ai.usage.output_tokens", output)
            .put("helios.usage.total_tokens", total)
            .put("helios.cost.micro_usd", 42L);
    if (write != 0) {
      usage.put("gen_ai.usage.cache_write.input_tokens", write);
    }
    if (read != 0) {
      usage.put("gen_ai.usage.cache_read.input_tokens", read);
    }
    return usage.build();
  }

  private static SpanData runSpan(String runId) {
    return run(
        runId,
        "00f067aa0ba902b7",
        "matchmaking",
        0,
        Attributes.builder()
            .put("gen_ai.conversation.id", "sess-1")
            .put("gen_ai.request.model", "scripted")
            .put("gen_ai.provider.name", "testing")
            .put("helios.terminal", "Success")
            .putAll(usage(180, 22, 30, 50, 202))
            .build());
  }

  @Test
  void aRunAndItsStepsRoundTripWithTheirIdsUsageAndKinds() {
    var chat =
        step(
            RUN,
            "1000000000000001",
            "chat scripted",
            "chat",
            1,
            Attributes.builder()
                .put(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"), List.of("stop"))
                .put("helios.attempt", 1L)
                .putAll(usage(180, 22, 30, 50, 202))
                .build());
    var tool =
        step(
            RUN,
            "1000000000000002",
            "execute_tool noop",
            "execute_tool",
            2,
            Attributes.of(AttributeKey.stringKey("gen_ai.tool.name"), "noop"));
    var hook =
        step(
            RUN,
            "1000000000000003",
            "hook guard",
            null,
            2,
            Attributes.of(AttributeKey.stringKey("helios.hook.phase"), "PRE_STOP"));

    var exported = exporter.export(List.of(hook, tool, chat, runSpan(RUN)));

    assertTrue(exported.isSuccess());
    var found = store.findById(RUN);
    assertEquals(RUN, found.id());
    assertEquals(TRACE_ID, found.otelTraceId());
    assertEquals("00f067aa0ba902b7", found.otelSpanId());
    assertEquals("matchmaking", found.name());
    assertEquals("sess-1", found.sessionId());
    assertEquals("scripted", found.modelId());
    assertEquals(START, found.startTime());
    assertEquals(START.plusSeconds(1), found.endTime());
    assertTrue(found.success());
    assertEquals(Usage.of(100, 22, 30, 50), found.usage());
    assertEquals(202, found.totalTokens());
    assertEquals(CostEstimate.ofMicroUsd(42), found.cost());
    assertEquals(
        Map.of(
            "gen_ai.operation.name", "invoke_agent",
            "gen_ai.provider.name", "testing",
            "helios.terminal", "Success"),
        found.attributes());
    assertNull(found.inputText());
    assertNull(found.outputText());
    assertEquals(
        List.of("chat scripted", "execute_tool noop", "hook guard"),
        found.spans().stream().map(Span::name).toList());
    assertEquals(
        List.of("chat", "execute_tool", "hook"), found.spans().stream().map(Span::kind).toList());
    var chatRow = found.spans().getFirst();
    assertEquals("1000000000000001", chatRow.id());
    assertEquals(Usage.of(100, 22, 30, 50), chatRow.usage());
    assertEquals(CostEstimate.ofMicroUsd(42), chatRow.cost());
    assertEquals(
        Map.of("gen_ai.response.finish_reasons", "[stop]", "helios.attempt", "1"),
        chatRow.attributes());
    var toolRow = found.spans().get(1);
    assertNull(toolRow.usage());
    assertNull(toolRow.cost());
    assertEquals(Map.of("gen_ai.tool.name", "noop"), toolRow.attributes());
    assertEquals(Map.of("helios.hook.phase", "PRE_STOP"), found.spans().get(2).attributes());
  }

  @Test
  void aFailedSpanStoresItsStatusDescriptionAndAVerboseRunItsPlainText() {
    var failed =
        span(
                "1000000000000004",
                "chat scripted",
                1,
                Attributes.of(
                    AttributeKey.stringKey("helios.run.id"),
                    RUN,
                    AttributeKey.stringKey("gen_ai.operation.name"),
                    "chat"))
            .setStatus(StatusData.create(StatusCode.ERROR, "IllegalStateException: broken"))
            .build();
    var run =
        run(
            RUN,
            "00f067aa0ba902b7",
            "helios",
            0,
            Attributes.builder()
                .put("gen_ai.input.messages", messages("user", "hello \"there\""))
                .put("gen_ai.output.messages", messages("assistant", "done"))
                .build());

    assertTrue(exporter.export(List.of(failed, run)).isSuccess());

    var found = store.findById(RUN);
    assertEquals("hello \"there\"", found.inputText());
    assertEquals("done", found.outputText());
    assertFalse(found.attributes().containsKey("gen_ai.input.messages"));
    assertEquals(0, found.totalTokens());
    assertNull(found.usage());
    assertEquals("IllegalStateException: broken", found.spans().getFirst().error());
    assertFalse(found.spans().getFirst().success());
  }

  private static String messages(String role, String text) {
    return "[{\"role\":\""
        + role
        + "\",\"parts\":[{\"type\":\"text\",\"content\":"
        + "\""
        + text.replace("\"", "\\\"")
        + "\"}]}]";
  }

  @Test
  void onlyHeliosSpansThatNameTheirRunAreWritten() {
    var application =
        span(
                "2000000000000001",
                "database query",
                1,
                Attributes.of(AttributeKey.stringKey("helios.run.id"), RUN))
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.create("application"))
            .build();
    var anonymous = span("2000000000000002", "chat scripted", 1, Attributes.empty()).build();

    assertTrue(exporter.export(List.of(application, anonymous)).isSuccess());
    assertEquals(0, rows("helios_spans"));

    assertTrue(exporter.export(List.of(application, anonymous, runSpan(RUN))).isSuccess());
    assertEquals(0, rows("helios_spans"));
    assertEquals(1, rows("helios_traces"));
  }

  @Test
  void stepsArriveBeforeTheirRunAndTheRunIsNotFoundUntilItsSpanDoes() {
    var chat = step(RUN, "3000000000000001", "chat scripted", "chat", 1, Attributes.empty());

    assertTrue(exporter.export(List.of(chat)).isSuccess());

    assertEquals(1, rows("helios_spans"));
    assertNull(store.findById(RUN));

    assertTrue(exporter.export(List.of(runSpan(RUN))).isSuccess());

    assertEquals(
        List.of("chat scripted"), store.findById(RUN).spans().stream().map(Span::name).toList());
  }

  @Test
  void aBatchThatFailsMidWriteWritesNothingAndLogsItsRunsRedacted() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var redacting =
        new PgSpanExporter(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withRedactor(secrets.redactor())
                .build());
    var tooLong = "run-" + SECRET + "-".repeat(64);
    var logged = new CopyOnWriteArrayList<LogRecord>();

    try (var ignored = LogCapture.of(PgSpanExporter.class, logged)) {
      var exported =
          redacting.export(
              List.of(
                  step(RUN, "4000000000000001", "chat scripted", "chat", 1, Attributes.empty()),
                  runSpan(tooLong)));

      assertFalse(exported.isSuccess());
    }

    assertEquals(0, rows("helios_spans"));
    assertEquals(0, rows("helios_traces"));
    var warning = logged.getFirst();
    assertEquals(Level.WARNING, warning.getLevel());
    assertTrue(warning.getMessage().contains(RUN), warning.getMessage());
    assertTrue(warning.getMessage().contains("<redacted:key>"), warning.getMessage());
    assertFalse(warning.getMessage().contains(SECRET), warning.getMessage());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aFailedWriteIsLoggedWhetherOrNotItsRollbackFails(boolean rollbackFails) {
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var failing =
        new PgSpanExporter(new PgConfig(PgTestSupport.failingWrites(rollbackFails), null, null));

    try (var ignored = LogCapture.of(PgSpanExporter.class, logged)) {
      assertFalse(failing.export(List.of(runSpan(RUN))).isSuccess());
    }

    var thrown = logged.getFirst().getThrown();
    assertEquals("java.lang.IllegalStateException: dml failed", thrown.getMessage());
  }

  @Test
  void afterShutdownNothingIsWritten() {
    assertTrue(exporter.flush().isSuccess());
    assertTrue(exporter.shutdown().isSuccess());

    assertFalse(exporter.export(List.of(runSpan(RUN))).isSuccess());
    assertEquals(0, rows("helios_traces"));
  }

  @Test
  void aNullConfigIsRejected() {
    assertEquals(
        "config",
        assertThrows(NullPointerException.class, () -> new PgSpanExporter(null)).getMessage());
  }

  @Test
  void textAndAttributeValuesPassThroughTheRedactor() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var redacting =
        new PgSpanExporter(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withRedactor(secrets.redactor())
                .build());
    var failed =
        span(
                "5000000000000001",
                "execute_tool echo",
                1,
                Attributes.builder()
                    .put("helios.run.id", RUN)
                    .put("gen_ai.operation.name", "execute_tool")
                    .put("gen_ai.tool.call.arguments", "{\"q\":\"" + SECRET + "\"}")
                    .build())
            .setStatus(StatusData.create(StatusCode.ERROR, "leaked " + SECRET))
            .build();
    var run =
        run(
            RUN,
            "00f067aa0ba902b7",
            "helios",
            0,
            Attributes.of(
                AttributeKey.stringKey("gen_ai.input.messages"), messages("user", SECRET)));

    assertTrue(redacting.export(List.of(failed, run)).isSuccess());

    var found = store.findById(RUN);
    assertEquals("<redacted:key>", found.inputText());
    var tool = found.spans().getFirst();
    assertEquals("leaked <redacted:key>", tool.error());
    assertEquals("{\"q\":\"<redacted:key>\"}", tool.attributes().get("gen_ai.tool.call.arguments"));
  }
}
