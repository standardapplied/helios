/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.StreamRetryPolicy;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.testing.ModelStreams;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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

  private final SessionState state = new SessionState("sess-call", new CancellationToken(), CLOCK);
  private final ToolDispatch dispatch =
      new ToolDispatch(
          SessionContext.forTesting("sess-call"), ToolRegistry.empty(), state.ending());
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private final SteeringQueue queue = new SteeringQueue(1);

  private final CountDownLatch released = new CountDownLatch(1);

  @AfterEach
  void release() {
    released.countDown();
  }

  private void untilReleased() {
    try {
      released.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private ModelCall modelCall(StreamModel model, Consumer<QueryEvent> sink) {
    var collaborators =
        new LoopCollaborators(
            HookRegistry.empty(),
            dispatch,
            Authorizations.ADMIT_ALL,
            Authorizations.NO_RULE,
            queue,
            sink,
            s ->
                new DefaultHookContext(
                    s.sessionId(), s.runId(), s.currentTurnIndex(), s.cancellation(), model),
            CLOCK);
    state.history().append(Message.user("hello"));
    state.beginTurn();
    return new ModelCall(
        collaborators, collaborators.emitter(), model, OutputSchema.of(Answer.class));
  }

  private ModelCall modelCall(StreamModel model) {
    return modelCall(model, events::add);
  }

  @Test
  void aParseFailureWithNothingToEchoQueuesOnlyTheCorrection() {
    var parseFailure =
        new StructuredOutputParseException(List.of("answer is required but missing"), null);
    var call = modelCall(failing(cancellation -> parseFailure));

    var attempts = call.stream(state, SessionLimits.defaults(), List.of());

    assertTrue(call.selfCorrectSchema(state, attempts));
    assertEquals(1, state.history().snapshot().size());
    assertEquals(parseFailure.correctionMessage(), queue.drain().getFirst().text());
  }

  @Test
  void aTransientFailureAfterCancellationIsNotRetried() {
    var model =
        failing(
            cancellation -> {
              cancellation.cancel("stopped mid-call");
              return transientFailure();
            });

    var attempts = modelCall(model).stream(state, SLOW_RETRY, List.of());

    assertEquals(1, attempts.streamAttempts());
    assertEquals(1, model.calls.get());
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.TurnRetried));
  }

  @Test
  void cancellationBeforeTheBackOffSkipsIt() {
    var model = failing(cancellation -> transientFailure());
    Consumer<QueryEvent> cancelOnRetry =
        event -> {
          if (event instanceof QueryEvent.TurnRetried) {
            state.cancellation().cancel("cancelled before the back-off");
          }
        };
    var call = modelCall(model, cancelOnRetry);

    var attempts = Await.value("the call", run(() -> call.stream(state, SLOW_RETRY, List.of())));

    assertEquals(1, attempts.streamAttempts());
    assertEquals(1, model.calls.get());
  }

  @Test
  void cancellationDuringTheBackOffEndsIt() {
    var model = failing(cancellation -> transientFailure());

    var attempts = cancelledDuringTheBackOff(model);

    assertEquals(1, attempts.streamAttempts());
    assertEquals(1, model.calls.get());
  }

  @Test
  void theBackOffIsTheRetryAfterTheFailureCarries() {
    var model = failing(cancellation -> transientFailure(Duration.ofSeconds(2)));
    var call =
        modelCall(
            model,
            event -> {
              events.add(event);
              if (event instanceof QueryEvent.TurnRetried) {
                state.cancellation().cancel("retry delay observed");
              }
            });

    var attempts = Await.value("the call", run(() -> call.stream(state, SLOW_RETRY, List.of())));

    assertEquals(Duration.ofSeconds(2), firstRetry().backoff());
    assertEquals(1, attempts.streamAttempts());
    assertEquals(1, model.calls.get());
  }

  @Test
  void aZeroRetryAfterRetriesAtOnceWhateverThePolicysBackOff() {
    var model = failing(cancellation -> transientFailure(Duration.ZERO));
    var call = modelCall(model);

    var attempts = Await.value("the call", run(() -> call.stream(state, SLOW_RETRY, List.of())));

    assertEquals(Duration.ZERO, firstRetry().backoff());
    assertEquals(3, attempts.streamAttempts());
    assertEquals(3, model.calls.get());
  }

  @Test
  void aRetryAfterBeyondThePolicysMaximumDelayIsCappedThere() {
    var model = failing(cancellation -> transientFailure(Duration.ofHours(1)));
    var call = modelCall(model);

    var attempts =
        Await.value(
            "the call",
            run(() -> call.stream(state, retrying(2, Duration.ofMillis(1)), List.of())));

    assertEquals(Duration.ofMillis(1), firstRetry().backoff());
    assertEquals(2, attempts.streamAttempts());
    assertEquals(2, model.calls.get());
  }

  @Test
  void anEarlierCancellationCallbackThatBlocksCannotKeepTheBackOffAsleep() {
    var model = failing(cancellation -> transientFailure());
    state.cancellation().onCancel(this::untilReleased);
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
        () -> state.cancellation().activeCallbackCountForTests() == 2);
    state.cancellation().cancelAsync("cancelled during the back-off", error -> {});

    assertEquals(1, Await.value("the call", streaming).streamAttempts());
  }

  @Test
  void interruptionDuringTheBackOffEndsItAndKeepsTheInterruptFlag() {
    var model = failing(cancellation -> transientFailure());
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
  void aHostErrorRecordedBeforeARetryEndsTheAttemptsWithoutAnotherModelCall() {
    var model = failing(cancellation -> transientFailure());
    Consumer<QueryEvent> failingOnRetry =
        event -> {
          if (event instanceof QueryEvent.TurnRetried) {
            state.ending().fail(new AssertionError("late host failure"));
          }
        };
    var call = modelCall(model, failingOnRetry);

    var attempts =
        Await.value(
            "the call", run(() -> call.stream(state, retrying(2, Duration.ofHours(1)), List.of())));

    assertEquals(1, attempts.streamAttempts());
    assertEquals(1, model.calls.get());
  }

  @Test
  void aBackOffThatElapsesIsFollowedByTheNextAttempt() {
    var model = failing(cancellation -> transientFailure());
    var call = modelCall(model);

    var attempts =
        Await.value(
            "the call",
            run(() -> call.stream(state, retrying(2, Duration.ofMillis(50)), List.of())));

    assertEquals(Duration.ofMillis(50), firstRetry().backoff());
    assertEquals(2, attempts.streamAttempts());
    assertEquals(2, model.calls.get());
    assertEquals(0, state.cancellation().activeCallbackCountForTests());
  }

  @Test
  void theTurnIsReadOnAThreadOfItsOwnAndTheSessionsCancellationClosesItsStream() {
    var pulled = new CountDownLatch(1);
    var readerName = new AtomicReference<String>();
    var model =
        new StreamModel(
            cancellation ->
                ModelStreams.stalled(
                    () -> {
                      readerName.set(Thread.currentThread().getName());
                      pulled.countDown();
                    }));
    var call = modelCall(model);

    TurnOutcome turn;
    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      var streaming = run(() -> call.stream(state, SessionLimits.defaults(), List.of()));
      Await.latch("the first read", pulled);
      state.cancellation().cancel("closed");
      turn = Await.value("the call", streaming);
    }

    var cancelled = assertInstanceOf(CancellationException.class, turn.streamError());
    assertEquals("session cancelled while waiting for model stream", cancelled.getMessage());
    assertEquals(FinishReason.ERROR, turn.finishReason());
    assertEquals("helios-model-stream-sess-call", readerName.get());
    assertEquals(List.of(), logged);
    assertEquals(1, model.calls.get());
  }

  @Test
  void aReaderThatIgnoresTheCloseIsReportedOnceItsGracePassesAndTheTurnStillEnds() {
    var pulled = new CountDownLatch(1);
    var model = new StreamModel(cancellation -> blocking(pulled, () -> {}));
    var call = modelCall(model);
    var limits = SessionLimits.newBuilder().withStopGrace(Duration.ofMillis(50)).build();

    TurnOutcome turn;
    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      var streaming = run(() -> call.stream(state, limits, List.of()));
      Await.latch("the first read", pulled);
      state.cancellation().cancel("closed");
      turn = Await.value("the call", streaming);
    }

    assertInstanceOf(CancellationException.class, turn.streamError());
    assertEquals(1, logged.size());
    assertEquals(Level.WARNING, logged.getFirst().getLevel());
    assertEquals(
        "the model stream of session sess-call did not end within PT0.05S of its close; it is not"
            + " known to be closed",
        logged.getFirst().getMessage());
  }

  @Test
  void aSessionCancelledWhileItsStreamIsBuiltClosesTheStreamOffTheLoopThread() {
    var closing = new CountDownLatch(1);
    var closer = new AtomicReference<String>();
    var model =
        new StreamModel(
            cancellation -> {
              cancellation.cancel("closed");
              return blocking(
                  new CountDownLatch(1),
                  () -> {
                    closer.compareAndSet(null, Thread.currentThread().getName());
                    closing.countDown();
                    Uninterruptible.await(released);
                  });
            });
    var call = modelCall(model);
    var limits = SessionLimits.newBuilder().withStopGrace(Duration.ofMillis(50)).build();

    TurnOutcome turn;
    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      turn = Await.value("the call", run(() -> call.stream(state, limits, List.of())));
    }

    var cancelled = assertInstanceOf(CancellationException.class, turn.streamError());
    assertEquals("session cancelled while waiting for model stream", cancelled.getMessage());
    Await.latch("the close", closing);
    assertEquals("helios-model-stream-close-sess-call", closer.get());
    assertEquals(1, logged.size());
    assertEquals(Level.WARNING, logged.getFirst().getLevel());
  }

  @Test
  void theReaderWaitsForTheCloseTheCancellationStartedSoTheErrorItThrowsIsRecordedFirst() {
    var pulled = new CountDownLatch(1);
    var readable = new CountDownLatch(1);
    var reader = new AtomicReference<Thread>();
    var closes = new AtomicInteger();
    var readerStateAtTheThrow = new AtomicReference<Thread.State>();
    var fatal = new AssertionError("close");
    var stream =
        new CloseableIterator<ModelChunk>() {
          @Override
          public boolean hasNext() {
            reader.set(Thread.currentThread());
            pulled.countDown();
            Uninterruptible.await(readable);
            return false;
          }

          @Override
          public ModelChunk next() {
            throw new NoSuchElementException();
          }

          @Override
          public void close() {
            closes.incrementAndGet();
            readable.countDown();
            Await.until(
                "the reader waits for this close or has ended",
                () -> readerHasStopped(reader.get().getState()));
            readerStateAtTheThrow.set(reader.get().getState());
            throw fatal;
          }
        };
    var call = modelCall(new StreamModel(cancellation -> stream));

    var streaming = run(() -> call.stream(state, SessionLimits.defaults(), List.of()));
    Await.latch("the first read", pulled);
    state.cancellation().cancel("closed");
    var turn = Await.value("the call", streaming);

    assertInstanceOf(CancellationException.class, turn.streamError());
    assertEquals(Thread.State.BLOCKED, readerStateAtTheThrow.get());
    assertSame(fatal, state.ending().seal().orElseThrow());
    assertEquals(1, closes.get());
  }

  private static boolean readerHasStopped(Thread.State state) {
    return state == Thread.State.BLOCKED || state == Thread.State.TERMINATED;
  }

  @Test
  void anInterruptWhileTheReaderOfAFailedAttemptClosesStartsNoFurtherAttemptAndStaysSet() {
    var failure = transientFailure(Duration.ZERO);
    var model =
        new StreamModel(
            cancellation -> {
              var caller = Thread.currentThread();
              return new CloseableIterator<>() {
                @Override
                public boolean hasNext() {
                  throw failure;
                }

                @Override
                public ModelChunk next() {
                  throw new NoSuchElementException();
                }

                @Override
                public void close() {
                  caller.interrupt();
                }
              };
            });
    var call = modelCall(model);
    var interruptKept = new AtomicBoolean();

    var turn =
        Await.value(
            "the call",
            run(
                () -> {
                  try {
                    return call.stream(state, SLOW_RETRY, List.of());
                  } finally {
                    interruptKept.set(Thread.interrupted());
                  }
                }));

    assertSame(failure, turn.streamError());
    assertEquals(1, turn.streamAttempts());
    assertEquals(1, model.calls.get());
    assertTrue(interruptKept.get());
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.TurnRetried));
  }

  @Test
  void anInterruptOfTheWaitingThreadClosesTheStreamAndEndsTheTurnKeepingTheInterrupt() {
    var pulled = new CountDownLatch(1);

    var turn =
        interruptedOnceRead(
            new StreamModel(cancellation -> ModelStreams.stalled(pulled::countDown)), pulled);

    assertInstanceOf(InterruptedException.class, turn.streamError());
    assertEquals(List.of(), logged);
  }

  @Test
  void anInterruptWhileWaitingForTheReaderEndsTheWaitAndReportsTheReader() {
    var pulled = new CountDownLatch(1);

    var turn =
        interruptedOnceRead(
            new StreamModel(
                cancellation -> {
                  var caller = Thread.currentThread();
                  return blocking(pulled, caller::interrupt);
                }),
            pulled);

    assertInstanceOf(InterruptedException.class, turn.streamError());
    assertEquals(1, logged.size());
    assertEquals(Level.WARNING, logged.getFirst().getLevel());
  }

  @Test
  void anInterruptOfTheWaitingThreadClosesTheStreamOffTheLoopThread() {
    var pulled = new CountDownLatch(1);
    var closing = new CountDownLatch(1);
    var closer = new AtomicReference<String>();
    var call =
        modelCall(
            new StreamModel(
                cancellation ->
                    blocking(
                        pulled,
                        () -> {
                          closer.compareAndSet(null, Thread.currentThread().getName());
                          closing.countDown();
                          Uninterruptible.await(released);
                        })));
    var limits = SessionLimits.newBuilder().withStopGrace(Duration.ofMillis(50)).build();
    var streaming = new FutureTask<>(() -> call.stream(state, limits, List.of()));

    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      var caller = Thread.ofVirtual().start(streaming);
      Await.latch("the first read", pulled);

      caller.interrupt();

      Await.latch("the close", closing);
      assertEquals("helios-model-stream-close-sess-call", closer.get());
      assertInstanceOf(
          InterruptedException.class, Await.value("the call", streaming).streamError());
    }
    assertEquals(1, logged.size());
    assertEquals(Level.WARNING, logged.getFirst().getLevel());
  }

  /**
   * The turn of a call over {@code model} whose waiting thread is interrupted once {@code pulled}
   * shows the stream is being read, asserting the interrupt stays set.
   */
  private TurnOutcome interruptedOnceRead(StreamModel model, CountDownLatch pulled) {
    var call = modelCall(model);
    var interruptKept = new AtomicBoolean();
    var streaming =
        new FutureTask<>(
            () -> {
              try {
                return call.stream(state, SessionLimits.defaults(), List.of());
              } finally {
                interruptKept.set(Thread.currentThread().isInterrupted());
              }
            });
    TurnOutcome turn;
    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      var caller = Thread.ofVirtual().start(streaming);
      Await.latch("the first read", pulled);
      caller.interrupt();
      turn = Await.value("the call", streaming);
    }
    assertTrue(interruptKept.get());
    return turn;
  }

  @Test
  void whatOpeningTheStreamThrowsEndsTheTurnAndAnErrorIsThrownAsItself() {
    var refused = new IllegalStateException("refused");
    var fatal = new AssertionError("host");

    var turn =
        modelCall(
            new StreamModel(
                cancellation -> {
                  throw refused;
                }))
            .stream(state, SessionLimits.defaults(), List.of());
    var thrown =
        assertThrows(
            AssertionError.class,
            () ->
                modelCall(
                    new StreamModel(
                        cancellation -> {
                          throw fatal;
                        }))
                    .stream(state, SessionLimits.defaults(), List.of()));

    assertSame(refused, turn.streamError());
    assertSame(fatal, thrown);
    assertSame(fatal, state.ending().seal().orElseThrow());
  }

  /**
   * A stream whose first read counts {@code pulled} down and blocks until the test ends, through
   * interrupts and its close, which runs {@code onClose}.
   */
  private CloseableIterator<ModelChunk> blocking(CountDownLatch pulled, Runnable onClose) {
    return new CloseableIterator<>() {
      @Override
      public boolean hasNext() {
        pulled.countDown();
        Uninterruptible.await(released);
        return false;
      }

      @Override
      public ModelChunk next() {
        throw new NoSuchElementException();
      }

      @Override
      public void close() {
        onClose.run();
      }
    };
  }

  /** The attempts of a call under {@link #SLOW_RETRY} cancelled once its first back-off waits. */
  private TurnOutcome cancelledDuringTheBackOff(StreamModel model) {
    var retried = new CountDownLatch(1);
    var call =
        modelCall(
            model,
            event -> {
              events.add(event);
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
    return Await.value("the call", streaming);
  }

  private QueryEvent.TurnRetried firstRetry() {
    return events.stream()
        .filter(QueryEvent.TurnRetried.class::isInstance)
        .map(QueryEvent.TurnRetried.class::cast)
        .findFirst()
        .orElseThrow();
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

  private static TransientStreamException transientFailure(Duration retryAfter) {
    return new TransientStreamException(
        "API error (status 429): slow down", null, "test", Optional.of(retryAfter));
  }

  private StreamModel failing(Function<CancellationToken, RuntimeException> failure) {
    return new StreamModel(cancellation -> ModelStreams.failing(failure.apply(cancellation)));
  }

  /** A model whose every structured stream is the one its script returns for the call. */
  private final class StreamModel extends MockModel {

    final AtomicInteger calls = new AtomicInteger();
    private final Function<CancellationToken, CloseableIterator<ModelChunk>> stream;

    StreamModel(Function<CancellationToken, CloseableIterator<ModelChunk>> stream) {
      super("unused");
      this.stream = stream;
    }

    @Override
    public CloseableIterator<ModelChunk> chatStream(
        List<Message> messages, List<Tool> tools, OutputSchema<?> outputSchema) {
      calls.incrementAndGet();
      return stream.apply(state.cancellation());
    }
  }
}
