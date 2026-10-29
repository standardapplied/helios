/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the complete observable behaviour of one scripted session that passes through every stage of
 * the loop: a steering message with an attachment, a pre-model-turn inject, a retried transient
 * stream failure, a tool round with mutate-args, block and post-tool mutate-result hooks, a
 * compaction, a schema self-correction and a pre-stop inject. The same run then ends early by a
 * wall-clock expiry, a cancellation and a hook that throws an {@link Error}.
 */
class SessionRunCharacterizationTest {

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  record Answer(String answer) {}

  @Test
  void scriptedRunEmitsTheCompleteEventSequenceAndBuildsTheFinalHistory() {
    var run = new Run();

    try (var session = run.start()) {
      var terminal = Await.value("the scripted run's terminal", run.result);
      var events = run.awaitEvents();
      run.assertEveryCallCarriedTheSchema();

      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals("{\"answer\":\"final\"}", success.result());
      assertEquals(Usage.of(63, 22), success.usage());
      assertEquals(CostEstimate.ofMicroUsd(401), success.cost());
      assertEquals(
          EXPECTED_EVENTS, events.stream().map(SessionRunCharacterizationTest::describe).toList());
      assertEquals(
          EXPECTED_FINAL_MODEL_INPUT,
          run.model.calls().getLast().stream()
              .map(SessionRunCharacterizationTest::describe)
              .toList());
      assertEquals(5, run.model.calls().size());
      assertEquals(5, run.compacted.get().size());
      assertShutDown(session);
    }
  }

  @Test
  void wallClockExpiryEndsTheRunAsErrorMaxWallClockAndShutsTheExecutorsDown() {
    var run = new Run();
    run.limits = run.limits().withMaxWallClock(Duration.ofMillis(1));
    run.preModelTurn =
        (history, ctx) -> {
          var expired = new CountDownLatch(1);
          ctx.cancellation().onCancel(expired::countDown);
          Await.latch("the wall-clock deadline", expired);
          run.now.set(START.plusSeconds(1));
          return HookOutcome.inject("pre-model inject");
        };

    try (var session = run.start()) {
      var terminal = Await.value("the wall-clock terminal", run.result);

      assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, terminal);
      run.assertEveryCallCarriedTheSchema();
      assertShutDown(session);
    }
  }

  @Test
  void cancellationDuringAStalledTurnEndsTheRunAsCancelledAndShutsTheExecutorsDown() {
    var run = new Run();
    var stalled = new CountDownLatch(1);
    run.replies.removeLast();
    run.replies.removeLast();
    run.replies.removeLast();
    run.replies.add(ModelStreams.stalled(stalled::countDown));

    var session = run.start();
    Await.latch("the stalled schema turn", stalled);
    session.close();
    var terminal = Await.value("the cancelled terminal", run.result);

    var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, terminal);
    assertEquals("session closed", cancelled.reason());
    run.assertEveryCallCarriedTheSchema();
    assertShutDown(session);
  }

  @Test
  void errorThrownByAHookFailsTheResultAndShutsTheExecutorsDown() {
    var run = new Run();
    run.postToolUse =
        (call, result, ctx) -> {
          throw new AssertionError("hook bug");
        };

    try (var session = run.start()) {
      var failure = Await.failure("the escaped error", run.result);

      assertInstanceOf(AssertionError.class, failure);
      assertEquals("hook bug", failure.getMessage());
      run.assertEveryCallCarriedTheSchema();
      assertShutDown(session);
    }
  }

  private static void assertShutDown(AgentSession session) {
    var impl = (AgentSessionImpl) session;
    session.close();
    assertEquals(0, impl.lifecycle.events().openStreams(), "event streams ended");
  }

  private static final List<String> EXPECTED_EVENTS =
      List.of(
          "RunStarted turn=0 run",
          "UserMessageReceived turn=0 start files=1",
          "HookFired turn=1 SessionRunCharacterizationTest$Run PreModelTurnHook Inject",
          "TurnEnded turn=1 TOOL_CALLS",
          "UserMessageReceived turn=1 pre-model inject files=0",
          "TurnRetried turn=2 attempt=1 delay=PT0S test",
          "ModelCallEnded turn=2 attempt=1 ERROR tokens=0 error=true",
          "ModelCallEnded turn=2 attempt=2 TOOL_CALLS tokens=15 error=false",
          "HookFired turn=2 SessionRunCharacterizationTest$Run PreToolUseHook MutateArgs",
          "ToolMutated turn=2 c1 SessionRunCharacterizationTest$Run {v=one}->{v=mutated}",
          "ToolUse turn=2 c1 {v=mutated}",
          "ToolResult turn=2 c1 Succeeded echoed: mutated",
          "HookFired turn=2 SessionRunCharacterizationTest$Run PostToolUseHook MutateResult",
          "ToolResult turn=2 c1 Succeeded post-mutated: echoed: mutated",
          "HookFired turn=2 SessionRunCharacterizationTest$Run PreToolUseHook Block",
          "ToolBlocked turn=2 c2 SessionRunCharacterizationTest$Run not allowed",
          "ToolResult turn=2 c2 Rejected blocked by SessionRunCharacterizationTest$Run: not allowed",
          "TurnEnded turn=2 TOOL_CALLS",
          "ContextWarning turn=2 0.96",
          "ModelCallEnded turn=2 attempt=1 STOP tokens=7 error=false",
          "ContextEdited turn=2 removed=3 96->10",
          "AssistantText turn=3 not json",
          "ModelCallEnded turn=3 attempt=1 ERROR tokens=0 error=true",
          "TurnEnded turn=3 TOOL_CALLS",
          "UserMessageReceived turn=3 Your structured output did not match the schema. Fix the listed fields and re-emit the structured output:\\n  - answer is required but missing\\n files=0",
          "AssistantText turn=4 {\"answer\":\"draft\"}",
          "ModelCallEnded turn=4 attempt=1 STOP tokens=26 error=false",
          "TurnEnded turn=4 STOP",
          "HookFired turn=4 SessionRunCharacterizationTest$Run PreStopHook Inject",
          "UserMessageReceived turn=4 pre-stop inject files=0",
          "AssistantText turn=5 {\"answer\":\"final\"}",
          "ModelCallEnded turn=5 attempt=1 STOP tokens=37 error=false",
          "TurnEnded turn=5 STOP",
          "RunEnded turn=5 Success");

  private static final List<String> EXPECTED_FINAL_MODEL_INPUT =
      List.of(
          "USER content=start toolCalls=[] toolCallId=null files=1",
          "USER content=compacted toolCalls=[] toolCallId=null files=0",
          "ASSISTANT content=not json toolCalls=[] toolCallId=null files=0",
          "USER content=Your structured output did not match the schema. Fix the listed fields and re-emit the structured output:\\n  - answer is required but missing\\n toolCalls=[] toolCallId=null files=0",
          "ASSISTANT content={\"answer\":\"draft\"} toolCalls=[] toolCallId=null files=0",
          "USER content=pre-stop inject toolCalls=[] toolCallId=null files=0");

  /** The scripted session, adjustable per test before {@link #start()}. */
  private static final class Run {

    final AtomicReference<Instant> now = new AtomicReference<>(START);
    CompletableFuture<ResultMessage> result;
    final List<CloseableIterator<ModelChunk>> replies =
        new ArrayList<>(
            List.of(
                transientFailure(),
                ModelStreams.of(
                    new ModelChunk.ToolUseStop(new ToolCall("c1", "echo", Map.of("v", "one"))),
                    new ModelChunk.ToolUseStop(new ToolCall("c2", "echo", Map.of("v", "two"))),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(10, 5), Map.of(), List.of())),
                ModelStreams.failing(
                    new StructuredOutputParseException(
                        List.of("answer is required but missing"), "not json"),
                    new ModelChunk.TextDelta("not json")),
                ModelStreams.of(
                    new ModelChunk.TextDelta("{\"answer\":\"draft\"}"),
                    new ModelChunk.MessageStop("STOP", Usage.of(20, 6), Map.of(), List.of())),
                ModelStreams.of(
                    new ModelChunk.TextDelta("{\"answer\":\"final\"}"),
                    new ModelChunk.MessageStop("STOP", Usage.of(30, 7), Map.of(), List.of()))));
    final EventCollector events = new EventCollector();
    ScriptedModel model;
    SessionLimits.Builder limits;
    PreModelTurnHook preModelTurn =
        (history, ctx) ->
            ctx.turnIndex() == 1 ? HookOutcome.inject("pre-model inject") : HookOutcome.cont();
    PostToolUseHook postToolUse =
        (call, result, ctx) ->
            call.id().equals("c1")
                ? HookOutcome.mutateResult("post-mutated: " + result.output())
                : HookOutcome.cont();
    final AtomicReference<List<Message>> compacted = new AtomicReference<>();

    SessionLimits.Builder limits() {
      if (limits == null) {
        limits =
            SessionLimits.newBuilder()
                .withMaxContextTokens(100)
                .withStreamRetryPolicy(new StreamRetryPolicy(2, Backoff.fixed(Duration.ZERO), 0.0));
      }
      return limits;
    }

    AgentSession start() {
      var preStopFired = new AtomicBoolean();
      PreToolUseHook preToolUse =
          (call, ctx) ->
              call.id().equals("c1")
                  ? HookOutcome.mutateArgs(Map.of("v", "mutated"))
                  : HookOutcome.block("not allowed");
      PreStopHook preStop =
          (response, ctx) ->
              preStopFired.compareAndSet(false, true)
                  ? HookOutcome.inject("pre-stop inject")
                  : HookOutcome.cont();
      TokenCounter counter =
          messages -> messages.stream().anyMatch(m -> m.role() == Role.TOOL) ? 96L : 10L;
      ContextCompactor compactor =
          (history, state) -> {
            compacted.set(history);
            return new CompactionResult(
                List.of(history.getFirst(), Message.user("compacted")),
                Usage.of(3, 4),
                "compactor-model",
                null);
          };
      InstantSource clock = now::get;
      var script = ScriptedModel.newBuilder();
      replies.forEach(script::withStreamTurn);
      model = script.build();
      var session =
          AgentSession.open(
              SessionOptions.newBuilder()
                  .withModel(model)
                  .withSessionId("characterization")
                  .withClock(clock)
                  .withLimits(limits().build())
                  .withTools(List.of(echo()))
                  .withOutputSchema(OutputSchema.of(Answer.class))
                  .withTokenCounter(counter)
                  .withContextCompactor(compactor)
                  .withCostCalculator(
                      (modelId, usage) ->
                          CostEstimate.ofMicroUsd(modelId.equals("compactor-model") ? 1 : 100))
                  .withHook(preModelTurn)
                  .withHook(preToolUse)
                  .withHook(postToolUse)
                  .withHook(preStop)
                  .build());
      events.read(session.events());
      result =
          session.send(
              UserMessage.newBuilder()
                  .withText("start")
                  .withAttachment(new byte[] {1, 2, 3}, "image/png")
                  .build());
      return session;
    }

    List<QueryEvent> awaitEvents() {
      events.awaitRunEnded();
      return events.events();
    }

    /** The session has an output schema, so every model call must carry it. */
    void assertEveryCallCarriedTheSchema() {
      assertTrue(
          model.outputSchemas().stream().allMatch(Optional::isPresent),
          "the session has an output schema");
    }

    static CloseableIterator<ModelChunk> transientFailure() {
      return ModelStreams.failing(
          new TransientStreamException(
              "Stream read error", new IOException("connection reset"), "test"));
    }

    private static ToolBinding echo() {
      var tool =
          Tool.newBuilder()
              .withName("echo")
              .withDescription("returns its 'v' arg")
              .withExecutor((args, ctx) -> ToolResult.success("echoed: " + args.get("v")))
              .build();
      return ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
    }
  }

  private static String describe(Message message) {
    return message.role()
        + " content="
        + oneLine(message.content())
        + " toolCalls="
        + message.toolCalls()
        + " toolCallId="
        + message.toolCallId()
        + " files="
        + message.inlineFiles().size();
  }

  private static String describe(QueryEvent event) {
    var detail =
        switch (event) {
          case QueryEvent.UserMessageReceived e ->
              oneLine(e.message().text()) + " files=" + e.message().attachments().size();
          case QueryEvent.HookFired e ->
              hook(e.hookName()) + " " + e.phase() + " " + e.outcomeKind();
          case QueryEvent.TurnEnded e -> String.valueOf(e.reason());
          case QueryEvent.TurnRetried e ->
              "attempt=" + e.attemptNumber() + " delay=" + e.backoff() + " " + e.providerName();
          case QueryEvent.ToolUse e -> e.call().id() + " " + e.call().arguments();
          case QueryEvent.ToolMutated e ->
              e.call().id()
                  + " "
                  + hook(e.hookName())
                  + " "
                  + e.inputBefore()
                  + "->"
                  + e.inputAfter();
          case QueryEvent.ToolBlocked e ->
              e.call().id() + " " + hook(e.hookName()) + " " + e.reason();
          case QueryEvent.ToolResult e ->
              e.call().id()
                  + " "
                  + e.result().outcome().getClass().getSimpleName()
                  + " "
                  + hook(e.result().output());
          case QueryEvent.AssistantText e -> e.text();
          case QueryEvent.ModelCallEnded e ->
              "attempt="
                  + e.attempt()
                  + " "
                  + e.finishReason()
                  + " tokens="
                  + e.usage().totalTokens()
                  + " error="
                  + (e.error() != null);
          case QueryEvent.ContextWarning e -> String.valueOf(e.usagePct());
          case QueryEvent.ContextEdited e ->
              "removed=" + e.removedBlocks() + " " + e.tokensBefore() + "->" + e.tokensAfter();
          case QueryEvent.RunStarted e -> e.runId().startsWith("run-") ? "run" : e.runId();
          case QueryEvent.RunEnded e -> e.result().getClass().getSimpleName();
          default -> "";
        };
    return event.getClass().getSimpleName() + " turn=" + event.turnIndex() + " " + detail;
  }

  private static String hook(String name) {
    return name.replaceAll("\\$\\$Lambda[^:]*", "");
  }

  private static String oneLine(String text) {
    return text == null ? null : text.replace("\n", "\\n");
  }
}
