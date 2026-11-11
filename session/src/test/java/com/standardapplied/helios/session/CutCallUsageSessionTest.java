/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A model call cut short counts the usage its stream reported before the cut, once and as a
 * completed call's does: in the run's totals, its terminal and its {@link QueryEvent.RunEnded},
 * priced, and checked against the budget. A retried attempt counts too, a transient failure that
 * takes the run over its budget is not retried, and a call cut before its stream reported any usage
 * counts zero. The cuts are those of {@code PartialTurnSessionTest}.
 *
 * <p>A failed stream reports usage ahead of its failure as a shipped provider's does. The cuts by
 * {@code interrupt()}, {@code close()} and the wall clock need a stream that yields a {@link
 * ModelChunk.UsageDelta} before the cut, which {@code ScriptedModel} does and no shipped provider
 * does yet: those providers pass usage to the session only when the stream ends, so such a call of
 * theirs still counts zero. These cases pin that the session keeps a usage it read before the cut.
 */
final class CutCallUsageSessionTest {

  private static final String MODEL = "metered";
  private static final Usage REPORTED = Usage.of(1_000, 200);
  private static final ModelChunk USAGE = new ModelChunk.UsageDelta(REPORTED);
  private static final ModelChunk TEXT = new ModelChunk.TextDelta("Half a sentence");
  private static final CostCalculator ONE_MICRO_USD_A_TOKEN =
      CostCalculator.staticTable(Map.of(MODEL, new Pricing(1_000_000L, 1_000_000L)));

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-11T10:00:00Z"));
  private final CountDownLatch stalled = new CountDownLatch(1);
  private final EventCollector events = new EventCollector();

  /** How a test cuts a model call that streamed {@code chunks}; returns the run's result. */
  @FunctionalInterface
  interface Cut {
    ResultMessage after(CutCallUsageSessionTest test, ModelChunk... chunks);
  }

  static Stream<Arguments> cuts() {
    return Stream.of(
        cut(
            "stream failure",
            (test, chunks) -> test.failed(new IllegalStateException("connection reset"), chunks)),
        cut(
            "interrupt after a scripted usage chunk",
            (test, chunks) -> test.stalledThen(AgentSession::interrupt, chunks)),
        cut(
            "close after a scripted usage chunk",
            (test, chunks) -> test.stalledThen(AgentSession::close, chunks)),
        cut("wall clock after a scripted usage chunk", CutCallUsageSessionTest::pastItsWallClock));
  }

  private static Arguments cut(String how, Cut cut) {
    return Arguments.of(how, cut);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cuts")
  void aCutCallCountsTheUsageItsStreamReportedBeforeTheCutOnce(String how, Cut cut) {
    var result = cut.after(this, USAGE, TEXT);

    assertEquals(REPORTED, result.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_200), result.cost());
    assertEquals(List.of(result), runEndedResults());
    assertEquals(List.of(REPORTED), modelCallUsages());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cuts")
  void aCallCutBeforeItsStreamReportedUsageCountsZero(String how, Cut cut) {
    var result = cut.after(this, TEXT);

    assertEquals(Usage.of(0, 0), result.usage());
    assertEquals(CostEstimate.zero(), result.cost());
    assertEquals(List.of(Usage.of(0, 0)), modelCallUsages());
  }

  @Test
  void aTransientFailureRetriedTwiceThenSucceedingCountsAllThreeAttempts() {
    var overloaded = new TransientStreamException("overloaded", null, "scripted");
    var answered = Usage.of(300, 30);
    var model =
        scripted()
            .withStreamTurn(ModelStreams.failing(overloaded, USAGE, TEXT))
            .withStreamTurn(ModelStreams.failing(overloaded, USAGE, TEXT))
            .withTextTurn("done", answered)
            .build();
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(new StreamRetryPolicy(3, Backoff.fixed(Duration.ZERO), 0.0))
            .build();

    var result = run(model, limits);

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(REPORTED.plus(REPORTED).plus(answered), result.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_200 + 1_200 + 330), result.cost());
    assertEquals(List.of(REPORTED, REPORTED, answered), modelCallUsages());
    assertEquals(
        List.of(1, 2, 3),
        events.eventsOf(QueryEvent.ModelCallEnded.class).stream()
            .map(QueryEvent.ModelCallEnded::attempt)
            .toList());
  }

  @Test
  void aCutCallWhoseUsageExceedsTheBudgetEndsTheRunOverBudget() {
    var model =
        scripted()
            .withStreamTurn(
                ModelStreams.failing(new IllegalStateException("connection reset"), USAGE, TEXT))
            .build();

    var result = run(model, SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000).build());

    var overBudget = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, result);
    assertEquals(1_200, overBudget.microUsdSpent());
    assertEquals(REPORTED, overBudget.usage());
  }

  @Test
  void aTransientFailureThatTakesTheRunOverItsBudgetIsNotRetried() {
    var overloaded = new TransientStreamException("overloaded", null, "scripted");
    var model =
        scripted()
            .withStreamTurn(ModelStreams.failing(overloaded, USAGE))
            .withStreamTurn(ModelStreams.failing(overloaded, USAGE))
            .withStreamTurn(ModelStreams.failing(overloaded, USAGE))
            .build();
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(new StreamRetryPolicy(3, Backoff.fixed(Duration.ZERO), 0.0))
            .withMaxBudgetMicroUsd(1_000)
            .build();

    var result = run(model, limits);

    var overBudget = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, result);
    assertEquals(1_200, overBudget.microUsdSpent());
    assertEquals(List.of(REPORTED), modelCallUsages());
    assertEquals(List.of(), events.eventsOf(QueryEvent.TurnRetried.class));
    assertEquals(1, model.calls().size());
  }

  private ResultMessage failed(RuntimeException failure, ModelChunk... chunks) {
    return run(
        scripted().withStreamTurn(ModelStreams.failing(failure, chunks)).build(),
        SessionLimits.defaults());
  }

  private ResultMessage pastItsWallClock(ModelChunk... chunks) {
    return stalledThen(
        session -> {
          now.set(now.get().plus(SessionLimits.defaults().maxWallClock()).plusSeconds(1));
          ((AgentSessionImpl) session).lifecycle.expireWallClock();
        },
        chunks);
  }

  /** The result of a run whose model call streams {@code chunks}, then stalls until {@code cut}. */
  private ResultMessage stalledThen(Consumer<AgentSession> cut, ModelChunk... chunks) {
    var model = scripted().withStreamTurn(ModelStreams.stalled(stalled::countDown, chunks)).build();
    try (var session = open(model, SessionLimits.defaults())) {
      var result = session.send("go");
      Await.latch("the model stream to stall after its chunks", stalled);
      cut.accept(session);
      return Await.value("the cut run", result);
    }
  }

  private ResultMessage run(ScriptedModel model, SessionLimits limits) {
    try (var session = open(model, limits)) {
      return Await.value("the run", session.send("go"));
    }
  }

  private AgentSession open(ScriptedModel model, SessionLimits limits) {
    var session =
        AgentSession.open(
            SessionOptions.newBuilder()
                .withModel(model)
                .withCostCalculator(ONE_MICRO_USD_A_TOKEN)
                .withLimits(limits)
                .withClock(now::get)
                .build());
    events.read(session.events());
    return session;
  }

  private static ScriptedModel.Builder scripted() {
    return ScriptedModel.newBuilder().withId(MODEL);
  }

  /** The usage of each model call the closed session reported, in order. */
  private List<Usage> modelCallUsages() {
    events.awaitDone();
    return events.eventsOf(QueryEvent.ModelCallEnded.class).stream()
        .map(QueryEvent.ModelCallEnded::usage)
        .toList();
  }

  /** The result each run of the closed session ended with, in order. */
  private List<ResultMessage> runEndedResults() {
    events.awaitDone();
    return events.eventsOf(QueryEvent.RunEnded.class).stream()
        .map(QueryEvent.RunEnded::result)
        .toList();
  }
}
