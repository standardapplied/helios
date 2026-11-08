/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStoreException;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
  private final CountDownLatch entered = new CountDownLatch(1);
  private RecordingStore store = new RecordingStore();

  // ── every terminal ──────────────────────────────────────────────────────

  static Stream<Arguments> endings() {
    return Stream.of(
        ending("Success", RunEndedSessionTest::success),
        ending("ErrorDuringExecution", RunEndedSessionTest::failedExecution),
        ending("ErrorMaxTurns", RunEndedSessionTest::pastItsTurns),
        ending("ErrorMaxBudgetUsd", RunEndedSessionTest::pastItsBudget),
        ending("ErrorMaxWallClock", RunEndedSessionTest::pastItsWallClock),
        ending("ErrorTransientStream", RunEndedSessionTest::failedTransiently),
        ending("Refusal", RunEndedSessionTest::refused),
        ending("Cancelled", RunEndedSessionTest::interrupted),
        ending("Cancelled", RunEndedSessionTest::closedMidRun));
  }

  private static Arguments ending(
      String terminal, Function<RunEndedSessionTest, ResultMessage> run) {
    return Arguments.of(terminal, run);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endings")
  void everyTerminalIsStoredAsTheLastEntryOfTheRunsLastBatch(
      String terminal, Function<RunEndedSessionTest, ResultMessage> run) {
    var result = run.apply(this);

    assertEquals(terminal, result.getClass().getSimpleName());
    var ended = new SessionEntry.RunEnded(result.runId(), now.get(), terminal);
    assertEquals(Optional.of(ended), store.load(SID).lastRunEnded());
    assertEquals(ended, store.batches.getLast().getLast());
  }

  private ResultMessage success() {
    return sent(options(ScriptedModel.newBuilder().withTextTurn("done").build()));
  }

  private ResultMessage failedExecution() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(ModelStreams.failing(new IllegalStateException("broken")))
            .build();
    return sent(options(model));
  }

  private ResultMessage pastItsTurns() {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(noop()).build();
    return sent(
        options(model)
            .withTool(EndingSessions.binding("noop", (args, ctx) -> ToolResult.success("ok")))
            .withLimits(SessionLimits.newBuilder().withMaxTurns(1).build()));
  }

  private ResultMessage pastItsBudget() {
    var model =
        ScriptedModel.newBuilder()
            .withId("priced")
            .withResponseTurn(
                Response.newBuilder()
                    .withContent("expensive")
                    .withFinishReason(FinishReason.STOP)
                    .withUsage(Usage.of(1_000_000, 1_000_000))
                    .build())
            .build();
    return sent(
        options(model)
            .withCostCalculator(
                CostCalculator.staticTable(
                    Map.of("priced", CostCalculator.Pricing.ofUsdPerMillion(1.0, 1.0))))
            .withLimits(SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000_000L).build()));
  }

  private ResultMessage pastItsWallClock() {
    return stalledThen(
        session -> {
          now.set(now.get().plus(SessionLimits.defaults().maxWallClock()).plusSeconds(1));
          ((AgentSessionImpl) session).lifecycle.expireWallClock();
        });
  }

  private ResultMessage failedTransiently() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(new TransientStreamException("reset", null, "test")))
            .build();
    return sent(
        options(model)
            .withLimits(
                SessionLimits.newBuilder()
                    .withStreamRetryPolicy(StreamRetryPolicy.disabled())
                    .build()));
  }

  private ResultMessage refused() {
    return sent(options(ScriptedModel.newBuilder().withRefusalTurn("no").build()));
  }

  private ResultMessage interrupted() {
    return stalledThen(AgentSession::interrupt);
  }

  private ResultMessage closedMidRun() {
    return stalledThen(AgentSession::close);
  }

  /** The result of a run whose model call blocks until {@code action} ends it. */
  private ResultMessage stalledThen(Consumer<AgentSession> action) {
    var model = ScriptedModel.newBuilder().withStreamTurn(stalled()).build();
    try (var session = AgentSession.open(options(model).build())) {
      var result = session.send("go");
      Await.latch("the model stream to be read", entered);
      action.accept(session);
      return Await.value("the result", result);
    }
  }

  // ── schema correction ───────────────────────────────────────────────────

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

  // ── inject ──────────────────────────────────────────────────────────────

  @Test
  void aPreStopInjectIsStoredAsAUserMessageOfItsOwnTaggedHookInject() {
    var fires = new AtomicInteger();
    PreStopHook injecting =
        (response, ctx) ->
            fires.incrementAndGet() == 1 ? HookOutcome.inject("more please") : HookOutcome.cont();
    var model = ScriptedModel.newBuilder().withTextTurn("draft").withTextTurn("final").build();

    sent(options(model).withHook(injecting));

    var inject = injected("more please");
    assertEquals(
        List.of(Message.user("go"), Message.assistant("draft"), inject, Message.assistant("final")),
        store.load(SID).history());
    assertEquals(inject, model.calls().getLast().getLast());
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

    var replaced =
        (SessionEntry.HistoryReplaced)
            store.inner.entries(SID).stream()
                .filter(SessionEntry.HistoryReplaced.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertEquals(
        List.of(Optional.empty(), Optional.of(MessageOrigin.SCHEMA_CORRECTION)),
        replaced.history().subList(0, 2).stream().map(MessageOrigin::of).toList());
    assertEquals(
        SummarizingCompactor.SUMMARY_PREFIX + "the summary",
        replaced.history().getFirst().content());
    assertEquals(
        Optional.of(new SessionEntry.RunEnded(result.runId(), now.get(), "Success")),
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
        Optional.of(new SessionEntry.RunEnded(result.runId(), now.get(), "Success")),
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

  private CloseableIterator<ModelChunk> stalled() {
    return ModelStreams.stalled(entered::countDown);
  }

  private static ToolCall noop() {
    return new ToolCall("c1", "noop", Map.of());
  }
}
