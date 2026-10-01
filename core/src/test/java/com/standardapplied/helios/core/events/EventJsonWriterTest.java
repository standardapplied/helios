/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for the package-private {@link EventJsonWriter}. Exercises encoding shapes and
 * defensive guards that are unreachable through the public {@link HeliosEvent} API (which validates
 * upstream).
 */
class EventJsonWriterTest {

  private static final Instant NOW = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  void encodeRunStartedHasStableShape() {
    var runId = Ids.newId();
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.RunStarted(NOW, runId, Optional.empty(), "agent", Map.of()));

    assertTrue(json.startsWith("{"));
    assertTrue(json.endsWith("}"));
    assertTrue(json.contains("\"type\":\"RunStarted\""));
    assertTrue(json.contains("\"runId\":\"" + runId + "\""));
    assertTrue(json.contains("\"harnessKind\":\"agent\""));
    assertTrue(json.contains("\"attributes\":{}"));
  }

  @Test
  void encodeAlwaysProducesSingleLine() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.AssistantTextDelta(NOW, Ids.newId(), Optional.empty(), "line1\nline2"));
    assertEquals(-1, json.indexOf('\n'));
  }

  @Test
  void encodeStringMapWithMultipleEntries() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.RunStarted(
                NOW, Ids.newId(), Optional.empty(), "agent", Map.of("a", "1", "b", "2")));
    assertTrue(json.contains("\"a\":\"1\"") || json.contains("\"b\":\"2\""));
  }

  @Test
  void encodeObjectMapWithMultipleEntries() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.ToolCallStarted(
                NOW, Ids.newId(), Optional.empty(), "c", "n", Map.of("a", 1, "b", true)));
    assertTrue(json.contains("\"a\":1") || json.contains("\"b\":true"));
  }

  @Test
  void encodeDoubleArrayMultiElement() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.OptimizerCandidateScored(
                NOW,
                Ids.newId(),
                Optional.empty(),
                Ids.newId(),
                1.5,
                new double[] {0.1, 0.2, 0.3}));
    assertTrue(json.contains("\"perInstanceScores\":[0.1,0.2,0.3]"));
  }

  @Test
  void encodeEmptyDoubleArrayProducesEmptyBrackets() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.OptimizerCandidateScored(
                NOW, Ids.newId(), Optional.empty(), Ids.newId(), 0.0, new double[] {}));
    assertTrue(json.contains("\"perInstanceScores\":[]"));
  }

  @Test
  void encodeWithSpanIdPopulated() {
    var spanId = Ids.newId();
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.RunCompleted(
                NOW, Ids.newId(), Optional.of(spanId), Trace.newBuilder().build()));
    assertTrue(json.contains("\"spanId\":\"" + spanId + "\""));
  }

  @Test
  void encodeRunFailedIncludesError() {
    var trace = Trace.newBuilder().withDuration(Duration.ofMillis(1)).build();
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.RunFailed(NOW, Ids.newId(), Optional.empty(), "boom", trace));
    assertTrue(json.contains("\"error\":\"boom\""));
    assertTrue(json.contains("\"durationNanos\":1000000"));
  }

  @Test
  void encodeRunCompletedIncludesTraceSummary() {
    var trace =
        Trace.newBuilder().withDuration(Duration.ofMillis(50)).withTotalTokens(1234).build();
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.RunCompleted(NOW, Ids.newId(), Optional.empty(), trace));
    assertTrue(json.contains("\"trace\":{"));
    assertTrue(json.contains("\"id\":\"" + trace.id() + "\""));
    assertTrue(json.contains("\"durationNanos\":50000000"));
    assertTrue(json.contains("\"spanCount\":0"));
    assertTrue(json.contains("\"totalTokens\":1234"));
  }

  @Test
  void encodeIterationCompletedIncludesIteration() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.IterationCompleted(NOW, Ids.newId(), Optional.empty(), 7));
    assertTrue(json.contains("\"iteration\":7"));
  }

  @Test
  void encodeMemoryReadIncludesBlockName() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.MemoryRead(NOW, Ids.newId(), Optional.empty(), "identity"));
    assertTrue(json.contains("\"blockName\":\"identity\""));
  }

  @Test
  void encodeAssistantTextIncludesFullText() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.AssistantText(NOW, Ids.newId(), Optional.empty(), "hello"));
    assertTrue(json.contains("\"fullText\":\"hello\""));
  }

  @Test
  void encodeAssistantThinkingCompleteWithoutSignatureEmitsNull() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.AssistantThinkingComplete(
                NOW, Ids.newId(), Optional.empty(), "full", Optional.empty()));
    assertTrue(json.contains("\"signature\":null"));
  }

  @Test
  void encodeSubAgentCompletedIncludesName() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.SubAgentCompleted(
                NOW, Ids.newId(), Optional.empty(), "worker-1", Duration.ZERO));
    assertTrue(json.contains("\"subAgentName\":\"worker-1\""));
  }

  @Test
  void encodeBeforeApiCallSummary() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.BeforeApiCall(
                NOW, Ids.newId(), Optional.empty(), "u-1", Ids.newId(), java.util.List.of(), 4));
    assertTrue(json.contains("\"type\":\"BeforeApiCall\""));
    assertTrue(json.contains("\"userId\":\"u-1\""));
    assertTrue(json.contains("\"iteration\":4"));
    assertTrue(json.contains("\"messageCount\":0"));
  }

  @Test
  void encodeAfterTurnSummary() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.AfterTurn(
                NOW,
                Ids.newId(),
                Optional.empty(),
                "u-1",
                Ids.newId(),
                Optional.empty(),
                ai.singlr.core.model.Message.assistant("ok"),
                java.util.List.of(),
                2));
    assertTrue(json.contains("\"type\":\"AfterTurn\""));
    assertTrue(json.contains("\"toolMessageCount\":0"));
    assertTrue(json.contains("\"iteration\":2"));
  }

  @Test
  void encodeBeforeCompactionSummary() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.BeforeCompaction(
                NOW, Ids.newId(), Optional.empty(), null, Ids.newId(), java.util.List.of()));
    assertTrue(json.contains("\"type\":\"BeforeCompaction\""));
    assertTrue(json.contains("\"userId\":null"));
  }

  @Test
  void encodeSessionEndSummary() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.SessionEnd(
                NOW,
                Ids.newId(),
                Optional.empty(),
                "u-1",
                Ids.newId(),
                java.util.List.of(),
                HeliosEvent.SessionEnd.Termination.MAX_ITERATIONS));
    assertTrue(json.contains("\"type\":\"SessionEnd\""));
    assertTrue(json.contains("\"termination\":\"MAX_ITERATIONS\""));
  }

  @Test
  void encodeEventsWithNullSessionId() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.BeforeCompaction(
                NOW, Ids.newId(), Optional.empty(), "u", null, java.util.List.of()));
    assertTrue(json.contains("\"sessionId\":\"\""));
  }

  @Test
  void encodeSpanOpenedWithoutParentEmitsNull() {
    var json =
        EventJsonWriter.encode(
            new HeliosEvent.SpanOpened(
                NOW, Ids.newId(), Optional.empty(), Ids.newId(), Optional.empty(), "span.x"));
    assertTrue(json.contains("\"parentSpanId\":null"));
  }
}
