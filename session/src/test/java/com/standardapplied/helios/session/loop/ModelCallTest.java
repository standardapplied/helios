/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.StreamRetryPolicy;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The model call at its edges: a parse failure with nothing to echo, a transient failure once the
 * session is cancelled, and the retry back-off ended by cancellation or interruption, or elapsing.
 */
final class ModelCallTest {

  private static final InstantSource CLOCK =
      InstantSource.fixed(Instant.parse("2026-05-14T19:00:00Z"));
  private static final SessionLimits SLOW_RETRY = retrying(3, Duration.ofHours(1));

  record Answer(String answer) {}

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final SteeringQueue queue = new SteeringQueue(1);
  private final SessionState state = new SessionState("sess-call", new CancellationToken(), CLOCK);

  @AfterEach
  void shutDownScheduler() {
    scheduler.shutdownNow();
  }

  private ModelCall modelCall(FailingModel model, Consumer<QueryEvent> sink) {
    var dispatch =
        new ToolDispatch(
            SessionContext.forTesting("sess-call"),
            ToolRegistry.empty(),
            ConcurrencyLimits.defaults());
    var collaborators =
        new LoopCollaborators(
            HookRegistry.empty(),
            dispatch,
            queue,
            sink,
            s ->
                new DefaultHookContext(
                    s.sessionId(), s.currentTurnIndex(), s.cancellation(), model),
            CLOCK);
    state.history().append(Message.user("hello"));
    state.beginTurn();
    return new ModelCall(
        collaborators, collaborators.emitter(), model, OutputSchema.of(Answer.class), scheduler);
  }

  private ModelCall modelCall(FailingModel model) {
    return modelCall(model, events::add);
  }

  @Test
  void aParseFailureWithNothingToEchoQueuesOnlyTheCorrection() {
    var parseFailure =
        new StructuredOutputParseException(List.of("answer is required but missing"), null);
    var call = modelCall(new FailingModel(cancellation -> parseFailure));

    var attempts = call.stream(state, SessionLimits.defaults(), List.of());

    assertTrue(call.selfCorrectSchema(state, attempts.turn()));
    assertEquals(1, state.history().snapshot().size());
    assertEquals(parseFailure.correctionMessage(), queue.drain().getFirst().text());
  }

  @Test
  void aTransientFailureAfterCancellationIsNotRetried() {
    var model =
        new FailingModel(
            cancellation -> {
              cancellation.cancel("stopped mid-call");
              return transientFailure();
            });

    var attempts = modelCall(model).stream(state, SLOW_RETRY, List.of());

    assertEquals(1, attempts.count());
    assertEquals(1, model.calls.get());
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.TurnRetried));
  }

  @Test
  void cancellationBeforeTheBackOffSkipsIt() {
    var model = new FailingModel(cancellation -> transientFailure());
    Consumer<QueryEvent> cancelOnRetry =
        event -> {
          if (event instanceof QueryEvent.TurnRetried) {
            state.cancellation().cancel("cancelled before the back-off");
          }
        };
    var call = modelCall(model, cancelOnRetry);

    var attempts = Await.value("the call", run(() -> call.stream(state, SLOW_RETRY, List.of())));

    assertEquals(1, attempts.count());
    assertEquals(1, model.calls.get());
  }

  @Test
  void cancellationDuringTheBackOffEndsIt() {
    var model = new FailingModel(cancellation -> transientFailure());
    var retried = new CountDownLatch(1);
    var call =
        modelCall(
            model,
            event -> {
              if (event instanceof QueryEvent.TurnRetried) {
                retried.countDown();
              }
            });
    var streaming = run(() -> call.stream(state, SLOW_RETRY, List.of()));

    Await.latch("the first attempt's retry", retried);
    Await.until(
        "the back-off waits on the cancellation token",
        () -> state.cancellation().activeCallbackCountForTests() == 1);
    state.cancellation().cancel("cancelled during the back-off");
    var attempts = Await.value("the call", streaming);

    assertEquals(1, attempts.count());
    assertEquals(1, model.calls.get());
  }

  @Test
  void interruptionDuringTheBackOffEndsItAndKeepsTheInterruptFlag() {
    var model = new FailingModel(cancellation -> transientFailure());
    Consumer<QueryEvent> interruptOnRetry =
        event -> {
          if (event instanceof QueryEvent.TurnRetried) {
            Thread.currentThread().interrupt();
          }
        };
    var call = modelCall(model, interruptOnRetry);

    var interrupted =
        Await.value(
            "the call",
            run(
                () -> {
                  call.stream(state, SLOW_RETRY, List.of());
                  return Thread.interrupted();
                }));

    assertTrue(interrupted);
    assertEquals(1, model.calls.get());
  }

  @Test
  void aBackOffThatElapsesIsFollowedByTheNextAttempt() {
    var model = new FailingModel(cancellation -> transientFailure());
    var call = modelCall(model);

    var attempts =
        Await.value(
            "the call",
            run(() -> call.stream(state, retrying(2, Duration.ofMillis(1)), List.of())));

    assertEquals(2, attempts.count());
    assertEquals(2, model.calls.get());
  }

  private static SessionLimits retrying(int attempts, Duration backoff) {
    return SessionLimits.newBuilder()
        .withStreamRetryPolicy(new StreamRetryPolicy(attempts, Backoff.fixed(backoff), 0.0))
        .build();
  }

  private static <T> FutureTask<T> run(Callable<T> body) {
    var task = new FutureTask<>(body);
    Thread.ofVirtual().start(task);
    return task;
  }

  private static TransientStreamException transientFailure() {
    return new TransientStreamException("Stream read error", new IOException("reset"), "test");
  }

  /** A model whose every stream fails with the throwable its script returns for the call. */
  private static final class FailingModel extends MockModel {

    final AtomicInteger calls = new AtomicInteger();
    private final Function<CancellationToken, RuntimeException> failure;

    FailingModel(Function<CancellationToken, RuntimeException> failure) {
      super("unused");
      this.failure = failure;
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages,
        List<Tool> tools,
        OutputSchema<?> outputSchema,
        CancellationToken cancellation) {
      calls.incrementAndGet();
      var error = failure.apply(cancellation);
      return subscriber -> {
        subscriber.onSubscribe(
            new Flow.Subscription() {
              @Override
              public void request(long n) {}

              @Override
              public void cancel() {}
            });
        subscriber.onError(error);
      };
    }
  }
}
