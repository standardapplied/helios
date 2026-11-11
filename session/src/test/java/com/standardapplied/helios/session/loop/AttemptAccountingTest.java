/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.StreamRetryPolicy;
import com.standardapplied.helios.session.test.HandClock;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Every attempt of a model call is accounted once, as its stream ends, with the usage its stream
 * reported, after the model its provider reported serving it and the time from its start to its
 * first chunk and first text are recorded, on every way {@link ModelCall#stream} returns: retries
 * that run out, a transient failure on a run already stopped, a run cancelled while an attempt is
 * accounted or before its back-off, a run interrupted while a retry is announced under a zero
 * back-off, and a run whose spend, its child runs' included, exceeds its budget once an attempt is
 * accounted or after its back-off.
 */
final class AttemptAccountingTest {

  private static final Usage REPORTED = Usage.of(40, 4);
  private static final Duration NOT_REACHED = Duration.ofMinutes(10);
  private static final TransientStreamException OVERLOADED =
      new TransientStreamException("overloaded", null, "scripted");

  private final LoopFixture fixture = new LoopFixture("sess-attempts", Instant.EPOCH);
  private final SessionState state = fixture.state();
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final List<String> steps = new CopyOnWriteArrayList<>();
  private final HandClock clock = new HandClock(Instant.EPOCH);

  @Test
  void attemptsThatRunOutOfRetriesAreEachAccountedOnceInOrder() {
    var turn = stream(failingTwice(), event -> {});

    assertEquals(2, turn.streamAttempts());
    assertEquals(List.of(REPORTED, REPORTED), accounted());
    assertEquals(List.of(1, 2), attempts());
    assertEquals(REPORTED.plus(REPORTED), state.totals().usage());
    assertEquals(CostEstimate.ofMicroUsd(44 + 44), state.totals().cost());
  }

  @Test
  void aRetriedAttemptIsAccountedBeforeItsRetryIsAnnounced() {
    stream(failingTwice(), event -> {});

    assertEquals(
        List.of(
            QueryEvent.ModelCallEnded.class,
            QueryEvent.TurnRetried.class,
            QueryEvent.ModelCallEnded.class),
        eventTypes());
  }

  /** The retried attempt starts as the first ends, 120 ms in, and is timed from there. */
  @Test
  void anAttemptsServedModelAndFirstOutputAreRecordedBeforeItIsAccounted() {
    var served =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                clock.paced(
                    ModelStreams.failing(OVERLOADED, new ModelChunk.UsageDelta(REPORTED)), 120))
            .withStreamTurn(
                clock.paced(
                    ModelStreams.of(
                        new ModelChunk.KeepAlive(),
                        new ModelChunk.TextDelta("Hi"),
                        new ModelChunk.MessageStop(
                            "STOP",
                            REPORTED,
                            Map.of(Response.RESPONSE_MODEL_KEY, "claude-opus-5-5-20261101"))),
                    40,
                    60));

    stream(served, event -> steps.add(event.getClass().getSimpleName()));

    assertEquals(
        List.of(
            "served null",
            "first chunk OptionalLong[120], first text OptionalLong.empty",
            "ModelCallEnded",
            "TurnRetried",
            "AssistantText",
            "served claude-opus-5-5-20261101",
            "first chunk OptionalLong[40], first text OptionalLong[100]",
            "ModelCallEnded"),
        steps);
  }

  @Test
  void anAttemptWhoseStreamDoesNotOpenHasNoFirstOutput() {
    var turn = stream(ScriptedModel.newBuilder(), event -> {});

    assertInstanceOf(IllegalStateException.class, turn.streamError());
    assertEquals(
        List.of("served null", "first chunk OptionalLong.empty, first text OptionalLong.empty"),
        steps);
  }

  @Test
  void aTransientFailureOnARunStoppedAsItsStreamClosedIsAccountedOnceAndNotRetried() {
    var stopping =
        new CloseableIterator<ModelChunk>() {
          private final CloseableIterator<ModelChunk> failing =
              ModelStreams.failing(OVERLOADED, new ModelChunk.UsageDelta(REPORTED));

          @Override
          public boolean hasNext() {
            return failing.hasNext();
          }

          @Override
          public ModelChunk next() {
            return failing.next();
          }

          @Override
          public void close() {
            state.cancellation().cancel("stopped as the stream closed");
          }
        };
    var model = ScriptedModel.newBuilder().withStreamTurn(stopping).withTextTurn("never");

    var turn = stream(model, event -> {});

    assertEquals(OVERLOADED, turn.streamError());
    assertEquals(1, turn.streamAttempts());
    assertEquals(List.of(REPORTED), accounted());
    assertEquals(REPORTED, state.totals().usage());
  }

  @Test
  void anAttemptTheRunIsCancelledWhileAccountingIsNotRetried() {
    var turn = stream(failingTwice(), cancelAt(QueryEvent.ModelCallEnded.class));

    assertEquals(1, turn.streamAttempts());
    assertEquals(List.of(REPORTED), accounted());
    assertEquals(REPORTED, state.totals().usage());
  }

  @Test
  void anAttemptWhoseBackOffTheRunsCancellationCutsIsAccountedOnce() {
    var turn = stream(failingTwice(), cancelAt(QueryEvent.TurnRetried.class));

    assertEquals(1, turn.streamAttempts());
    assertEquals(List.of(REPORTED), accounted());
    assertEquals(REPORTED, state.totals().usage());
  }

  /**
   * A zero back-off returns at once without seeing an interrupt, so the check after it is what
   * keeps an interrupted run from a second attempt.
   */
  @Test
  void aRunInterruptedWhileItsRetryIsAnnouncedUnderAZeroBackOffMakesNoSecondAttempt() {
    Consumer<QueryEvent> interruptOnRetry =
        event -> {
          if (event instanceof QueryEvent.TurnRetried) {
            Thread.currentThread().interrupt();
          }
        };

    var turn = stream(failingTwice(), interruptOnRetry);
    var interrupted = Thread.interrupted();

    assertTrue(interrupted);
    assertEquals(1, turn.streamAttempts());
    assertEquals(List.of(REPORTED), accounted());
    assertEquals(REPORTED, state.totals().usage());
  }

  @Test
  void anAttemptThatTakesTheRunOverItsBudgetIsNotRetried() {
    var model = failingThrice();

    var turn = stream(model, event -> {}, budgeted(50, Duration.ZERO));

    assertEquals(2, turn.streamAttempts());
    assertEquals(
        List.of(
            QueryEvent.ModelCallEnded.class,
            QueryEvent.TurnRetried.class,
            QueryEvent.ModelCallEnded.class),
        eventTypes());
    assertEquals(2, model.calls().size());
  }

  @Test
  void anAttemptThatReachesTheBudgetExactlyIsRetried() {
    var model = failingThrice();

    var turn = stream(model, event -> {}, budgeted(88, Duration.ZERO));

    assertEquals(3, turn.streamAttempts());
    assertEquals(3, model.calls().size());
  }

  /** Under a back-off of a day, returning at all proves no back-off ran. */
  @Test
  void anAttemptOverTheBudgetReturnsWithoutBackingOff() {
    var model = failingThrice();

    var turn = stream(model, event -> {}, budgeted(40, Duration.ofDays(1)));

    assertEquals(1, turn.streamAttempts());
    assertEquals(List.of(QueryEvent.ModelCallEnded.class), eventTypes());
    assertEquals(1, model.calls().size());
  }

  @Test
  void aChildRunsSpendAddedAsAnAttemptIsAccountedCountsAgainstItsRetry() {
    var model = failingThrice();

    var turn =
        stream(model, childSpendsAt(QueryEvent.ModelCallEnded.class), budgeted(50, Duration.ZERO));

    assertEquals(1, turn.streamAttempts());
    assertEquals(List.of(QueryEvent.ModelCallEnded.class), eventTypes());
    assertEquals(CostEstimate.ofMicroUsd(44 + 10), state.totals().cost());
  }

  @Test
  void aChildRunsSpendAddedWhileARetryIsAnnouncedStopsTheNextAttempt() {
    var model = failingThrice();

    var turn =
        stream(model, childSpendsAt(QueryEvent.TurnRetried.class), budgeted(50, Duration.ZERO));

    assertEquals(1, turn.streamAttempts());
    assertEquals(
        List.of(QueryEvent.ModelCallEnded.class, QueryEvent.TurnRetried.class), eventTypes());
    assertEquals(1, model.calls().size());
  }

  /** A model whose first two attempts report {@link #REPORTED} and fail transiently. */
  private static ScriptedModel.Builder failingTwice() {
    return ScriptedModel.newBuilder()
        .withStreamTurn(ModelStreams.failing(OVERLOADED, new ModelChunk.UsageDelta(REPORTED)))
        .withStreamTurn(ModelStreams.failing(OVERLOADED, new ModelChunk.UsageDelta(REPORTED)));
  }

  /** A model whose three attempts each report {@link #REPORTED} and fail transiently. */
  private static ScriptedModel failingThrice() {
    return failingTwice()
        .withStreamTurn(ModelStreams.failing(OVERLOADED, new ModelChunk.UsageDelta(REPORTED)))
        .build();
  }

  /** Three attempts backing off {@code backoff} each, under a budget of {@code microUsd}. */
  private static SessionLimits budgeted(long microUsd, Duration backoff) {
    return SessionLimits.newBuilder()
        .withStreamRetryPolicy(new StreamRetryPolicy(3, Backoff.fixed(backoff), 0.0))
        .withMaxBudgetMicroUsd(microUsd)
        .withStopGrace(NOT_REACHED)
        .build();
  }

  /** At each event of {@code type}, a child run of this run spends 10 µUSD. */
  private Consumer<QueryEvent> childSpendsAt(Class<? extends QueryEvent> type) {
    return event -> {
      if (type.isInstance(event)) {
        new SessionTotals(clock, Optional.of(state.totals()))
            .accumulateCost(CostEstimate.ofMicroUsd(10));
      }
    };
  }

  private Consumer<QueryEvent> cancelAt(Class<? extends QueryEvent> type) {
    return event -> {
      if (type.isInstance(event)) {
        state.cancellation().cancel("cancelled at " + type.getSimpleName());
      }
    };
  }

  /** One turn's model call over {@code model} under two attempts with no back-off. */
  private TurnOutcome stream(ScriptedModel.Builder model, Consumer<QueryEvent> onEvent) {
    return stream(
        model.build(),
        onEvent,
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(new StreamRetryPolicy(2, Backoff.fixed(Duration.ZERO), 0.0))
            .withStopGrace(NOT_REACHED)
            .build());
  }

  /**
   * One turn's model call over {@code built} under {@code limits}, each event recorded, then handed
   * to {@code onEvent}; the reader's close is always waited for.
   */
  private TurnOutcome stream(
      ScriptedModel built, Consumer<QueryEvent> onEvent, SessionLimits limits) {
    var collaborators =
        new LoopCollaborators(
            HookRegistry.empty(),
            fixture.dispatch(ToolRegistry.empty()),
            Authorizations.ADMIT_ALL,
            Authorizations.NO_RULE,
            new SteeringQueue(1),
            event -> {
              events.add(event);
              onEvent.accept(event);
            },
            fixture::hookContext,
            clock,
            NoSpans.recordingTo(steps::add));
    var emitter = collaborators.emitter();
    CostCalculator oneMicroUsdAToken =
        (modelId, usage) -> CostEstimate.ofMicroUsd(usage.totalTokens());
    var call =
        new ModelCall(
            collaborators,
            emitter,
            new UsageAccounting(collaborators, emitter, built, oneMicroUsdAToken),
            built,
            null);
    state.history().append(Message.user("go"));
    state.beginTurn();
    return call.stream(state, limits, List.of());
  }

  private List<Class<?>> eventTypes() {
    return events.stream().<Class<?>>map(Object::getClass).toList();
  }

  /** The usage of each attempt's {@link QueryEvent.ModelCallEnded}, in order. */
  private List<Usage> accounted() {
    return ended().stream().map(QueryEvent.ModelCallEnded::usage).toList();
  }

  /** The attempt number of each {@link QueryEvent.ModelCallEnded}, in order. */
  private List<Integer> attempts() {
    return ended().stream().map(QueryEvent.ModelCallEnded::attempt).toList();
  }

  private List<QueryEvent.ModelCallEnded> ended() {
    return events.stream()
        .filter(QueryEvent.ModelCallEnded.class::isInstance)
        .map(QueryEvent.ModelCallEnded.class::cast)
        .toList();
  }
}
