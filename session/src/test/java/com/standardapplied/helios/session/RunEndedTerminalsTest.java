/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every way a run ends, each terminal, an interrupt and a close mid-run, stores a {@link
 * SessionEntry.RunEnded} naming the run as the last entry of its last batch.
 */
final class RunEndedTerminalsTest {

  private static final String SID = "chat-1";

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
  private final CountDownLatch entered = new CountDownLatch(1);
  private final RecordingStore store = new RecordingStore();

  // ── every terminal ──────────────────────────────────────────────────────

  static Stream<Arguments> endings() {
    return Stream.of(
        ending("Success", RunEndedTerminalsTest::success),
        ending("ErrorDuringExecution", RunEndedTerminalsTest::failedExecution),
        ending("ErrorMaxTurns", RunEndedTerminalsTest::pastItsTurns),
        ending("ErrorMaxBudgetUsd", RunEndedTerminalsTest::pastItsBudget),
        ending("ErrorMaxWallClock", RunEndedTerminalsTest::pastItsWallClock),
        ending("ErrorTransientStream", RunEndedTerminalsTest::failedTransiently),
        ending("Refusal", RunEndedTerminalsTest::refused),
        ending("Cancelled", RunEndedTerminalsTest::interrupted),
        ending("Cancelled", RunEndedTerminalsTest::closedMidRun));
  }

  private static Arguments ending(
      String terminal, Function<RunEndedTerminalsTest, ResultMessage> run) {
    return Arguments.of(terminal, run);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endings")
  void everyTerminalIsStoredAsTheLastEntryOfTheRunsLastBatch(
      String terminal, Function<RunEndedTerminalsTest, ResultMessage> run) {
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
    var model =
        ScriptedModel.newBuilder().withToolCallsTurn(new ToolCall("c1", "noop", Map.of())).build();
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

  private SessionOptions.Builder options(ScriptedModel model) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withStore(store)
        .withClock(now::get);
  }

  private static ResultMessage sent(SessionOptions.Builder options) {
    try (var session = AgentSession.open(options.build())) {
      return Await.value("the run", session.send("go"));
    }
  }

  private CloseableIterator<ModelChunk> stalled() {
    return ModelStreams.stalled(entered::countDown);
  }
}
