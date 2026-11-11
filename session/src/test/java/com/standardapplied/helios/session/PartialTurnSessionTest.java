/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A model call cut short after it streamed text keeps that text as the run's last assistant
 * message, tagged {@link MessageOrigin#PARTIAL_TURN}, whatever cut it: it is stored with the run's
 * end, and the next run, in the same session or a reopened one, sends it.
 */
final class PartialTurnSessionTest {

  private static final String SID = "chat-partial";
  private static final ModelChunk HALF = new ModelChunk.TextDelta("Half a sentence");
  private static final Message PARTIAL =
      Message.assistant("Half a sentence", List.of(), MessageOrigin.PARTIAL_TURN.metadata());

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-11T10:00:00Z"));
  private final CountDownLatch stalled = new CountDownLatch(1);
  private final RecordingStore store = new RecordingStore();

  static Stream<Arguments> endings() {
    return Stream.of(
        ending("interrupt", "Cancelled", PartialTurnSessionTest::interrupted),
        ending("close", "Cancelled", PartialTurnSessionTest::closed),
        ending("wall clock", "ErrorMaxWallClock", PartialTurnSessionTest::pastItsWallClock),
        ending("stream failure", "ErrorDuringExecution", PartialTurnSessionTest::failed),
        ending("retries exhausted", "ErrorTransientStream", PartialTurnSessionTest::retriedOut),
        ending("idle timeout", "ErrorDuringExecution", PartialTurnSessionTest::idle));
  }

  private static Arguments ending(
      String cut, String terminal, Function<PartialTurnSessionTest, ResultMessage> run) {
    return Arguments.of(cut, terminal, run);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endings")
  void theCutCallsTextIsStoredTaggedWithTheRunsEnd(
      String cut, String terminal, Function<PartialTurnSessionTest, ResultMessage> run) {
    var result = run.apply(this);

    assertEquals(terminal, result.getClass().getSimpleName());
    assertLastBatchKeptThePartial(result);
  }

  /**
   * A run cut during the back-off before a retry keeps the failed attempt's text: no later attempt
   * streamed, so it is the last thing the model said, though {@code TurnRetried} announced it
   * superseded.
   */
  @Test
  void aRunCutDuringARetryBackOffKeepsTheFailedAttemptsText() {
    var retried = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(
                    new TransientStreamException("Stream read error", null, "test"), HALF))
            .withTextTurn("never")
            .build();
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(
                new StreamRetryPolicy(2, Backoff.fixed(Duration.ofHours(1)), 0.0))
            .build();
    ResultMessage result;
    try (var session = AgentSession.open(options(model).withLimits(limits).build())) {
      new EventCollector(
              event -> {
                if (event instanceof QueryEvent.TurnRetried) {
                  retried.countDown();
                }
              })
          .read(session.events());
      var run = session.send("go");
      Await.latch("the first attempt's TurnRetried", retried);
      session.interrupt();
      result = Await.value("the run cut during its back-off", run);
    }

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals(1, model.calls().size());
    assertLastBatchKeptThePartial(result);
  }

  @Test
  void aReopenedSessionSendsThePartialBeforeTheNextMessage() {
    assertInstanceOf(ResultMessage.Cancelled.class, interrupted());
    var model = ScriptedModel.newBuilder().withTextTurn("carried on").build();

    try (var reopened = AgentSession.open(options(model).build())) {
      Await.value("the next run", reopened.send("go on"));
    }

    assertEquals(
        List.of(Message.user("go"), PARTIAL, Message.user("go on")), model.calls().getFirst());
  }

  @Test
  void theNextRunOfTheSameSessionSendsThePartial() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(stalledAfterText())
            .withTextTurn("carried on")
            .build();

    try (var session = AgentSession.open(options(model).build())) {
      var interrupted = session.send("go");
      Await.latch("the model stream to stall after its text", stalled);
      session.interrupt();
      assertInstanceOf(
          ResultMessage.Cancelled.class, Await.value("the interrupted run", interrupted));
      Await.value("the next run", session.send("go on"));
    }

    assertEquals(List.of(Message.user("go"), PARTIAL, Message.user("go on")), model.calls().get(1));
  }

  /** The store's last batch holds the run's first message, its partial and its end. */
  private void assertLastBatchKeptThePartial(ResultMessage result) {
    assertEquals(
        List.of(
            new SessionEntry.MessageAdded(result.runId(), now.get(), Message.user("go")),
            new SessionEntry.MessageAdded(result.runId(), now.get(), PARTIAL),
            SessionEntry.RunEnded.of(result.runId(), now.get(), result)),
        store.batches.getLast());
  }

  private ResultMessage interrupted() {
    return stalledThen(AgentSession::interrupt);
  }

  private ResultMessage closed() {
    return stalledThen(AgentSession::close);
  }

  private ResultMessage pastItsWallClock() {
    return stalledThen(
        session -> {
          now.set(now.get().plus(SessionLimits.defaults().maxWallClock()).plusSeconds(1));
          ((AgentSessionImpl) session).lifecycle.expireWallClock();
        });
  }

  private ResultMessage failed() {
    return sent(
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(new IllegalStateException("connection reset"), HALF)),
        SessionLimits.defaults());
  }

  /** Two attempts, each streaming text before a transient failure: the last attempt's is kept. */
  private ResultMessage retriedOut() {
    var transientFailure = new TransientStreamException("Stream read error", null, "test");
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(transientFailure, new ModelChunk.TextDelta("First attempt")))
            .withStreamTurn(ModelStreams.failing(transientFailure, HALF));
    var limits =
        SessionLimits.newBuilder()
            .withStreamRetryPolicy(new StreamRetryPolicy(2, Backoff.fixed(Duration.ZERO), 0.0))
            .build();
    return sent(model, limits);
  }

  /**
   * What a provider's stream throws when its idle timeout fires after text: the provider's
   * exception, status 0, from {@code hasNext()} ({@code SseReader}).
   */
  private ResultMessage idle() {
    var idleTimeout = new ProviderException("Stream idle timeout: no data received for 120000 ms");
    return sent(
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.failing(idleTimeout, HALF)),
        SessionLimits.defaults());
  }

  /** The result of a run whose model call streams text, then stalls until {@code action}. */
  private ResultMessage stalledThen(Consumer<AgentSession> action) {
    try (var session = open(ScriptedModel.newBuilder().withStreamTurn(stalledAfterText()))) {
      var result = session.send("go");
      Await.latch("the model stream to stall after its text", stalled);
      action.accept(session);
      return Await.value("the result", result);
    }
  }

  private ResultMessage sent(ScriptedModel.Builder model, SessionLimits limits) {
    try (var session = AgentSession.open(options(model.build()).withLimits(limits).build())) {
      return Await.value("the run", session.send("go"));
    }
  }

  private AgentSession open(ScriptedModel.Builder model) {
    return AgentSession.open(options(model.build()).build());
  }

  private SessionOptions.Builder options(ScriptedModel model) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withStore(store)
        .withClock(now::get);
  }

  private CloseableIterator<ModelChunk> stalledAfterText() {
    return ModelStreams.stalled(stalled::countDown, HALF);
  }
}
