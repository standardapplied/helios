/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.StreamRetryPolicy;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Every attempt of a model call is accounted once with the usage its provider reported, retried or
 * final, on every way {@link ModelCall#stream} returns: retries that run out, a retried attempt
 * followed by a success, and a run cancelled before its back-off or while a retried attempt is
 * accounted.
 */
final class AttemptAccountingTest {

  private static final Usage REPORTED = Usage.of(40, 4);
  private static final Usage ANSWERED = Usage.of(300, 30);
  private static final CostCalculator ONE_MICRO_USD_A_TOKEN =
      (modelId, usage) -> CostEstimate.ofMicroUsd(usage.totalTokens());

  private final LoopFixture fixture = new LoopFixture("sess-attempts", Instant.EPOCH);
  private final SessionState state = fixture.state();
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();

  @Test
  void eachRetriedAttemptAndTheSuccessAfterThemAreAccountedOnce() {
    var model = failingTwice().withTextTurn("done", ANSWERED);

    var turn = stream(model, 3, event -> false);

    assertEquals(3, turn.streamAttempts());
    assertEquals(List.of(REPORTED, REPORTED, ANSWERED), accounted());
    assertEquals(REPORTED.plus(REPORTED).plus(ANSWERED), state.totals().usage());
    assertEquals(CostEstimate.ofMicroUsd(44 + 44 + 330), state.totals().cost());
  }

  @Test
  void attemptsThatRunOutOfRetriesAreEachAccountedOnce() {
    var turn = stream(failingTwice(), 2, event -> false);

    assertEquals(2, turn.streamAttempts());
    assertEquals(List.of(REPORTED, REPORTED), accounted());
    assertEquals(REPORTED.plus(REPORTED), state.totals().usage());
  }

  @Test
  void anAttemptWhoseBackOffTheRunsCancellationCutsIsAccountedOnce() {
    var turn = stream(failingTwice(), 2, QueryEvent.TurnRetried.class::isInstance);

    assertEquals(1, turn.streamAttempts());
    assertEquals(List.of(REPORTED), accounted());
    assertEquals(REPORTED, state.totals().usage());
  }

  @Test
  void aRetriedAttemptTheRunIsCancelledWhileAccountingIsAccountedOnce() {
    var turn = stream(failingTwice(), 2, QueryEvent.ModelCallEnded.class::isInstance);

    assertEquals(1, turn.streamAttempts());
    assertEquals(List.of(REPORTED), accounted());
    assertEquals(REPORTED, state.totals().usage());
  }

  /** A model whose first two attempts report {@link #REPORTED} and fail transiently. */
  private static ScriptedModel.Builder failingTwice() {
    var overloaded = new TransientStreamException("overloaded", null, "scripted");
    return ScriptedModel.newBuilder()
        .withStreamTurn(ModelStreams.failing(overloaded, new ModelChunk.UsageDelta(REPORTED)))
        .withStreamTurn(ModelStreams.failing(overloaded, new ModelChunk.UsageDelta(REPORTED)));
  }

  /**
   * One turn's model call over {@code model} under {@code attempts} attempts with no back-off,
   * cancelling the run at the first event {@code cancelAt} matches.
   */
  private TurnOutcome stream(
      ScriptedModel.Builder model, int attempts, Predicate<QueryEvent> cancelAt) {
    var built = model.build();
    var collaborators =
        fixture.collaborators(
            HookRegistry.empty(),
            fixture.dispatch(ToolRegistry.empty()),
            new SteeringQueue(1),
            event -> {
              events.add(event);
              if (cancelAt.test(event)) {
                state.cancellation().cancel("cancelled at " + event.getClass().getSimpleName());
              }
            });
    var emitter = collaborators.emitter();
    var call =
        new ModelCall(
            collaborators,
            emitter,
            new UsageAccounting(collaborators, emitter, built, ONE_MICRO_USD_A_TOKEN),
            built,
            null);
    state.history().append(Message.user("go"));
    state.beginTurn();
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(
                new StreamRetryPolicy(attempts, Backoff.fixed(Duration.ZERO), 0.0))
            .build();
    return call.stream(state, limits, List.of());
  }

  /** The usage of each attempt's {@link QueryEvent.ModelCallEnded}, in order. */
  private List<Usage> accounted() {
    return events.stream()
        .filter(QueryEvent.ModelCallEnded.class::isInstance)
        .map(QueryEvent.ModelCallEnded.class::cast)
        .map(QueryEvent.ModelCallEnded::usage)
        .toList();
  }
}
