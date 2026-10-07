/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.fault.Backoff;
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
import com.standardapplied.helios.session.hooks.CompactionPayload;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostCompactHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreCompactHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the complete observable behaviour of one scripted session that passes through every stage of
 * the loop: a steering message with an attachment, a pre-model-turn inject, a retried transient
 * stream failure, a tool round with mutate-args, block and post-tool mutate-result hooks, a
 * compaction with pre- and post-compact hooks, a schema self-correction and a pre-stop inject. The
 * same run then ends early by a wall-clock expiry, a cancellation and a hook that throws an {@link
 * Error}.
 */
class SessionRunCharacterizationTest {

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  record Answer(String answer) {}

  @Test
  void scriptedRunEmitsTheCompleteEventSequenceAndBuildsTheFinalHistory() {
    var run = new Run();
    var postCompactPayload = new AtomicReference<CompactionPayload>();
    run.postCompact =
        (payload, ctx) -> {
          postCompactPayload.set(payload);
          return HookOutcome.cont();
        };

    try (var session = run.start()) {
      var terminal = Await.value("the scripted run's terminal", session.result());
      var events = run.awaitEvents();
      run.assertEveryCallCarriedTheSchema();

      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals("{\"answer\":\"final\"}", success.result());
      assertEquals(Usage.of(63, 22), success.usage());
      assertEquals(CostEstimate.ofMicroUsd(301), success.cost());
      assertEquals(
          EXPECTED_EVENTS, events.stream().map(SessionRunCharacterizationTest::describe).toList());
      assertEquals(
          EXPECTED_FINAL_MODEL_INPUT,
          run.model.calls().getLast().stream()
              .map(SessionRunCharacterizationTest::describe)
              .toList());
      assertEquals(5, run.model.calls().size());
      var payload = postCompactPayload.get();
      assertEquals(6, payload.historyBefore().size());
      assertEquals(2, payload.historyAfter().size());
      assertEquals(96L, payload.tokensBefore());
      assertEquals(10L, payload.tokensAfter());
      assertEquals(4, payload.removedBlocks());
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
      var terminal = Await.value("the wall-clock terminal", session.result());

      assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, terminal);
      run.assertEveryCallCarriedTheSchema();
      assertShutDown(session);
    }
  }

  @Test
  void cancellationDuringAStalledTurnEndsTheRunAsCancelledAndShutsTheExecutorsDown() {
    var run = new Run();
    run.limits = run.limits().withStreamIdleTimeout(Duration.ofHours(1));
    var stalled = new CountDownLatch(1);
    run.replies.removeLast();
    run.replies.removeLast();
    run.replies.removeLast();
    run.replies.add(ModelStreams.stalled(stalled::countDown));

    var session = run.start();
    Await.latch("the stalled schema turn", stalled);
    session.close();
    var terminal = Await.value("the cancelled terminal", session.result());

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
      var failure = Await.failure("the escaped error", session.result());

      assertInstanceOf(AssertionError.class, failure);
      assertEquals("hook bug", failure.getMessage());
      run.assertEveryCallCarriedTheSchema();
      assertShutDown(session);
    }
  }

  private static void assertShutDown(AgentSession session) {
    var impl = (AgentSessionImpl) session;
    assertTrue(
        impl.lifecycleForTests().publisherExecutor().isShutdown(), "publisher executor shut down");
    assertTrue(impl.lifecycleForTests().scheduler().isShutdown(), "deadline scheduler shut down");
  }

  private static final List<String> EXPECTED_EVENTS =
      List.of(
          "UserMessageReceived turn=0 start files=1",
          "HookFired turn=1 SessionRunCharacterizationTest$Run PreModelTurnHook Inject",
          "TurnEnded turn=1 TOOL_USE",
          "UserMessageReceived turn=1 pre-model inject files=0",
          "TurnRetried turn=2 attempt=1 delay=PT0S test",
          "HookFired turn=2 SessionRunCharacterizationTest$Run PreToolUseHook MutateArgs",
          "ToolMutated turn=2 c1 SessionRunCharacterizationTest$Run {v=one}->{v=mutated}",
          "ToolUse turn=2 c1 {v=mutated}",
          "ToolResult turn=2 c1 echoed: mutated",
          "HookFired turn=2 SessionRunCharacterizationTest$Run PostToolUseHook MutateResult",
          "ToolResult turn=2 c1 post-mutated: echoed: mutated",
          "HookFired turn=2 SessionRunCharacterizationTest$Run PreToolUseHook Block",
          "ToolBlocked turn=2 c2 SessionRunCharacterizationTest$Run not allowed",
          "ToolResult turn=2 c2 blocked by hook: not allowed",
          "TurnEnded turn=2 TOOL_USE",
          "ContextWarning turn=2 0.96",
          "HookFired turn=2 SessionRunCharacterizationTest$Run PreCompactHook MutateHistory",
          "ContextEdited turn=2 removed=4 96->10",
          "AssistantText turn=3 not json",
          "TurnEnded turn=3 TOOL_USE",
          "UserMessageReceived turn=3 Your structured output did not match the schema. Fix the listed fields and re-emit the structured output:\\n  - answer is required but missing\\n files=0",
          "AssistantText turn=4 {\"answer\":\"draft\"}",
          "TurnEnded turn=4 END_TURN",
          "HookFired turn=4 SessionRunCharacterizationTest$Run PreStopHook Inject",
          "UserMessageReceived turn=4 pre-stop inject files=0",
          "AssistantText turn=5 {\"answer\":\"final\"}",
          "TurnEnded turn=5 END_TURN",
          "LoopEnded turn=5 Success");

  private static final List<String> EXPECTED_FINAL_MODEL_INPUT =
      List.of(
          "USER content=start toolCalls=[] toolCallId=null files=1",
          "USER content=pre-compact note toolCalls=[] toolCallId=null files=0",
          "ASSISTANT content=not json toolCalls=[] toolCallId=null files=0",
          "USER content=Your structured output did not match the schema. Fix the listed fields and re-emit the structured output:\\n  - answer is required but missing\\n toolCalls=[] toolCallId=null files=0",
          "ASSISTANT content={\"answer\":\"draft\"} toolCalls=[] toolCallId=null files=0",
          "USER content=pre-stop inject toolCalls=[] toolCallId=null files=0");

  /** The scripted session, adjustable per test before {@link #start()}. */
  private static final class Run {

    final AtomicReference<Instant> now = new AtomicReference<>(START);
    final List<Flow.Publisher<ModelChunk>> replies =
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
    final CollectingSubscriber events = new CollectingSubscriber();
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
    PostCompactHook postCompact = (payload, ctx) -> HookOutcome.cont();

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
      PreCompactHook preCompact =
          (history, ctx) -> {
            var mutated = new ArrayList<>(history);
            mutated.add(Message.user("pre-compact note"));
            return HookOutcome.mutateHistory(mutated);
          };
      PreStopHook preStop =
          (response, ctx) ->
              preStopFired.compareAndSet(false, true)
                  ? HookOutcome.inject("pre-stop inject")
                  : HookOutcome.cont();
      TokenCounter counter =
          messages -> messages.stream().anyMatch(m -> m.role() == Role.TOOL) ? 96L : 10L;
      ContextCompactor compactor =
          (history, state) ->
              new CompactionResult(
                  List.of(history.getFirst(), history.getLast()),
                  Usage.of(3, 4),
                  "compactor-model");
      InstantSource clock = now::get;
      var script = ScriptedModel.newBuilder();
      replies.forEach(script::withStreamTurn);
      model = script.build();
      var session =
          AgentSession.create(
              SessionOptions.newBuilder()
                  .withModel(model)
                  .withSessionId("characterization")
                  .withClock(clock)
                  .withLimits(limits().build())
                  .withTools(new ToolRegistry(List.of(echo())))
                  .withOutputSchema(OutputSchema.of(Answer.class))
                  .withTokenCounter(counter)
                  .withContextCompactor(compactor)
                  .withCostCalculator(
                      (modelId, usage) ->
                          CostEstimate.ofMicroUsd(modelId.equals("compactor-model") ? 1 : 100))
                  .withHook(preModelTurn)
                  .withHook(preToolUse)
                  .withHook(postToolUse)
                  .withHook(preCompact)
                  .withHook(postCompact)
                  .withHook(preStop)
                  .build());
      session.events().subscribe(events);
      session.send(
          UserMessage.newBuilder()
              .withText("start")
              .withAttachment(new byte[] {1, 2, 3}, "image/png")
              .build());
      return session;
    }

    List<QueryEvent> awaitEvents() {
      events.awaitDone();
      return events.events();
    }

    /** The session has an output schema, so every model call must carry it. */
    void assertEveryCallCarriedTheSchema() {
      assertTrue(
          model.outputSchemas().stream().allMatch(Optional::isPresent),
          "the session has an output schema");
    }

    static Flow.Publisher<ModelChunk> transientFailure() {
      return subscriber -> {
        throw new TransientStreamException(
            "Stream read error", new IOException("connection reset"), "test");
      };
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
          case QueryEvent.ToolResult e -> e.call().id() + " " + e.result().output();
          case QueryEvent.AssistantText e -> e.text();
          case QueryEvent.ContextWarning e -> String.valueOf(e.usagePct());
          case QueryEvent.ContextEdited e ->
              "removed=" + e.removedBlocks() + " " + e.tokensBefore() + "->" + e.tokensAfter();
          case QueryEvent.LoopEnded e -> e.result().getClass().getSimpleName();
          default -> "";
        };
    return event.getClass().getSimpleName() + " turn=" + event.turnIndex() + " " + detail;
  }

  private static String hook(String name) {
    return name.replaceAll("\\$\\$Lambda.*", "");
  }

  private static String oneLine(String text) {
    return text == null ? null : text.replace("\n", "\\n");
  }
}
