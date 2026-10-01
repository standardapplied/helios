/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.model.FileReference;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Role;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.Trace;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlEventSinkTest {

  private static final Instant NOW = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  void writesOneLinePerEvent(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("events.jsonl");
    var runId = Ids.newId();
    try (var sink = JsonlEventSink.open(file)) {
      var trace = Trace.newBuilder().withDuration(Duration.ofMillis(7)).build();
      sink.onEvent(new HeliosEvent.RunStarted(NOW, runId, Optional.empty(), "agent", Map.of()));
      sink.onEvent(new HeliosEvent.RunCompleted(NOW, runId, Optional.empty(), trace));
    }

    var lines = Files.readAllLines(file);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("\"type\":\"RunStarted\""));
    assertTrue(lines.get(0).contains("\"harnessKind\":\"agent\""));
    assertTrue(lines.get(1).contains("\"type\":\"RunCompleted\""));
    assertTrue(lines.get(1).contains("\"durationNanos\":7000000"));
  }

  @Test
  void everyEventVariantSerializes(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("all.jsonl");
    var runId = Ids.newId();
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.RunStarted(NOW, runId, Optional.empty(), "agent", Map.of("k", "v")));
      sink.onEvent(
          new HeliosEvent.RunCompleted(NOW, runId, Optional.empty(), Trace.newBuilder().build()));
      sink.onEvent(
          new HeliosEvent.RunFailed(
              NOW, runId, Optional.empty(), "err", Trace.newBuilder().withError("err").build()));
      sink.onEvent(new HeliosEvent.IterationStarted(NOW, runId, Optional.empty(), 0, 30));
      sink.onEvent(new HeliosEvent.IterationCompleted(NOW, runId, Optional.empty(), 0));
      sink.onEvent(new HeliosEvent.AssistantTextDelta(NOW, runId, Optional.empty(), "txt"));
      sink.onEvent(new HeliosEvent.AssistantText(NOW, runId, Optional.empty(), "full"));
      sink.onEvent(new HeliosEvent.AssistantThinkingDelta(NOW, runId, Optional.empty(), "thk"));
      sink.onEvent(
          new HeliosEvent.AssistantThinkingComplete(
              NOW, runId, Optional.empty(), "full", Optional.of("sig")));
      sink.onEvent(
          new HeliosEvent.ToolCallStarted(
              NOW, runId, Optional.empty(), "c1", "search", Map.of("q", "x")));
      sink.onEvent(
          new HeliosEvent.ToolCallCompleted(
              NOW, runId, Optional.empty(), "c1", ToolResult.success("ok"), Duration.ZERO));
      sink.onEvent(new HeliosEvent.ToolCallFailed(NOW, runId, Optional.empty(), "c1", "e"));
      sink.onEvent(new HeliosEvent.MemoryWritten(NOW, runId, Optional.empty(), "b", "update"));
      sink.onEvent(new HeliosEvent.MemoryRead(NOW, runId, Optional.empty(), "b"));
      sink.onEvent(
          new HeliosEvent.SpanOpened(
              NOW, runId, Optional.empty(), Ids.newId(), Optional.empty(), "n"));
      sink.onEvent(
          new HeliosEvent.SpanClosed(
              NOW,
              runId,
              Optional.empty(),
              Ids.newId(),
              Duration.ZERO,
              false,
              Optional.of("boom")));
      sink.onEvent(new HeliosEvent.SubAgentStarted(NOW, runId, Optional.empty(), "w", Ids.newId()));
      sink.onEvent(
          new HeliosEvent.SubAgentCompleted(NOW, runId, Optional.empty(), "w", Duration.ZERO));
      sink.onEvent(
          new HeliosEvent.CompactionTriggered(NOW, runId, Optional.empty(), "prune", 100, 50));
      sink.onEvent(
          new HeliosEvent.OptimizerCandidateProposed(
              NOW, runId, Optional.empty(), Ids.newId(), Optional.empty(), "ref"));
      sink.onEvent(
          new HeliosEvent.OptimizerCandidateScored(
              NOW, runId, Optional.empty(), Ids.newId(), 1.5, new double[] {0.5, 1.0}));
      sink.onEvent(new HeliosEvent.Custom(NOW, runId, Optional.empty(), "x.y", Map.of("a", 1)));
    }

    var lines = Files.readAllLines(file);
    assertEquals(22, lines.size());
    for (var line : lines) {
      assertTrue(line.startsWith("{") && line.endsWith("}"), "malformed line: " + line);
      assertFalse(line.contains(",}"), "trailing comma in: " + line);
    }
  }

  @Test
  void metadataOnlyModeOmitsSensitiveValuesAcrossEveryEventShape(@TempDir Path tmp)
      throws Exception {
    var file = tmp.resolve("metadata.jsonl");
    var runId = Ids.newId();
    var canary = "sensitive-canary-never-persist";
    var fileUri = "https://private-files.example/" + canary;
    var message =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent(canary)
            .withMetadata(Map.of("private", canary))
            .withFileReferences(List.of(FileReference.of(fileUri, "video/mp4")))
            .build();
    var trace =
        Trace.newBuilder()
            .withModelId("gemini-3.7-flash")
            .withDuration(Duration.ofMillis(9))
            .withError(canary)
            .withInputText(canary)
            .withOutputText(canary)
            .withAttribute("gemini.apiVersion", "v1beta")
            .withAttribute("private", canary)
            .withUsage(Response.Usage.of(11, 7, 0, 3))
            .build();

    try (var sink = JsonlEventSink.openMetadataOnly(file)) {
      sink.onEvent(
          new HeliosEvent.RunStarted(
              NOW,
              runId,
              Optional.empty(),
              canary,
              Map.of("gemini.apiVersion", "v1beta", "private", canary)));
      sink.onEvent(
          new HeliosEvent.RunStarted(
              NOW, runId, Optional.empty(), "agent", Map.of("apiVersion", canary)));
      sink.onEvent(new HeliosEvent.RunCompleted(NOW, runId, Optional.empty(), trace));
      sink.onEvent(new HeliosEvent.RunFailed(NOW, runId, Optional.empty(), canary, trace));
      sink.onEvent(
          new HeliosEvent.BeforeApiCall(
              NOW, runId, Optional.empty(), canary, Ids.newId(), List.of(message), 1));
      sink.onEvent(
          new HeliosEvent.AfterTurn(
              NOW,
              runId,
              Optional.empty(),
              canary,
              Ids.newId(),
              Optional.of(message),
              Message.assistant(canary),
              List.of(Message.tool("call-1", "lookup", canary)),
              1));
      sink.onEvent(
          new HeliosEvent.BeforeCompaction(
              NOW, runId, Optional.empty(), canary, Ids.newId(), List.of(message)));
      sink.onEvent(
          new HeliosEvent.SessionEnd(
              NOW,
              runId,
              Optional.empty(),
              canary,
              Ids.newId(),
              List.of(message),
              HeliosEvent.SessionEnd.Termination.FAILED));
      sink.onEvent(new HeliosEvent.AssistantTextDelta(NOW, runId, Optional.empty(), canary));
      sink.onEvent(new HeliosEvent.AssistantText(NOW, runId, Optional.empty(), canary));
      sink.onEvent(new HeliosEvent.AssistantThinkingDelta(NOW, runId, Optional.empty(), canary));
      sink.onEvent(
          new HeliosEvent.AssistantThinkingComplete(
              NOW, runId, Optional.empty(), canary, Optional.of(canary)));
      sink.onEvent(
          new HeliosEvent.ToolCallStarted(
              NOW, runId, Optional.empty(), "call-1", "lookup", Map.of("query", canary)));
      sink.onEvent(
          new HeliosEvent.ToolCallCompleted(
              NOW,
              runId,
              Optional.empty(),
              "call-1",
              ToolResult.success(canary, Map.of("private", canary)),
              Duration.ofMillis(2)));
      sink.onEvent(new HeliosEvent.ToolCallFailed(NOW, runId, Optional.empty(), "call-1", canary));
      sink.onEvent(new HeliosEvent.MemoryWritten(NOW, runId, Optional.empty(), canary, canary));
      sink.onEvent(new HeliosEvent.MemoryRead(NOW, runId, Optional.empty(), canary));
      sink.onEvent(
          new HeliosEvent.SpanOpened(
              NOW, runId, Optional.empty(), Ids.newId(), Optional.empty(), canary));
      sink.onEvent(
          new HeliosEvent.SpanClosed(
              NOW,
              runId,
              Optional.empty(),
              Ids.newId(),
              Duration.ofMillis(3),
              false,
              Optional.of(canary)));
      sink.onEvent(
          new HeliosEvent.SubAgentStarted(NOW, runId, Optional.empty(), canary, Ids.newId()));
      sink.onEvent(
          new HeliosEvent.SubAgentCompleted(
              NOW, runId, Optional.empty(), canary, Duration.ofMillis(4)));
      sink.onEvent(
          new HeliosEvent.OptimizerCandidateProposed(
              NOW, runId, Optional.empty(), Ids.newId(), Optional.empty(), canary));
      sink.onEvent(
          new HeliosEvent.Custom(NOW, runId, Optional.empty(), canary, Map.of("private", canary)));
    }

    var output = Files.readString(file);
    assertFalse(output.contains(canary), output);
    assertFalse(output.contains(fileUri), output);
    assertFalse(output.contains("fullText"), output);
    assertFalse(output.contains("thinkingText"), output);
    assertFalse(output.contains("args"), output);
    assertFalse(output.contains("data"), output);
    assertTrue(output.contains("\"toolName\":\"lookup\""), output);
    assertTrue(output.contains("\"success\":true"), output);
    assertTrue(output.contains("\"errorCategory\":\"run_failed\""), output);
    assertTrue(output.contains("\"modelId\":\"gemini-3.7-flash\""), output);
    assertTrue(output.contains("\"apiVersion\":\"v1beta\""), output);
    assertTrue(output.contains("\"inputTokens\":11"), output);
    assertTrue(output.contains("\"outputTokens\":7"), output);
  }

  @Test
  void explicitFullModeRetainsReplayContent(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("full.jsonl");
    try (var sink = JsonlEventSink.openFull(file)) {
      sink.onEvent(
          new HeliosEvent.AssistantText(NOW, Ids.newId(), Optional.empty(), "full-mode-canary"));
    }

    assertTrue(Files.readString(file).contains("full-mode-canary"));
  }

  @Test
  void escapesSpecialCharactersInStrings(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("escape.jsonl");
    var runId = Ids.newId();
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.AssistantTextDelta(
              NOW, runId, Optional.empty(), "line1\nline2\t\"quoted\"\\back"));
    }

    var lines = Files.readAllLines(file);
    assertEquals(1, lines.size());
    var line = lines.get(0);
    assertTrue(line.contains("\\n"));
    assertTrue(line.contains("\\t"));
    assertTrue(line.contains("\\\""));
    assertTrue(line.contains("\\\\"));
  }

  @Test
  void escapesControlCharacters(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("ctrl.jsonl");
    var runId = Ids.newId();
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(new HeliosEvent.AssistantTextDelta(NOW, runId, Optional.empty(), "xy"));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\\u0001"));
  }

  @Test
  void onEventAfterCloseThrows(@TempDir Path tmp) {
    var file = tmp.resolve("closed.jsonl");
    var sink = JsonlEventSink.open(file);
    sink.close();
    assertTrue(sink.isClosed());
    assertThrows(
        IllegalStateException.class,
        () ->
            sink.onEvent(
                new HeliosEvent.IterationStarted(NOW, Ids.newId(), Optional.empty(), 0, 10)));
  }

  @Test
  void doubleCloseIsIdempotent(@TempDir Path tmp) {
    var file = tmp.resolve("idem.jsonl");
    var sink = JsonlEventSink.open(file);
    sink.close();
    sink.close();
    assertTrue(sink.isClosed());
  }

  @Test
  void openRejectsNullPath() {
    assertThrows(NullPointerException.class, () -> JsonlEventSink.open(null));
  }

  @Test
  void onEventRejectsNullEvent(@TempDir Path tmp) {
    try (var sink = JsonlEventSink.open(tmp.resolve("x.jsonl"))) {
      assertThrows(NullPointerException.class, () -> sink.onEvent(null));
    }
  }

  @Test
  void openFailsOnInvalidPath() {
    // Parent directory does not exist
    var path = Path.of("/this/path/should/not/exist/event.jsonl");
    assertThrows(UncheckedIOException.class, () -> JsonlEventSink.open(path));
  }

  @Test
  void concurrentWritesProduceWellFormedLines(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("concurrent.jsonl");
    var threads = 16;
    var perThread = 50;
    var latch = new CountDownLatch(threads);
    try (var sink = JsonlEventSink.open(file);
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var t = 0; t < threads; t++) {
        pool.submit(
            () -> {
              try {
                var runId = Ids.newId();
                for (var i = 0; i < perThread; i++) {
                  sink.onEvent(
                      new HeliosEvent.IterationStarted(NOW, runId, Optional.empty(), i, 100000));
                }
              } finally {
                latch.countDown();
              }
            });
      }
      latch.await();
    }

    var lines = Files.readAllLines(file);
    assertEquals(threads * perThread, lines.size());
    for (var line : lines) {
      assertTrue(line.startsWith("{"));
      assertTrue(line.endsWith("}"));
    }
  }

  @Test
  void customDataWithMixedValuesSerializes(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("custom.jsonl");
    var data = Map.<String, Object>of("count", 42, "rate", 0.99, "name", "alpha", "active", true);

    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.Custom(NOW, Ids.newId(), Optional.empty(), "kubera.event", data));
    }

    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"count\":42"));
    assertTrue(line.contains("\"rate\":0.99"));
    assertTrue(line.contains("\"name\":\"alpha\""));
    assertTrue(line.contains("\"active\":true"));
  }

  @Test
  void nonFiniteNumberValuesAreCoercedToString(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("nonfinite.jsonl");
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.Custom(
              NOW, Ids.newId(), Optional.empty(), "x.y", Map.of("v", Double.POSITIVE_INFINITY)));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"Infinity\""));
  }

  @Test
  void spanIdEmitsNullWhenAbsent(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("spanid.jsonl");
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(new HeliosEvent.IterationStarted(NOW, Ids.newId(), Optional.empty(), 0, 10));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"spanId\":null"));
  }

  @Test
  void customDataWithCharSequenceAndObjectValues(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("char.jsonl");
    var data =
        Map.<String, Object>of(
            "csq", new StringBuilder("buf"),
            "obj",
                new Object() {
                  @Override
                  public String toString() {
                    return "custom-obj";
                  }
                });
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(new HeliosEvent.Custom(NOW, Ids.newId(), Optional.empty(), "x.y", data));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"csq\":\"buf\""));
    assertTrue(line.contains("\"obj\":\"custom-obj\""));
  }

  @Test
  void customDataWithIntegerNumberSerializesAsFinite(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("int.jsonl");
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.Custom(NOW, Ids.newId(), Optional.empty(), "x.y", Map.of("n", 42L)));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"n\":42"));
  }

  @Test
  void customDataWithNaNNumberIsCoercedToString(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("nan.jsonl");
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.Custom(
              NOW, Ids.newId(), Optional.empty(), "x.y", Map.of("v", Double.NaN)));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"NaN\""));
  }

  @Test
  void toolCallStartedWithEmptyArgsProducesEmptyJsonObject(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("empty-args.jsonl");
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.ToolCallStarted(NOW, Ids.newId(), Optional.empty(), "c", "n", Map.of()));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"args\":{}"));
  }

  @Test
  void runStartedWithEmptyAttributesProducesEmptyJsonObject(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("empty-attrs.jsonl");
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.RunStarted(NOW, Ids.newId(), Optional.empty(), "agent", Map.of()));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"attributes\":{}"));
  }

  @Test
  void optimizerCandidateProposedWithoutParentEmitsNullParentJson(@TempDir Path tmp)
      throws Exception {
    var file = tmp.resolve("orphan.jsonl");
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.OptimizerCandidateProposed(
              NOW, Ids.newId(), Optional.empty(), Ids.newId(), Optional.empty(), "seed"));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"parentCandidateId\":null"));
  }

  @Test
  void spanClosedSuccessEmitsNullError(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("ok.jsonl");
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(
          new HeliosEvent.SpanClosed(
              NOW,
              Ids.newId(),
              Optional.empty(),
              Ids.newId(),
              Duration.ZERO,
              true,
              Optional.empty()));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"error\":null"));
    assertTrue(line.contains("\"success\":true"));
  }

  @Test
  void spanIdEmitsValueWhenPresent(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("spanid2.jsonl");
    var spanId = Ids.newId();
    try (var sink = JsonlEventSink.open(file)) {
      sink.onEvent(new HeliosEvent.IterationStarted(NOW, Ids.newId(), Optional.of(spanId), 0, 10));
    }
    var line = Files.readAllLines(file).get(0);
    assertTrue(line.contains("\"spanId\":\"" + spanId.toString() + "\""));
  }
}
