/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStoreException;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Every way a run ends stores a {@link SessionEntry.RunEnded} naming it, and every message the
 * session writes itself, a rejected structured output, its correction and a hook's {@code Inject},
 * is stored tagged with its {@link MessageOrigin}, never merged with the caller's input.
 */
final class RunEndedSessionTest {

  private static final String SID = "chat-1";
  private static final String VALID = "{\"answer\":\"final\"}";

  /** The schema the structured-output runs answer. */
  public record Answer(String answer) {}

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
  private RecordingStore store = new RecordingStore();

  // ── schema correction ───────────────────────────────────────────────────

  @Test
  void aCorrectedRunStoresItsTaggedAttemptAndCorrectionThenItsAnswerWithItsEndInOneBatch() {
    var result = sent(structured(correcting()));

    assertEquals(
        List.of(List.of("USER", "ASSISTANT", "USER"), List.of("ASSISTANT", "ended Success")),
        store.shapes());
    var stored = store.load(SID);
    assertEquals(
        List.of(
            Optional.empty(),
            Optional.of(MessageOrigin.REJECTED_ATTEMPT),
            Optional.of(MessageOrigin.SCHEMA_CORRECTION),
            Optional.empty()),
        stored.history().stream().map(MessageOrigin::of).toList());
    assertEquals(
        List.of("go", "not json", correction().correctionMessage(), VALID),
        stored.history().stream().map(Message::content).toList());
    assertEquals(
        Optional.of(
            new SessionEntry.RunEnded(result.runId(), now.get(), "Success", Optional.of(VALID))),
        stored.lastRunEnded());
  }

  @Test
  void aRefusedWriteAfterTheRejectedAttemptLeavesItAndItsCorrectionStoredTaggedAndTheRunNotEnded() {
    store =
        new RecordingStore(index -> index == 1 ? new SessionStoreException("down", null) : null);

    var result = sent(structured(correcting()));

    assertEquals(
        "SessionStoreFailed",
        assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result).error().kind());
    assertEquals(List.of(List.of("USER", "ASSISTANT", "USER")), store.shapes());
    var stored = store.load(SID);
    assertEquals(Optional.empty(), stored.lastRunEnded());
    assertEquals(
        List.of(
            Optional.empty(),
            Optional.of(MessageOrigin.REJECTED_ATTEMPT),
            Optional.of(MessageOrigin.SCHEMA_CORRECTION)),
        stored.history().stream().map(MessageOrigin::of).toList());
    assertEquals("not json", stored.history().get(1).content());
  }

  @Test
  void theCorrectionReachesTheModelAsItsOwnUserMessageAfterTheRejectedAttempt() {
    var model = correcting();

    sent(structured(model));

    var retry = model.calls().get(1);
    assertEquals(
        List.of(Role.USER, Role.ASSISTANT, Role.USER), retry.stream().map(Message::role).toList());
    assertEquals(correction().correctionMessage(), retry.getLast().content());
  }

  // ── the run's last batch ────────────────────────────────────────────────

  @Test
  void aRefusedLastBatchStoresNeitherTheFinalTurnNorTheRunsEnd() {
    store =
        new RecordingStore(index -> index == 1 ? new SessionStoreException("full", null) : null);
    var model = ScriptedModel.newBuilder().withToolCallsTurn(noop()).withTextTurn("done").build();

    var result = sent(options(model).withTool(noopTool()));

    var error = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result).error();
    assertEquals(List.of("SessionStoreFailed", "full"), List.of(error.kind(), error.message()));
    assertEquals(List.of(List.of("USER", "ASSISTANT", "TOOL")), store.shapes());
    var stored = store.load(SID);
    assertEquals(Optional.empty(), stored.lastRunEnded());
    assertEquals(Role.TOOL, stored.history().getLast().role());
  }

  @Test
  void aRefusedWriteOfATurnTheRunGoesOnFromEndsItWithTheRefusalAndNoRunEnded() {
    store = new RecordingStore(index -> new SessionStoreException("full", null));
    var model = ScriptedModel.newBuilder().withToolCallsTurn(noop()).withTextTurn("done").build();

    var result = sent(options(model).withTool(noopTool()));

    var error = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result).error();
    assertEquals("SessionStoreFailed", error.kind());
    assertEquals(1, model.calls().size());
    assertEquals(List.of(), store.shapes());
  }

  @Test
  void aRunInterruptedDuringATurnStoresThatTurnWithItsEnd() {
    var session = new AtomicReference<AgentSession>();
    var interrupting =
        EndingSessions.binding(
            "noop",
            (args, ctx) -> {
              session.get().interrupt();
              return ToolResult.success("ok");
            });
    var model = ScriptedModel.newBuilder().withToolCallsTurn(noop()).withTextTurn("never").build();
    ResultMessage result;
    try (var opened = AgentSession.open(options(model).withTool(interrupting).build())) {
      session.set(opened);
      result = Await.value("the run", opened.send("go"));
    }

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals(List.of(List.of("USER", "ASSISTANT", "TOOL", "ended Cancelled")), store.shapes());
  }

  @Test
  void aRunInterruptedBetweenTurnsStoresItsEndAloneAfterTheTurnItWentOnFrom() {
    var session = new AtomicReference<AgentSession>();
    ContextCompactor interrupting =
        (history, context) -> {
          session.get().interrupt();
          return CompactionResult.noOp(history);
        };
    var model = ScriptedModel.newBuilder().withToolCallsTurn(noop()).withTextTurn("never").build();
    ResultMessage result;
    try (var opened =
        AgentSession.open(
            options(model)
                .withTool(noopTool())
                .withContextCompactor(interrupting)
                .withTokenCounter(
                    messages -> messages.stream().anyMatch(m -> m.role() == Role.TOOL) ? 96L : 10L)
                .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100).build())
                .build())) {
      session.set(opened);
      result = Await.value("the run", opened.send("go"));
    }

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals(1, model.calls().size());
    assertEquals(
        List.of(List.of("USER", "ASSISTANT", "TOOL"), List.of("ended Cancelled")), store.shapes());
  }

  @Test
  void aFinalTurnOverTheWatermarkKeepsItsAnswerAndTheNextRunCompactsBeforeItsModelCall() {
    var model =
        ScriptedModel.newBuilder()
            .withTextTurn("answer one")
            .withTextTurn("the summary")
            .withTextTurn("answer two")
            .build();
    TokenCounter counter =
        messages ->
            messages.stream()
                .mapToLong(
                    m -> m.content().startsWith(SummarizingCompactor.SUMMARY_PREFIX) ? 1 : 50)
                .sum();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    SessionEntry.RunEnded firstEnded;
    List<Message> firstHistory;
    try (var session =
        AgentSession.open(options(model).withLimits(limits).withTokenCounter(counter).build())) {
      var first = Await.value("run 1", session.send("first"));
      var stored = store.load(SID);
      firstEnded = stored.lastRunEnded().orElseThrow();
      firstHistory = stored.history();
      assertEquals(first.runId(), firstEnded.runId());
      Await.value("run 2", session.send("second"));
    }

    assertEquals(List.of(Message.user("first"), Message.assistant("answer one")), firstHistory);
    assertEquals(100L, counter.count(firstHistory));
    assertEquals(List.of("USER", "ASSISTANT", "ended Success"), store.shapes().getFirst());
    assertEquals("Success", firstEnded.terminal());
    assertEquals(Optional.of("answer one"), firstEnded.result());
    var request = model.calls().getLast();
    assertEquals(
        List.of(SummarizingCompactor.SUMMARY_PREFIX + "the summary", "second"),
        request.stream().map(Message::content).toList());
    assertEquals(51L, counter.count(request));
  }

  @Test
  void aBudgetOnlyASummaryCallExceedsEndsTheRunAtTheNextTurnWithItsEndStored() {
    var model =
        ScriptedModel.newBuilder()
            .withId("priced")
            .withToolCallsTurn(Usage.of(0, 0), noop())
            .withTextTurn("done", Usage.of(0, 0))
            .build();
    ContextCompactor pricedSummary =
        (history, context) ->
            new CompactionResult(
                List.of(history.getFirst()), Usage.of(1_000_000, 1_000_000), "priced");

    var result =
        sent(
            options(model)
                .withTool(noopTool())
                .withContextCompactor(pricedSummary)
                .withTokenCounter(
                    messages -> messages.stream().anyMatch(m -> m.role() == Role.TOOL) ? 96L : 10L)
                .withCostCalculator(
                    CostCalculator.staticTable(
                        Map.of("priced", CostCalculator.Pricing.ofUsdPerMillion(1.0, 1.0))))
                .withLimits(
                    SessionLimits.newBuilder()
                        .withMaxContextTokens(100)
                        .withMaxBudgetMicroUsd(1_000_000L)
                        .build()));

    var budget = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, result);
    assertEquals(2_000_000L, budget.microUsdSpent());
    assertEquals(2, model.calls().size());
    assertEquals(
        Optional.of(
            new SessionEntry.RunEnded(
                result.runId(), now.get(), "ErrorMaxBudgetUsd", Optional.empty())),
        store.load(SID).lastRunEnded());
    assertEquals(
        List.of(List.of("replaced"), List.of("ASSISTANT", "ended ErrorMaxBudgetUsd")),
        store.shapes());
  }

  // ── the run's answer ────────────────────────────────────────────────────

  @Test
  void aPreStopStopStoresItsTextAsTheRunsResultAndAddsNoMessage() {
    PreStopHook stopping = (response, ctx) -> HookOutcome.stop("hook answer");
    var model = ScriptedModel.newBuilder().withTextTurn("model draft").build();

    var result = sent(options(model).withHook(stopping));

    var stored = store.load(SID);
    assertEquals(
        Optional.of(
            new SessionEntry.RunEnded(
                result.runId(), now.get(), "Success", Optional.of("hook answer"))),
        stored.lastRunEnded());
    assertEquals(List.of(Message.user("go"), Message.assistant("model draft")), stored.history());
  }

  @Test
  void anOnUserMessageStopStoresItsTextAsTheRunsResultWithoutAModelCall() {
    OnUserMessageHook stopping = (message, ctx) -> HookOutcome.stop("handled");
    var model = ScriptedModel.newBuilder().withTextTurn("never").build();

    var result = sent(options(model).withHook(stopping));

    assertEquals(List.of(), model.calls());
    assertEquals(List.of(List.of("ended Success")), store.shapes());
    assertEquals(
        Optional.of(
            new SessionEntry.RunEnded(
                result.runId(), now.get(), "Success", Optional.of("handled"))),
        store.load(SID).lastRunEnded());
  }

  @Test
  void anEmptyReplyStoresAnEmptyResultThoughAnEarlierRunsAnswerIsTheLastAssistantMessage() {
    var model = ScriptedModel.newBuilder().withTextTurn("first answer").withTextTurn("").build();

    ResultMessage second;
    try (var session = AgentSession.open(options(model).build())) {
      Await.value("the first run", session.send("one"));
      second = Await.value("the second run", session.send("two"));
    }

    var stored = store.load(SID);
    assertEquals(
        Optional.of(
            new SessionEntry.RunEnded(second.runId(), now.get(), "Success", Optional.of(""))),
        stored.lastRunEnded());
    assertEquals(
        List.of(Message.user("one"), Message.assistant("first answer"), Message.user("two")),
        stored.history());
  }

  // ── inject ──────────────────────────────────────────────────────────────

  @Test
  void aPreStopInjectIsStoredAsAUserMessageOfItsOwnTaggedHookInject() {
    var fires = new AtomicInteger();
    PreStopHook injecting =
        (response, ctx) ->
            fires.incrementAndGet() == 1 ? HookOutcome.inject("more please") : HookOutcome.cont();
    var storedBeforeSecondCall = new AtomicReference<List<Message>>();
    PreModelTurnHook observing =
        (history, ctx) -> {
          if (ctx.turnIndex() == 2) {
            storedBeforeSecondCall.set(store.load(SID).history());
          }
          return HookOutcome.cont();
        };
    var model = ScriptedModel.newBuilder().withTextTurn("draft").withTextTurn("final").build();

    sent(options(model).withHook(injecting).withHook(observing));

    assertEquals(
        List.of(List.of("USER", "ASSISTANT", "USER"), List.of("ASSISTANT", "ended Success")),
        store.shapes());
    var inject = injected("more please");
    var beforeSecondCall = List.of(Message.user("go"), Message.assistant("draft"), inject);
    assertEquals(beforeSecondCall, storedBeforeSecondCall.get());
    assertEquals(beforeSecondCall, model.calls().getLast());
    assertEquals(
        List.of(Message.user("go"), Message.assistant("draft"), inject, Message.assistant("final")),
        store.load(SID).history());
  }

  @Test
  void anInjectDrainedWithACallersMessageStaysAMessageOfItsOwnInDrainOrder() {
    var session = new AtomicReference<AgentSession>();
    var fires = new AtomicInteger();
    PreStopHook steering =
        (response, ctx) -> {
          if (fires.incrementAndGet() > 1) {
            return HookOutcome.cont();
          }
          session.get().send("from the caller");
          return HookOutcome.inject("from the hook");
        };
    var model = ScriptedModel.newBuilder().withTextTurn("draft").withTextTurn("final").build();

    try (var opened = AgentSession.open(options(model).withHook(steering).build())) {
      session.set(opened);
      Await.value("the run", opened.send("go"));
    }

    var request = model.calls().getLast();
    assertEquals(
        List.of(Message.user("from the caller"), injected("from the hook")), request.subList(2, 4));
    assertEquals(request, store.load(SID).history().subList(0, 4));
  }

  // ── compaction ──────────────────────────────────────────────────────────

  @Test
  void aSummaryKeepsTheTaggedCorrectionItRetainsAndCarriesNoTagItself() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(ModelStreams.failing(correction()))
            .withTextTurn("the summary")
            .withTextTurn(VALID)
            .build();

    var result = sent(overWatermark(structured(model)));

    assertEquals(
        List.of(List.of("replaced"), List.of("ASSISTANT", "ended Success")), store.shapes());
    var replaced =
        assertInstanceOf(SessionEntry.HistoryReplaced.class, store.batches.getFirst().getFirst());
    assertEquals(
        List.of(Optional.empty(), Optional.of(MessageOrigin.SCHEMA_CORRECTION)),
        replaced.history().stream().map(MessageOrigin::of).toList());
    assertEquals(
        SummarizingCompactor.SUMMARY_PREFIX + "the summary",
        replaced.history().getFirst().content());
    assertEquals(
        Optional.of(
            new SessionEntry.RunEnded(result.runId(), now.get(), "Success", Optional.of(VALID))),
        store.load(SID).lastRunEnded());
  }

  @Test
  void aMutateHistoryHookKeepsTheTagsOfTheMessagesItRetains() {
    PreModelTurnHook droppingTheInput =
        (history, ctx) ->
            ctx.turnIndex() == 2
                ? HookOutcome.mutateHistory(history.subList(1, history.size()))
                : HookOutcome.cont();

    var result = sent(structured(correcting()).withHook(droppingTheInput));

    var stored = store.load(SID);
    assertEquals(
        List.of(
            Optional.of(MessageOrigin.REJECTED_ATTEMPT),
            Optional.of(MessageOrigin.SCHEMA_CORRECTION),
            Optional.empty()),
        stored.history().stream().map(MessageOrigin::of).toList());
    assertEquals(
        Optional.of(
            new SessionEntry.RunEnded(result.runId(), now.get(), "Success", Optional.of(VALID))),
        stored.lastRunEnded());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private SessionOptions.Builder options(ScriptedModel model) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withStore(store)
        .withClock(now::get);
  }

  private SessionOptions.Builder structured(ScriptedModel model) {
    return options(model).withOutputSchema(OutputSchema.of(Answer.class));
  }

  /** {@code options} with every history counted over the compaction watermark. */
  private static SessionOptions.Builder overWatermark(SessionOptions.Builder options) {
    return options
        .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100).build())
        .withTokenCounter(messages -> 1_000L * messages.size());
  }

  private static ResultMessage sent(SessionOptions.Builder options) {
    try (var session = AgentSession.open(options.build())) {
      return Await.value("the run", session.send("go"));
    }
  }

  /** A model whose first answer is rejected by the schema and whose second is valid. */
  private static ScriptedModel correcting() {
    return ScriptedModel.newBuilder()
        .withStreamTurn(ModelStreams.failing(correction()))
        .withTextTurn(VALID)
        .build();
  }

  private static StructuredOutputParseException correction() {
    return new StructuredOutputParseException(
        List.of("answer is required but missing"), "not json");
  }

  private static Message injected(String text) {
    return Message.newBuilder()
        .withRole(Role.USER)
        .withContent(text)
        .withMetadata(MessageOrigin.HOOK_INJECT.metadata())
        .build();
  }

  private static ToolCall noop() {
    return new ToolCall("c1", "noop", Map.of());
  }

  private static ToolBinding noopTool() {
    return EndingSessions.binding("noop", (args, ctx) -> ToolResult.success("ok"));
  }
}
