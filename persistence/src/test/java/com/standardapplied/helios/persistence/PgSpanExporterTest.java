/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import static com.standardapplied.helios.persistence.ExportedSpans.START;
import static com.standardapplied.helios.persistence.ExportedSpans.TRACE_ID;
import static com.standardapplied.helios.persistence.ExportedSpans.host;
import static com.standardapplied.helios.persistence.ExportedSpans.run;
import static com.standardapplied.helios.persistence.ExportedSpans.span;
import static com.standardapplied.helios.persistence.ExportedSpans.step;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link PgSpanExporter} writes each Helios run span as a {@code helios_traces} row and each other
 * span that names its run, Helios's or a host's, as a {@code helios_spans} row, one batch per
 * transaction, and ignores every span that names no run.
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

  /** Connections other than this query's that hold a transaction open. */
  private static long openTransactions() {
    return PgTestSupport.dbClient()
        .execute()
        .query(
            "SELECT COUNT(*) AS n FROM pg_stat_activity"
                + " WHERE state LIKE 'idle in transaction%' AND pid <> pg_backend_pid()")
        .findFirst()
        .orElseThrow()
        .column("n")
        .getLong();
  }

  private static Attributes usage(long input, long output, long write, long read) {
    var usage =
        Attributes.builder()
            .put("gen_ai.usage.input_tokens", input)
            .put("gen_ai.usage.output_tokens", output)
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
            .putAll(usage(180, 22, 30, 50))
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
                .put("helios.turn.index", 1L)
                .putAll(usage(180, 22, 30, 50))
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
    assertEquals(START.plusSeconds(1), chatRow.startTime());
    assertEquals(START.plusSeconds(2), chatRow.endTime());
    assertEquals(Usage.of(100, 22, 30, 50), chatRow.usage());
    assertEquals(CostEstimate.ofMicroUsd(42), chatRow.cost());
    assertEquals(
        Map.of(
            "gen_ai.response.finish_reasons",
            "[stop]",
            "helios.attempt",
            "1",
            "helios.turn.index",
            "1"),
        chatRow.attributes());
    var toolRow = found.spans().get(1);
    assertNull(toolRow.usage());
    assertNull(toolRow.cost());
    assertEquals(Map.of("gen_ai.tool.name", "noop"), toolRow.attributes());
    assertEquals(Map.of("helios.hook.phase", "PRE_STOP"), found.spans().get(2).attributes());
  }

  @Test
  void theReasoningCountOfARunAndOfItsChatStepIsStoredAndReadBack() {
    var reasoning = "gen_ai.usage.reasoning.output_tokens";
    var chat =
        step(
            RUN,
            "1000000000000001",
            "chat scripted",
            "chat",
            1,
            Attributes.builder().putAll(usage(180, 22, 30, 50)).put(reasoning, 9L).build());
    var run =
        run(
            RUN,
            "00f067aa0ba902b7",
            "matchmaking",
            0,
            Attributes.builder().putAll(usage(180, 22, 30, 50)).put(reasoning, 12L).build());

    assertTrue(exporter.export(List.of(chat, run)).isSuccess());

    var found = store.findById(RUN);
    assertEquals(Usage.of(100, 22, 30, 50, 12), found.usage());
    assertEquals(Usage.of(100, 22, 30, 50, 9), found.spans().getFirst().usage());
    assertFalse(found.attributes().containsKey(reasoning));
    assertFalse(found.spans().getFirst().attributes().containsKey(reasoning));
  }

  @Test
  void aHostSpansReasoningCountAboveItsOutputCountIsCutToItWithoutFailingTheBatch() {
    var reasoning = "gen_ai.usage.reasoning.output_tokens";
    var overReported =
        host(
                RUN,
                "8000000000000001",
                "host chat",
                1,
                Attributes.builder().putAll(usage(10, 4, 0, 0)).put(reasoning, 9L).build())
            .build();
    var withoutOutput =
        host(
                RUN,
                "8000000000000002",
                "host summary",
                2,
                Attributes.builder()
                    .put("gen_ai.usage.input_tokens", 10L)
                    .put(reasoning, 9L)
                    .build())
            .build();

    assertTrue(exporter.export(List.of(overReported, withoutOutput, runSpan(RUN))).isSuccess());

    var found = store.findById(RUN);
    assertEquals(Usage.of(100, 22, 30, 50), found.usage());
    assertEquals(
        List.of(Usage.of(10, 4, 0, 0, 4), Usage.of(10, 0)),
        found.spans().stream().map(Span::usage).toList());
  }

  @Test
  void aStepWithAnOkStatusAndNoUsageStoresNoErrorAndNoTokenColumn() {
    var ok =
        span(
                "6000000000000001",
                "execute_tool noop",
                1,
                Attributes.builder()
                    .put("helios.run.id", RUN)
                    .put("gen_ai.operation.name", "execute_tool")
                    .build())
            .setStatus(StatusData.ok())
            .build();

    assertTrue(exporter.export(List.of(ok, runSpan(RUN))).isSuccess());

    var tool = store.findById(RUN).spans().getFirst();
    assertNull(tool.error());
    assertNull(
        PgTestSupport.dbClient()
            .execute()
            .query("SELECT total_tokens FROM helios_spans WHERE id = ?", "6000000000000001")
            .findFirst()
            .orElseThrow()
            .column("total_tokens")
            .get(Object.class));
  }

  @Test
  void aFailedRunStoresItsStatusDescription() {
    var failed =
        span(
                "00f067aa0ba902b7",
                "invoke_agent helios",
                0,
                Attributes.builder()
                    .put("gen_ai.operation.name", "invoke_agent")
                    .put("gen_ai.agent.name", "helios")
                    .put("helios.run.id", RUN)
                    .build())
            .setStatus(StatusData.create(StatusCode.ERROR, "Cancelled"))
            .build();

    assertTrue(exporter.export(List.of(failed)).isSuccess());

    var found = store.findById(RUN);
    assertEquals("Cancelled", found.error());
    assertFalse(found.success());
  }

  @Test
  void everyRowIsWrittenToTheConfiguredSchema() {
    var elsewhere =
        new PgSpanExporter(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withSchema("no_such_schema")
                .build());
    var chat = step(RUN, "7000000000000001", "chat scripted", "chat", 1, Attributes.empty());

    assertFalse(elsewhere.export(List.of(runSpan(RUN))).isSuccess());
    assertFalse(elsewhere.export(List.of(chat)).isSuccess());
    assertEquals(0, rows("helios_traces"));
    assertEquals(0, rows("helios_spans"));
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
    assertEquals(Map.of("gen_ai.operation.name", "invoke_agent"), found.attributes());
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
  void aNulCharacterIsStoredAsTheReplacementCharacterWithoutFailingTheBatch() {
    var failed =
        span(
                "6000000000000001",
                "execute_tool cat\u0000",
                1,
                Attributes.builder()
                    .put("helios.run.id", RUN)
                    .put("gen_ai.operation.name", "execute_tool")
                    .put("gen_ai.tool.call.result", "ELF\u0000\u0001")
                    .build())
            .setStatus(StatusData.create(StatusCode.ERROR, "Threw: bad\u0000path"))
            .build();
    var run =
        run(
            RUN,
            "00f067aa0ba902b7",
            "helios",
            0,
            Attributes.builder()
                .put("gen_ai.input.messages", messages("user", "a\\u0000b"))
                .put("gen_ai.output.messages", messages("assistant", "done\\u0000"))
                .build());

    assertTrue(exporter.export(List.of(failed, run, runSpan("run-2"))).isSuccess());

    var found = store.findById(RUN);
    assertEquals("a\uFFFDb", found.inputText());
    assertEquals("done\uFFFD", found.outputText());
    var tool = found.spans().getFirst();
    assertEquals("execute_tool cat\uFFFD", tool.name());
    assertEquals("Threw: bad\uFFFDpath", tool.error());
    assertEquals("ELF\uFFFD\u0001", tool.attributes().get("gen_ai.tool.call.result"));
    assertEquals("run-2", store.findById("run-2").id());
  }

  @Test
  void aRunAndAStepWithNamesLongerThan255CharactersAreStored() {
    var agent = "a".repeat(300);
    var toolName = "execute_tool " + "t".repeat(300);
    var tool =
        span(
                "7000000000000001",
                toolName,
                1,
                Attributes.builder()
                    .put("helios.run.id", RUN)
                    .put("gen_ai.operation.name", "execute_tool")
                    .build())
            .build();

    assertTrue(
        exporter
            .export(List.of(tool, run(RUN, "00f067aa0ba902b7", agent, 0, Attributes.empty())))
            .isSuccess());

    var found = store.findById(RUN);
    assertEquals(agent, found.name());
    assertEquals(toolName, found.spans().getFirst().name());
  }

  @Test
  void aSpanThatNamesNoRunIsNotStoredWhateverItsScope() {
    var application =
        span("2000000000000001", "database query", 1, Attributes.empty())
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
  void aHostSpanEndedAfterItsRunIsStoredAfterTheRunsOwnSpansWithItsScopeAttributesAndStatus() {
    var chat = step(RUN, "1000000000000001", "chat scripted", "chat", 1, Attributes.empty());
    assertTrue(exporter.export(List.of(chat, runSpan(RUN))).isSuccess());
    var outcome =
        host(
                RUN,
                "8000000000000001",
                "render reply",
                3,
                Attributes.builder()
                    .put("app.reply.fallback", true)
                    .put("app.query.count", 3L)
                    .put("otel.scope.name", "spoofed")
                    .build())
            .setStatus(StatusData.create(StatusCode.ERROR, "parse failed"))
            .build();

    assertTrue(exporter.export(List.of(outcome)).isSuccess());

    var spans = store.findById(RUN).spans();
    assertEquals(List.of("chat scripted", "render reply"), spans.stream().map(Span::name).toList());
    var row = spans.getLast();
    assertEquals("8000000000000001", row.id());
    assertEquals("host", row.kind());
    assertEquals("light-grid", row.scope());
    assertEquals("com.standardapplied.helios", spans.getFirst().scope());
    assertEquals(START.plusSeconds(3), row.startTime());
    assertEquals("parse failed", row.error());
    assertNull(row.usage());
    assertEquals(
        Map.of(
            "app.reply.fallback", "true",
            "app.query.count", "3",
            "otel.scope.name", "light-grid"),
        row.attributes());
  }

  @Test
  void aHostSpanExportedBeforeItsRunRowIsReadBackWithTheRun() {
    var outcome =
        host(RUN, "8000000000000002", "answer", 0, Attributes.of(stringKey("app.mode"), "chat"))
            .setStatus(StatusData.ok())
            .build();

    assertTrue(exporter.export(List.of(outcome)).isSuccess());
    assertNull(store.findById(RUN));
    assertTrue(exporter.export(List.of(runSpan(RUN))).isSuccess());

    var found = store.findById(RUN);
    assertEquals("matchmaking", found.name());
    var row = found.spans().getFirst();
    assertEquals("answer", row.name());
    assertTrue(row.success());
    assertEquals(Map.of("app.mode", "chat", "otel.scope.name", "light-grid"), row.attributes());
  }

  @Test
  void aHostSpanNamedAsARunIsAHostSpanRowNeverTheRunsRow() {
    var impostor =
        host(
                RUN,
                "8000000000000003",
                "invoke_agent host",
                2,
                Attributes.builder()
                    .put("gen_ai.operation.name", "invoke_agent")
                    .put("gen_ai.agent.name", "host")
                    .build())
            .build();

    assertTrue(exporter.export(List.of(runSpan(RUN), impostor)).isSuccess());

    var found = store.findById(RUN);
    assertEquals("matchmaking", found.name());
    assertEquals("00f067aa0ba902b7", found.otelSpanId());
    var row = found.spans().getFirst();
    assertEquals("host", row.kind());
    assertEquals(
        Map.of(
            "gen_ai.operation.name", "invoke_agent",
            "gen_ai.agent.name", "host",
            "otel.scope.name", "light-grid"),
        row.attributes());
  }

  @Test
  void aHostSpansNameAttributesAndStatusAreRedactedAndItsNulsReplaced() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var redacting =
        new PgSpanExporter(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withRedactor(secrets.redactor())
                .build());
    var outcome =
        host(
                RUN,
                "8000000000000004",
                "verify " + SECRET,
                1,
                Attributes.builder()
                    .put("app.reply", "token " + SECRET)
                    .put("app.raw", "a\u0000b")
                    .put("app.key\u0000", "c")
                    .build())
            .setStatus(StatusData.create(StatusCode.ERROR, "rejected " + SECRET))
            .build();

    assertTrue(redacting.export(List.of(outcome, runSpan(RUN))).isSuccess());

    var row = store.findById(RUN).spans().getFirst();
    assertEquals("verify <redacted:key>", row.name());
    assertEquals("rejected <redacted:key>", row.error());
    assertEquals(
        Map.of(
            "app.reply", "token <redacted:key>",
            "app.raw", "a\uFFFDb",
            "app.key\uFFFD", "c",
            "otel.scope.name", "light-grid"),
        row.attributes());
  }

  @Test
  void aBatchWithNoSpanNamingARunSucceedsWithoutTouchingTheDatabase() {
    var unavailable =
        new PgSpanExporter(new PgConfig(PgTestSupport.unavailable("database down"), null, null));
    var application =
        span("2000000000000003", "database query", 1, Attributes.empty())
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.create("application"))
            .build();

    assertTrue(unavailable.export(List.of(application)).isSuccess());
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
    assertEquals(0, openTransactions());
    var warning = logged.getFirst();
    assertEquals(Level.WARNING, warning.getLevel());
    assertTrue(warning.getMessage().contains(RUN), warning.getMessage());
    assertTrue(warning.getMessage().contains("<redacted:key>"), warning.getMessage());
    assertFalse(warning.getMessage().contains(SECRET), warning.getMessage());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aFailedWriteIsRolledBackAndLoggedWhetherOrNotItsRollbackFails(boolean rollbackFails) {
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var rollbacks = new AtomicInteger();
    var failing =
        new PgSpanExporter(
            new PgConfig(PgTestSupport.failingWrites(rollbackFails, rollbacks), null, null));

    try (var ignored = LogCapture.of(PgSpanExporter.class, logged)) {
      assertFalse(failing.export(List.of(runSpan(RUN))).isSuccess());
    }

    assertEquals(1, rollbacks.get());
    var thrown = logged.stream().map(record -> record.getThrown().getMessage()).toList();
    assertEquals(
        rollbackFails
            ? List.of(
                "java.lang.IllegalStateException: dml failed",
                "java.lang.IllegalStateException: rollback failed")
            : List.of("java.lang.IllegalStateException: dml failed"),
        thrown);
  }

  @Test
  void aTransactionThatCannotOpenIsAFailureLoggedRedacted() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var unavailable =
        new PgSpanExporter(
            new PgConfig(
                PgTestSupport.unavailable("connection to " + SECRET + " unavailable"),
                null,
                secrets.redactor()));
    var logged = new CopyOnWriteArrayList<LogRecord>();

    try (var ignored = LogCapture.of(PgSpanExporter.class, logged)) {
      assertFalse(unavailable.export(List.of(runSpan(RUN))).isSuccess());
    }

    var warning = logged.getFirst();
    assertEquals(Level.WARNING, warning.getLevel());
    assertTrue(warning.getMessage().contains(RUN), warning.getMessage());
    assertEquals(
        "java.lang.IllegalStateException: connection to <redacted:key> unavailable",
        warning.getThrown().getMessage());
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
