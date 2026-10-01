/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HeliosEventTest {

  private static final Instant NOW = Instant.parse("2026-05-13T10:00:00Z");

  // ----- Run lifecycle -----

  @Test
  void runStartedHoldsAllFields() {
    var runId = Ids.newId();
    var event =
        new HeliosEvent.RunStarted(
            NOW, runId, Optional.empty(), "agent", Map.of("model", "opus-4.7"));

    assertInstanceOf(HeliosEvent.class, event);
    assertEquals(NOW, event.at());
    assertEquals(runId, event.runId());
    assertTrue(event.spanId().isEmpty());
    assertEquals("agent", event.harnessKind());
    assertEquals(Map.of("model", "opus-4.7"), event.attributes());
  }

  @Test
  void runStartedDefensiveCopiesAttributes() {
    var attrs = new HashMap<String, String>();
    attrs.put("k", "v1");
    var event = new HeliosEvent.RunStarted(NOW, Ids.newId(), Optional.empty(), "agent", attrs);
    attrs.put("k", "v2");
    assertEquals("v1", event.attributes().get("k"));
  }

  @Test
  void runStartedAcceptsNullAttributes() {
    var event = new HeliosEvent.RunStarted(NOW, Ids.newId(), Optional.empty(), "agent", null);
    assertEquals(Map.of(), event.attributes());
  }

  @Test
  void runStartedRejectsBlankHarnessKind() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.RunStarted(NOW, Ids.newId(), Optional.empty(), " ", Map.of()));
  }

  @Test
  void runStartedRejectsNullBaseFields() {
    var runId = Ids.newId();
    assertThrows(
        NullPointerException.class,
        () -> new HeliosEvent.RunStarted(null, runId, Optional.empty(), "agent", Map.of()));
    assertThrows(
        NullPointerException.class,
        () -> new HeliosEvent.RunStarted(NOW, null, Optional.empty(), "agent", Map.of()));
    assertThrows(
        NullPointerException.class,
        () -> new HeliosEvent.RunStarted(NOW, runId, null, "agent", Map.of()));
  }

  @Test
  void runCompletedHoldsTrace() {
    var trace = Trace.newBuilder().withName("agent").withDuration(Duration.ofMillis(123)).build();
    var event = new HeliosEvent.RunCompleted(NOW, Ids.newId(), Optional.empty(), trace);
    assertEquals(trace, event.trace());
    assertEquals(Duration.ofMillis(123), event.trace().duration());
  }

  @Test
  void runCompletedRejectsNullTrace() {
    assertThrows(
        NullPointerException.class,
        () -> new HeliosEvent.RunCompleted(NOW, Ids.newId(), Optional.empty(), null));
  }

  @Test
  void runFailedHoldsErrorAndTrace() {
    var trace = Trace.newBuilder().withName("agent").withError("boom").build();
    var event = new HeliosEvent.RunFailed(NOW, Ids.newId(), Optional.empty(), "boom", trace);
    assertEquals("boom", event.error());
    assertEquals(trace, event.trace());
  }

  @Test
  void runFailedRejectsBlankError() {
    var trace = Trace.newBuilder().withName("agent").build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.RunFailed(NOW, Ids.newId(), Optional.empty(), "", trace));
  }

  @Test
  void runFailedRejectsNullTrace() {
    assertThrows(
        NullPointerException.class,
        () -> new HeliosEvent.RunFailed(NOW, Ids.newId(), Optional.empty(), "err", null));
  }

  // ----- Agent-loop hooks (BeforeApiCall / AfterTurn / BeforeCompaction / SessionEnd) -----

  @Test
  void beforeApiCallHoldsFields() {
    var sid = Ids.newId();
    var event =
        new HeliosEvent.BeforeApiCall(
            NOW, Ids.newId(), Optional.empty(), "user-1", sid, List.of(), 3);
    assertEquals("user-1", event.userId());
    assertEquals(sid, event.sessionId());
    assertEquals(3, event.iteration());
  }

  @Test
  void beforeApiCallAcceptsNullUserIdForAnonymous() {
    var event =
        new HeliosEvent.BeforeApiCall(
            NOW, Ids.newId(), Optional.empty(), null, Ids.newId(), List.of(), 0);
    assertNull(event.userId());
  }

  @Test
  void beforeApiCallRejectsNegativeIteration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.BeforeApiCall(
                NOW, Ids.newId(), Optional.empty(), "u", Ids.newId(), List.of(), -1));
  }

  @Test
  void afterTurnHoldsFields() {
    var assistant = ai.singlr.core.model.Message.assistant("hi");
    var event =
        new HeliosEvent.AfterTurn(
            NOW,
            Ids.newId(),
            Optional.empty(),
            "u",
            Ids.newId(),
            Optional.empty(),
            assistant,
            List.of(),
            5);
    assertEquals(assistant, event.assistantMessage());
    assertEquals(5, event.iteration());
    assertTrue(event.userMessage().isEmpty());
  }

  @Test
  void afterTurnRejectsNullAssistantMessage() {
    assertThrows(
        NullPointerException.class,
        () ->
            new HeliosEvent.AfterTurn(
                NOW,
                Ids.newId(),
                Optional.empty(),
                "u",
                Ids.newId(),
                Optional.empty(),
                null,
                List.of(),
                0));
  }

  @Test
  void beforeCompactionHoldsMessages() {
    var event =
        new HeliosEvent.BeforeCompaction(
            NOW, Ids.newId(), Optional.empty(), "u", Ids.newId(), List.of());
    assertNotNull(event.messages());
    assertEquals(0, event.messages().size());
  }

  @Test
  void sessionEndCarriesTermination() {
    var event =
        new HeliosEvent.SessionEnd(
            NOW,
            Ids.newId(),
            Optional.empty(),
            "u",
            Ids.newId(),
            List.of(),
            HeliosEvent.SessionEnd.Termination.MAX_ITERATIONS);
    assertEquals(HeliosEvent.SessionEnd.Termination.MAX_ITERATIONS, event.termination());
  }

  @Test
  void sessionEndRejectsNullTermination() {
    assertThrows(
        NullPointerException.class,
        () ->
            new HeliosEvent.SessionEnd(
                NOW, Ids.newId(), Optional.empty(), "u", Ids.newId(), List.of(), null));
  }

  // ----- Iteration -----

  @Test
  void iterationStartedAndCompletedRoundTrip() {
    var started = new HeliosEvent.IterationStarted(NOW, Ids.newId(), Optional.empty(), 0, 30);
    var completed = new HeliosEvent.IterationCompleted(NOW, Ids.newId(), Optional.empty(), 0);
    assertEquals(0, started.iteration());
    assertEquals(30, started.maxIterations());
    assertEquals(0, completed.iteration());
  }

  @Test
  void iterationStartedRejectsNegativeIteration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.IterationStarted(NOW, Ids.newId(), Optional.empty(), -1, 30));
  }

  @Test
  void iterationStartedRejectsNonPositiveMaxIterations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.IterationStarted(NOW, Ids.newId(), Optional.empty(), 0, 0));
  }

  @Test
  void iterationCompletedRejectsNegativeIteration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.IterationCompleted(NOW, Ids.newId(), Optional.empty(), -1));
  }

  // ----- Assistant content -----

  @Test
  void assistantTextDeltaAllowsEmptyText() {
    var event = new HeliosEvent.AssistantTextDelta(NOW, Ids.newId(), Optional.empty(), "");
    assertEquals("", event.text());
  }

  @Test
  void assistantTextDeltaRejectsNullText() {
    assertThrows(
        NullPointerException.class,
        () -> new HeliosEvent.AssistantTextDelta(NOW, Ids.newId(), Optional.empty(), null));
  }

  @Test
  void assistantTextHoldsFullText() {
    var event = new HeliosEvent.AssistantText(NOW, Ids.newId(), Optional.empty(), "Hello world");
    assertEquals("Hello world", event.fullText());
  }

  @Test
  void assistantThinkingDeltaAllowsEmpty() {
    var event = new HeliosEvent.AssistantThinkingDelta(NOW, Ids.newId(), Optional.empty(), "");
    assertEquals("", event.thinkingText());
  }

  @Test
  void assistantThinkingCompleteHoldsFullAndOptionalSignature() {
    var withSig =
        new HeliosEvent.AssistantThinkingComplete(
            NOW, Ids.newId(), Optional.empty(), "full", Optional.of("sig"));
    var withoutSig =
        new HeliosEvent.AssistantThinkingComplete(
            NOW, Ids.newId(), Optional.empty(), "full", Optional.empty());
    assertEquals("full", withSig.fullThinking());
    assertEquals(Optional.of("sig"), withSig.signature());
    assertEquals(Optional.empty(), withoutSig.signature());
  }

  @Test
  void assistantThinkingCompleteRejectsNullSignatureOptional() {
    assertThrows(
        NullPointerException.class,
        () ->
            new HeliosEvent.AssistantThinkingComplete(
                NOW, Ids.newId(), Optional.empty(), "f", null));
  }

  // ----- Tool calls -----

  @Test
  void toolCallStartedDefensiveCopiesArgs() {
    var args = new HashMap<String, Object>();
    args.put("k", "v1");
    var event =
        new HeliosEvent.ToolCallStarted(
            NOW, Ids.newId(), Optional.empty(), "call_1", "search", args);
    args.put("k", "v2");
    assertEquals("v1", event.args().get("k"));
  }

  @Test
  void toolCallStartedAcceptsNullArgs() {
    var event =
        new HeliosEvent.ToolCallStarted(
            NOW, Ids.newId(), Optional.empty(), "call_1", "search", null);
    assertEquals(Map.of(), event.args());
  }

  @Test
  void toolCallStartedRejectsBlankCallId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.ToolCallStarted(
                NOW, Ids.newId(), Optional.empty(), "", "search", Map.of()));
  }

  @Test
  void toolCallStartedRejectsBlankToolName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.ToolCallStarted(
                NOW, Ids.newId(), Optional.empty(), "call_1", " ", Map.of()));
  }

  @Test
  void toolCallCompletedHoldsResultAndDuration() {
    var result = ToolResult.success("ok");
    var event =
        new HeliosEvent.ToolCallCompleted(
            NOW, Ids.newId(), Optional.empty(), "call_1", result, Duration.ofMillis(50));
    assertEquals(result, event.result());
    assertEquals(Duration.ofMillis(50), event.took());
  }

  @Test
  void toolCallCompletedRejectsBlankCallId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.ToolCallCompleted(
                NOW, Ids.newId(), Optional.empty(), " ", ToolResult.success("ok"), Duration.ZERO));
  }

  @Test
  void toolCallCompletedRejectsNullTook() {
    assertThrows(
        NullPointerException.class,
        () ->
            new HeliosEvent.ToolCallCompleted(
                NOW, Ids.newId(), Optional.empty(), "c", ToolResult.success("ok"), null));
  }

  @Test
  void toolCallFailedRejectsBlankCallId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.ToolCallFailed(NOW, Ids.newId(), Optional.empty(), " ", "err"));
  }

  @Test
  void subAgentCompletedRejectsBlankName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.SubAgentCompleted(
                NOW, Ids.newId(), Optional.empty(), " ", Duration.ZERO));
  }

  @Test
  void subAgentCompletedRejectsNullDuration() {
    assertThrows(
        NullPointerException.class,
        () -> new HeliosEvent.SubAgentCompleted(NOW, Ids.newId(), Optional.empty(), "w", null));
  }

  @Test
  void subAgentStartedRejectsNullParentSpanId() {
    assertThrows(
        NullPointerException.class,
        () -> new HeliosEvent.SubAgentStarted(NOW, Ids.newId(), Optional.empty(), "w", null));
  }

  @Test
  void spanOpenedRejectsNullParentOptional() {
    assertThrows(
        NullPointerException.class,
        () ->
            new HeliosEvent.SpanOpened(
                NOW, Ids.newId(), Optional.empty(), Ids.newId(), null, "name"));
  }

  @Test
  void spanClosedRejectsNullErrorOptional() {
    assertThrows(
        NullPointerException.class,
        () ->
            new HeliosEvent.SpanClosed(
                NOW, Ids.newId(), Optional.empty(), Ids.newId(), Duration.ZERO, true, null));
  }

  @Test
  void optimizerCandidateProposedRejectsNullParentOptional() {
    assertThrows(
        NullPointerException.class,
        () ->
            new HeliosEvent.OptimizerCandidateProposed(
                NOW, Ids.newId(), Optional.empty(), Ids.newId(), null, "ref"));
  }

  @Test
  void toolCallCompletedRejectsNullResult() {
    assertThrows(
        NullPointerException.class,
        () ->
            new HeliosEvent.ToolCallCompleted(
                NOW, Ids.newId(), Optional.empty(), "call_1", null, Duration.ZERO));
  }

  @Test
  void toolCallFailedHoldsError() {
    var event =
        new HeliosEvent.ToolCallFailed(
            NOW, Ids.newId(), Optional.empty(), "call_1", "subprocess died");
    assertEquals("subprocess died", event.error());
  }

  @Test
  void toolCallFailedRejectsBlankError() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.ToolCallFailed(NOW, Ids.newId(), Optional.empty(), "call_1", ""));
  }

  // ----- Memory -----

  @Test
  void memoryWrittenHoldsBlockAndOperation() {
    var event =
        new HeliosEvent.MemoryWritten(NOW, Ids.newId(), Optional.empty(), "identity", "update");
    assertEquals("identity", event.blockName());
    assertEquals("update", event.operation());
  }

  @Test
  void memoryReadHoldsBlockName() {
    var event = new HeliosEvent.MemoryRead(NOW, Ids.newId(), Optional.empty(), "working_memory");
    assertEquals("working_memory", event.blockName());
  }

  @Test
  void memoryWrittenRejectsBlankFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.MemoryWritten(NOW, Ids.newId(), Optional.empty(), "", "update"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.MemoryWritten(NOW, Ids.newId(), Optional.empty(), "identity", " "));
  }

  @Test
  void memoryReadRejectsBlankBlockName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.MemoryRead(NOW, Ids.newId(), Optional.empty(), ""));
  }

  // ----- Spans -----

  @Test
  void spanOpenedHoldsIdsAndName() {
    var openedId = Ids.newId();
    var parentId = Ids.newId();
    var event =
        new HeliosEvent.SpanOpened(
            NOW, Ids.newId(), Optional.empty(), openedId, Optional.of(parentId), "model.chat");
    assertEquals(openedId, event.openedSpanId());
    assertEquals(Optional.of(parentId), event.parentSpanId());
    assertEquals("model.chat", event.name());
  }

  @Test
  void spanOpenedRejectsBlankName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.SpanOpened(
                NOW, Ids.newId(), Optional.empty(), Ids.newId(), Optional.empty(), ""));
  }

  @Test
  void spanClosedHoldsDurationAndSuccess() {
    var closedId = Ids.newId();
    var event =
        new HeliosEvent.SpanClosed(
            NOW,
            Ids.newId(),
            Optional.empty(),
            closedId,
            Duration.ofMillis(200),
            true,
            Optional.empty());
    assertEquals(closedId, event.closedSpanId());
    assertTrue(event.success());
    assertTrue(event.error().isEmpty());
  }

  @Test
  void spanClosedWithError() {
    var event =
        new HeliosEvent.SpanClosed(
            NOW,
            Ids.newId(),
            Optional.empty(),
            Ids.newId(),
            Duration.ZERO,
            false,
            Optional.of("boom"));
    assertEquals(Optional.of("boom"), event.error());
  }

  // ----- Sub-agent -----

  @Test
  void subAgentStartedHoldsNameAndParent() {
    var parent = Ids.newId();
    var event =
        new HeliosEvent.SubAgentStarted(NOW, Ids.newId(), Optional.empty(), "researcher", parent);
    assertEquals("researcher", event.subAgentName());
    assertEquals(parent, event.parentSpanId());
  }

  @Test
  void subAgentCompletedHoldsNameAndDuration() {
    var event =
        new HeliosEvent.SubAgentCompleted(
            NOW, Ids.newId(), Optional.empty(), "researcher", Duration.ofSeconds(2));
    assertEquals("researcher", event.subAgentName());
    assertEquals(Duration.ofSeconds(2), event.duration());
  }

  @Test
  void subAgentStartedRejectsBlankName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.SubAgentStarted(NOW, Ids.newId(), Optional.empty(), "", Ids.newId()));
  }

  // ----- Compaction -----

  @Test
  void compactionTriggeredHoldsCounts() {
    var event =
        new HeliosEvent.CompactionTriggered(
            NOW, Ids.newId(), Optional.empty(), "summarize", 10000, 2500);
    assertEquals("summarize", event.phase());
    assertEquals(10000, event.beforeTokens());
    assertEquals(2500, event.afterTokens());
  }

  @Test
  void compactionTriggeredRejectsNegativeTokens() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.CompactionTriggered(
                NOW, Ids.newId(), Optional.empty(), "summarize", -1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.CompactionTriggered(
                NOW, Ids.newId(), Optional.empty(), "summarize", 0, -1));
  }

  @Test
  void compactionTriggeredRejectsBlankPhase() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.CompactionTriggered(NOW, Ids.newId(), Optional.empty(), "", 0, 0));
  }

  // ----- Optimizer -----

  @Test
  void optimizerCandidateProposedHoldsLineage() {
    var candId = Ids.newId();
    var parentId = Ids.newId();
    var event =
        new HeliosEvent.OptimizerCandidateProposed(
            NOW, Ids.newId(), Optional.empty(), candId, Optional.of(parentId), "reflective");
    assertEquals(candId, event.candidateId());
    assertEquals(Optional.of(parentId), event.parentCandidateId());
    assertEquals("reflective", event.source());
  }

  @Test
  void optimizerCandidateScoredHoldsScores() {
    var scores = new double[] {0.5, 0.7, 0.8};
    var event =
        new HeliosEvent.OptimizerCandidateScored(
            NOW, Ids.newId(), Optional.empty(), Ids.newId(), 2.0, scores);
    assertEquals(2.0, event.aggregateScore());
    assertEquals(3, event.perInstanceScores().length);
  }

  @Test
  void optimizerCandidateScoredDefensiveClonesScores() {
    var scores = new double[] {0.5};
    var event =
        new HeliosEvent.OptimizerCandidateScored(
            NOW, Ids.newId(), Optional.empty(), Ids.newId(), 0.5, scores);
    scores[0] = 0.0;
    assertEquals(0.5, event.perInstanceScores()[0]);
    assertNotSame(scores, event.perInstanceScores());
  }

  @Test
  void optimizerCandidateScoredRejectsNaNAggregate() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.OptimizerCandidateScored(
                NOW, Ids.newId(), Optional.empty(), Ids.newId(), Double.NaN, new double[] {0.0}));
  }

  @Test
  void optimizerCandidateScoredRejectsNaNPerInstance() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.OptimizerCandidateScored(
                NOW,
                Ids.newId(),
                Optional.empty(),
                Ids.newId(),
                0.0,
                new double[] {0.5, Double.NaN}));
  }

  @Test
  void optimizerCandidateProposedRejectsBlankSource() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HeliosEvent.OptimizerCandidateProposed(
                NOW, Ids.newId(), Optional.empty(), Ids.newId(), Optional.empty(), ""));
  }

  // ----- Custom -----

  @Test
  void customDefensiveCopiesData() {
    var data = new HashMap<String, Object>();
    data.put("k", "v1");
    var event =
        new HeliosEvent.Custom(NOW, Ids.newId(), Optional.empty(), "kubera.quote-fetched", data);
    data.put("k", "v2");
    assertEquals("v1", event.data().get("k"));
  }

  @Test
  void customAcceptsNullData() {
    var event =
        new HeliosEvent.Custom(NOW, Ids.newId(), Optional.empty(), "kubera.quote-fetched", null);
    assertEquals(Map.of(), event.data());
  }

  @Test
  void customRejectsBlankKind() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HeliosEvent.Custom(NOW, Ids.newId(), Optional.empty(), " ", Map.of()));
  }

  // ----- Pattern matching exhaustiveness -----

  @Test
  void sealedSwitchClassifiesEveryVariant() {
    var runId = Ids.newId();
    HeliosEvent[] events = {
      new HeliosEvent.RunStarted(NOW, runId, Optional.empty(), "agent", Map.of()),
      new HeliosEvent.RunCompleted(NOW, runId, Optional.empty(), Trace.newBuilder().build()),
      new HeliosEvent.RunFailed(NOW, runId, Optional.empty(), "err", Trace.newBuilder().build()),
      new HeliosEvent.IterationStarted(NOW, runId, Optional.empty(), 0, 30),
      new HeliosEvent.IterationCompleted(NOW, runId, Optional.empty(), 0),
      new HeliosEvent.BeforeApiCall(NOW, runId, Optional.empty(), "u1", Ids.newId(), List.of(), 0),
      new HeliosEvent.AfterTurn(
          NOW,
          runId,
          Optional.empty(),
          "u1",
          Ids.newId(),
          Optional.empty(),
          ai.singlr.core.model.Message.assistant("hi"),
          List.of(),
          0),
      new HeliosEvent.BeforeCompaction(NOW, runId, Optional.empty(), "u1", Ids.newId(), List.of()),
      new HeliosEvent.SessionEnd(
          NOW,
          runId,
          Optional.empty(),
          "u1",
          Ids.newId(),
          List.of(),
          HeliosEvent.SessionEnd.Termination.COMPLETED),
      new HeliosEvent.AssistantTextDelta(NOW, runId, Optional.empty(), "t"),
      new HeliosEvent.AssistantText(NOW, runId, Optional.empty(), "t"),
      new HeliosEvent.AssistantThinkingDelta(NOW, runId, Optional.empty(), "th"),
      new HeliosEvent.AssistantThinkingComplete(
          NOW, runId, Optional.empty(), "f", Optional.empty()),
      new HeliosEvent.ToolCallStarted(NOW, runId, Optional.empty(), "c", "n", Map.of()),
      new HeliosEvent.ToolCallCompleted(
          NOW, runId, Optional.empty(), "c", ToolResult.success("ok"), Duration.ZERO),
      new HeliosEvent.ToolCallFailed(NOW, runId, Optional.empty(), "c", "e"),
      new HeliosEvent.MemoryWritten(NOW, runId, Optional.empty(), "b", "op"),
      new HeliosEvent.MemoryRead(NOW, runId, Optional.empty(), "b"),
      new HeliosEvent.SpanOpened(NOW, runId, Optional.empty(), Ids.newId(), Optional.empty(), "s"),
      new HeliosEvent.SpanClosed(
          NOW, runId, Optional.empty(), Ids.newId(), Duration.ZERO, true, Optional.empty()),
      new HeliosEvent.SubAgentStarted(NOW, runId, Optional.empty(), "w", Ids.newId()),
      new HeliosEvent.SubAgentCompleted(NOW, runId, Optional.empty(), "w", Duration.ZERO),
      new HeliosEvent.CompactionTriggered(NOW, runId, Optional.empty(), "prune", 100, 50),
      new HeliosEvent.OptimizerCandidateProposed(
          NOW, runId, Optional.empty(), Ids.newId(), Optional.empty(), "ref"),
      new HeliosEvent.OptimizerCandidateScored(
          NOW, runId, Optional.empty(), Ids.newId(), 1.0, new double[] {0.5}),
      new HeliosEvent.Custom(NOW, runId, Optional.empty(), "x.y", Map.of())
    };
    for (var event : events) {
      var label = classify(event);
      assertNotEquals("", label);
    }
  }

  private static String classify(HeliosEvent event) {
    return switch (event) {
      case HeliosEvent.RunStarted e -> "RunStarted";
      case HeliosEvent.RunCompleted e -> "RunCompleted";
      case HeliosEvent.RunFailed e -> "RunFailed";
      case HeliosEvent.IterationStarted e -> "IterationStarted";
      case HeliosEvent.IterationCompleted e -> "IterationCompleted";
      case HeliosEvent.BeforeApiCall e -> "BeforeApiCall";
      case HeliosEvent.AfterTurn e -> "AfterTurn";
      case HeliosEvent.BeforeCompaction e -> "BeforeCompaction";
      case HeliosEvent.SessionEnd e -> "SessionEnd";
      case HeliosEvent.AssistantTextDelta e -> "AssistantTextDelta";
      case HeliosEvent.AssistantText e -> "AssistantText";
      case HeliosEvent.AssistantThinkingDelta e -> "AssistantThinkingDelta";
      case HeliosEvent.AssistantThinkingComplete e -> "AssistantThinkingComplete";
      case HeliosEvent.ToolCallStarted e -> "ToolCallStarted";
      case HeliosEvent.ToolCallCompleted e -> "ToolCallCompleted";
      case HeliosEvent.ToolCallFailed e -> "ToolCallFailed";
      case HeliosEvent.MemoryWritten e -> "MemoryWritten";
      case HeliosEvent.MemoryRead e -> "MemoryRead";
      case HeliosEvent.SpanOpened e -> "SpanOpened";
      case HeliosEvent.SpanClosed e -> "SpanClosed";
      case HeliosEvent.SubAgentStarted e -> "SubAgentStarted";
      case HeliosEvent.SubAgentCompleted e -> "SubAgentCompleted";
      case HeliosEvent.CompactionTriggered e -> "CompactionTriggered";
      case HeliosEvent.OptimizerCandidateProposed e -> "OptimizerCandidateProposed";
      case HeliosEvent.OptimizerCandidateScored e -> "OptimizerCandidateScored";
      case HeliosEvent.Custom e -> "Custom";
    };
  }
}
