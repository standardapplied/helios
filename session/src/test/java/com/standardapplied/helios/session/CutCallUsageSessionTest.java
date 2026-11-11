/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ProviderException;
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
 * A model call cut short counts the usage its provider reported before the cut, whatever cut it,
 * once and as a completed call's does: in the run's totals, its terminal and its {@link
 * QueryEvent.RunEnded}, priced, and checked against the budget. A retried attempt counts too, and a
 * call cut before its provider reported any usage counts zero. The cuts are those of {@code
 * PartialTurnSessionTest}.
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
            "idle timeout",
            (test, chunks) ->
                test.failed(
                    new ProviderException("Stream idle timeout: no data received for 120000 ms"),
                    chunks)),
        cut("interrupt", (test, chunks) -> test.stalledThen(AgentSession::interrupt, chunks)),
        cut("close", (test, chunks) -> test.stalledThen(AgentSession::close, chunks)),
        cut("wall clock", CutCallUsageSessionTest::pastItsWallClock));
  }

  private static Arguments cut(String how, Cut cut) {
    return Arguments.of(how, cut);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cuts")
  void aCutCallCountsTheUsageItsProviderReportedOnce(String how, Cut cut) {
    var result = cut.after(this, USAGE, TEXT);

    assertEquals(REPORTED, result.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_200), result.cost());
    assertEquals(List.of(result), runEndedResults());
    assertEquals(List.of(REPORTED), modelCallUsages());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cuts")
  void aCallCutBeforeItsProviderReportedUsageCountsZero(String how, Cut cut) {
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
            .withTextTurn("done", answered);
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(new StreamRetryPolicy(3, Backoff.fixed(Duration.ZERO), 0.0))
            .build();

    var result = run(model, limits);

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(REPORTED.plus(REPORTED).plus(answered), result.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_200 + 1_200 + 330), result.cost());
    assertEquals(List.of(REPORTED, REPORTED, answered), modelCallUsages());
  }

  @Test
  void aCutCallWhoseUsageExceedsTheBudgetEndsTheRunOverBudget() {
    var model =
        scripted()
            .withStreamTurn(
                ModelStreams.failing(new IllegalStateException("connection reset"), USAGE, TEXT));

    var result = run(model, SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000).build());

    var overBudget = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, result);
    assertEquals(1_200, overBudget.microUsdSpent());
    assertEquals(REPORTED, overBudget.usage());
  }

  private ResultMessage failed(RuntimeException failure, ModelChunk... chunks) {
    return run(
        scripted().withStreamTurn(ModelStreams.failing(failure, chunks)), SessionLimits.defaults());
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
    var model = scripted().withStreamTurn(ModelStreams.stalled(stalled::countDown, chunks));
    try (var session = open(model, SessionLimits.defaults())) {
      var result = session.send("go");
      Await.latch("the model stream to stall after its chunks", stalled);
      cut.accept(session);
      return Await.value("the cut run", result);
    }
  }

  private ResultMessage run(ScriptedModel.Builder model, SessionLimits limits) {
    try (var session = open(model, limits)) {
      return Await.value("the run", session.send("go"));
    }
  }

  private AgentSession open(ScriptedModel.Builder model, SessionLimits limits) {
    var session =
        AgentSession.open(
            SessionOptions.newBuilder()
                .withModel(model.build())
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
