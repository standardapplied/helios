/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.testing.ModelStreams;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * The turn's one terminal transition: whichever ending comes first — the stream's end, a failure it
 * or a chunk's emission throws, or an ending from another thread — snapshots what was read, makes
 * every later chunk a no-op that emits nothing, and leaves the stream closed.
 */
final class TurnReaderTest {

  private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
  private static final Response.Usage USAGE = Response.Usage.of(7, 3);
  private static final ModelChunk.MessageStop STOP =
      new ModelChunk.MessageStop("STOP", USAGE, Map.of());

  private final SessionState state = new SessionState("sess", new CancellationToken(), CLOCK);
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private final TurnReader reader = newReader(events::add);

  @Test
  void aKeepAliveAddsNothingToTheTurnAndEmitsNothing() {
    reader.read(
        ModelStreams.of(
            new ModelChunk.KeepAlive(),
            new ModelChunk.TextDelta("Hel"),
            new ModelChunk.KeepAlive(),
            STOP));

    assertEquals(
        new TurnOutcome(FinishReason.STOP, "Hel", List.of(), List.of(), USAGE, Map.of(), null, 1),
        reader.outcome());
    assertEquals(1, events.size());
    assertTrue(reader.released());
  }

  @Test
  void anEndedStreamIsSnapshottedWithEveryEventEmittedAndIsClosed() {
    var call = ToolCall.newBuilder().withId("c1").withName("lookup").build();
    var cite = Citation.of("https://example.com", "snippet");
    var stream =
        new Tracked(
            ModelStreams.of(
                new ModelChunk.ThinkingDelta("Hm."),
                new ModelChunk.TextDelta("Checking."),
                new ModelChunk.ToolUseStart("c1", "lookup"),
                new ModelChunk.ToolUseDelta("c1", "{}"),
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.UsageDelta(USAGE),
                new ModelChunk.MessageStop("TOOL_CALLS", USAGE, Map.of("k", "v"), List.of(cite))));

    reader.read(stream);
    var turn = reader.outcome();

    assertNull(turn.streamError());
    assertEquals("Checking.", turn.assistantContent());
    assertEquals(List.of(call), turn.toolCalls());
    assertEquals(FinishReason.TOOL_CALLS, turn.finishReason());
    assertEquals(USAGE, turn.usage());
    assertEquals(Map.of("k", "v"), turn.metadata());
    assertEquals(List.of(cite), turn.citations());
    assertEquals(
        List.of(
            new QueryEvent.AssistantThinking("sess", 0, Instant.EPOCH, "Hm."),
            new QueryEvent.AssistantText("sess", 0, Instant.EPOCH, "Checking."),
            new QueryEvent.AssistantCitations("sess", 0, Instant.EPOCH, List.of(cite))),
        events);
    assertTrue(stream.closed.get());
  }

  @Test
  void aStreamThatEndsWithoutAMessageStopEndsTheTurnWithAnIllegalState() {
    reader.read(ModelStreams.of(new ModelChunk.TextDelta("Hel")));
    var turn = reader.outcome();

    assertInstanceOf(IllegalStateException.class, turn.streamError());
    assertEquals(FinishReason.ERROR, turn.finishReason());
    assertEquals("Hel", turn.assistantContent());
  }

  @Test
  void theStreamsOwnFailureEndsTheTurnAsAnErrorKeepingWhatWasRead() {
    var refused = new IllegalStateException("refused");
    var stream = new Tracked(ModelStreams.failing(refused, STOP));

    reader.read(stream);
    var turn = reader.outcome();

    assertSame(refused, turn.streamError());
    assertEquals(FinishReason.ERROR, turn.finishReason());
    assertEquals(USAGE, turn.usage());
    assertTrue(stream.closed.get());
    assertTrue(state.ending().seal().isEmpty());
  }

  @Test
  void anErrorTheStreamThrowsEndsTheTurnAndTheSession() {
    var failure = new AssertionError("host");

    reader.read(ModelStreams.failing(failure));

    assertSame(failure, reader.outcome().streamError());
    assertSame(failure, state.ending().seal().orElseThrow());
  }

  @Test
  void anErrorAStreamFailsWithInsideACompletionExceptionEndsTheSession() {
    var failure = new AssertionError("joined failure");

    reader.read(ModelStreams.failing(new CompletionException(failure)));

    assertInstanceOf(CompletionException.class, reader.outcome().streamError());
    assertSame(failure, state.ending().seal().orElseThrow());
  }

  @Test
  void anErrorAChunksEventThrowsEndsTheTurnWithItAndTheSessionAndClosesTheStream() {
    var failure = new AssertionError("hook failed");
    var failing =
        newReader(
            event -> {
              throw failure;
            });
    var stream = new Tracked(ModelStreams.of(new ModelChunk.TextDelta("Hel"), STOP));

    failing.read(stream);

    assertSame(failure, failing.outcome().streamError());
    assertTrue(failing.released());
    assertTrue(stream.closed.get());
    assertSame(failure, state.ending().seal().orElseThrow());
  }

  @Test
  void aChunkReadAfterAnotherEndingIsDroppedAndTheStreamClosed() {
    var stream = new Tracked(ModelStreams.of(new ModelChunk.TextDelta("late"), STOP));

    reader.end(new CancellationException("closed"));
    reader.read(stream);

    assertInstanceOf(CancellationException.class, reader.outcome().streamError());
    assertEquals("", reader.outcome().assistantContent());
    assertEquals(List.of(), events);
    assertEquals(1, stream.taken);
    assertTrue(stream.closed.get());
  }

  @Test
  void anErrorTheStreamThrowsAfterAnotherEndingStillEndsTheSession() {
    var failure = new AssertionError("late");

    reader.end(new CancellationException("closed"));
    reader.read(ModelStreams.failing(failure));

    assertInstanceOf(CancellationException.class, reader.outcome().streamError());
    assertSame(failure, state.ending().seal().orElseThrow());
  }

  /**
   * Recording an {@link Error} cancels the session, whose callback ends the turn: holding the
   * session's ending holds the record, so an ending that arrives meanwhile stands for that
   * callback.
   */
  @Test
  void anErrorEndsTheTurnBeforeItIsRecordedSoTheCancellationTheRecordCausesCannotTakeTheTurn() {
    var failure = new AssertionError("host");
    var reading = new FutureTask<Void>(() -> reader.read(ModelStreams.failing(failure)), null);

    synchronized (state.ending()) {
      var thread = Thread.ofVirtual().start(reading);
      Await.until(
          "the Error's record to wait for the session's ending",
          () -> thread.getState() == Thread.State.BLOCKED);
      reader.end(new CancellationException("the cancellation the record causes"));
    }
    Await.value("the read", reading);

    assertSame(failure, reader.outcome().streamError());
    assertSame(failure, state.ending().seal().orElseThrow());
  }

  @Test
  void anEndingWhileAChunksEventIsEmittedReleasesTheRunnerOnlyAfterThatEvent() {
    var emitting = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stalled =
        newReader(
            event -> {
              emitting.countDown();
              Await.latch("the sink released", release);
            });
    var reading =
        CompletableFuture.runAsync(
            () -> stalled.read(ModelStreams.of(new ModelChunk.TextDelta("Hel"), STOP)));
    Await.latch("the chunk's event being emitted", emitting);

    stalled.end(new CancellationException("closed"));
    var releasedWhileEmitting = stalled.released();
    release.countDown();
    Await.value("the read", reading);

    assertFalse(releasedWhileEmitting);
    assertTrue(stalled.released());
    assertInstanceOf(CancellationException.class, stalled.outcome().streamError());
  }

  @Test
  void aCloseThatThrowsIsLoggedAndTheTurnKeepsItsEnding() {
    var stream = new Tracked(ModelStreams.of(STOP), new IllegalStateException("close failed"));

    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      reader.read(stream);
    }

    assertNull(reader.outcome().streamError());
    assertEquals(1, logged.size());
    assertEquals(Level.WARNING, logged.getFirst().getLevel());
    assertEquals("closing the model stream of session sess threw", logged.getFirst().getMessage());
    assertTrue(state.ending().seal().isEmpty());
  }

  @Test
  void anErrorACloseThrowsEndsTheSessionUnlogged() {
    var failure = new AssertionError("close failed");
    var stream = new Tracked(ModelStreams.of(STOP), failure);

    try (var ignored = LogCapture.of(TurnRunner.class, logged)) {
      reader.read(stream);
    }

    assertNull(reader.outcome().streamError());
    assertEquals(List.of(), logged);
    assertSame(failure, state.ending().seal().orElseThrow());
  }

  private TurnReader newReader(Consumer<QueryEvent> sink) {
    var model =
        new BlockingModel() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("not called");
          }

          @Override
          public String id() {
            return "stub";
          }

          @Override
          public String provider() {
            return "stub";
          }
        };
    var emitter =
        new EventEmitter(
            sink,
            HookRegistry.empty(),
            s ->
                new DefaultHookContext(
                    s.sessionId(), s.currentTurnIndex(), s.cancellation(), model),
            CLOCK);
    return new TurnReader(state, emitter, CLOCK);
  }

  /** A stream that records its close and throws {@code onClose}, if any, from it. */
  private static final class Tracked implements CloseableIterator<ModelChunk> {
    private final CloseableIterator<ModelChunk> chunks;
    private final Throwable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int taken;

    Tracked(CloseableIterator<ModelChunk> chunks) {
      this(chunks, null);
    }

    Tracked(CloseableIterator<ModelChunk> chunks, Throwable onClose) {
      this.chunks = chunks;
      this.onClose = onClose;
    }

    @Override
    public boolean hasNext() {
      return chunks.hasNext();
    }

    @Override
    public ModelChunk next() {
      taken++;
      return chunks.next();
    }

    @Override
    public void close() {
      closed.set(true);
      if (onClose instanceof RuntimeException unchecked) {
        throw unchecked;
      }
      if (onClose instanceof Error error) {
        throw error;
      }
    }
  }
}
