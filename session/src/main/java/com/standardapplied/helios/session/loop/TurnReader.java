/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.QueryEvent;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads one turn's model stream into per-turn {@link QueryEvent}s and accumulates what it produced
 * into a {@link TurnOutcome}. {@link ModelCall} creates one per attempt, runs {@link #read} on the
 * turn's reader thread and waits in {@link #await()} for its {@link #outcome()}.
 *
 * <h2>One terminal transition</h2>
 *
 * The first ending wins: the stream's end, a failure it or a chunk's emission throws, or {@link
 * #end} from {@link ModelCall} when the session is cancelled or its thread interrupted. It
 * snapshots what the stream produced under the lock that guards every accumulation, so a turn that
 * failed, was cancelled or ended without its {@link ModelChunk.MessageStop} finishes as {@link
 * FinishReason#ERROR}. A chunk read after the ending is dropped and emits nothing. A chunk accepted
 * before it emits its event outside the lock, and the waiting runner is released once that event is
 * out, so every event of the turn precedes the runner's next one.
 *
 * <h2>Host errors</h2>
 *
 * Every {@link Error} that reaches the turn, from the stream, a chunk's emission or the stream's
 * close, whenever it arrives, is recorded by the session's {@link SessionEnding}, which ends the
 * session; so is one a {@link java.util.concurrent.CompletionException} carries ({@link
 * SessionEnding#failWith}).
 */
final class TurnReader {

  private static final Logger LOGGER = Logger.getLogger(TurnRunner.class.getName());

  private final SessionState state;
  private final EventEmitter emitter;
  private final InstantSource clock;
  private final SessionEnding ending;
  private final CountDownLatch done = new CountDownLatch(1);
  private final Object lock = new Object();
  private final StringBuilder content = new StringBuilder();
  private final List<ToolCall> toolCalls = new ArrayList<>();
  private ModelChunk.MessageStop stop;
  private boolean emitting;
  private TurnOutcome outcome;
  private boolean closed;

  TurnReader(SessionState state, EventEmitter emitter, InstantSource clock) {
    this.state = state;
    this.emitter = emitter;
    this.clock = clock;
    this.ending = state.ending();
  }

  /**
   * Read {@code stream} to its end, or until the turn ended otherwise, then close it. What reading
   * or emitting throws ends the turn with it.
   */
  void read(CloseableIterator<ModelChunk> stream) {
    try {
      var reading = true;
      while (reading && stream.hasNext()) {
        reading = accept(stream.next());
      }
      end(null);
    } catch (Throwable failure) {
      end(failure);
    } finally {
      close(stream);
    }
  }

  /**
   * Block until the turn ended and its last event is out.
   *
   * @throws InterruptedException if the waiting thread is interrupted first
   */
  void await() throws InterruptedException {
    done.await();
  }

  /** Whether the turn ended and the waiting runner has been released. */
  boolean released() {
    return done.getCount() == 0L;
  }

  /** What the turn produced, once it ended; {@code null} before. */
  TurnOutcome outcome() {
    synchronized (lock) {
      return outcome;
    }
  }

  /**
   * The one terminal transition: unless the turn already ended, snapshot it with {@code error}, or
   * none for a stream that ended, and release the runner, or leave its release to the chunk whose
   * event is being emitted. An {@link Error}, or one {@code error} carries, is then recorded by the
   * session's {@link SessionEnding}, whether or not the turn already ended, and before the runner
   * is released; the snapshot comes first, so the cancellation the record causes cannot take the
   * turn's ending from it.
   */
  void end(Throwable error) {
    var release = false;
    synchronized (lock) {
      if (outcome == null) {
        outcome = snapshot(error);
        release = !emitting;
      }
    }
    if (error != null) {
      ending.failWith(error);
    }
    if (release) {
      done.countDown();
    }
  }

  private boolean accept(ModelChunk chunk) {
    synchronized (lock) {
      if (outcome != null) {
        return false;
      }
      accumulate(chunk);
      emitting = true;
    }
    try {
      emit(chunk);
    } finally {
      boolean release;
      synchronized (lock) {
        emitting = false;
        release = outcome != null;
      }
      if (release) {
        done.countDown();
      }
    }
    return true;
  }

  private void accumulate(ModelChunk chunk) {
    switch (chunk) {
      case ModelChunk.TextDelta(String text) -> content.append(text);
      case ModelChunk.ToolUseStop(ToolCall call) -> toolCalls.add(call);
      case ModelChunk.MessageStop messageStop -> stop = messageStop;
      default -> {}
    }
  }

  private void emit(ModelChunk chunk) {
    switch (chunk) {
      case ModelChunk.TextDelta(String text) ->
          emitter.emit(
              state,
              new QueryEvent.AssistantText(
                  state.sessionId(),
                  state.runId(),
                  state.currentTurnIndex(),
                  clock.instant(),
                  text));
      case ModelChunk.ThinkingDelta(String text) ->
          emitter.emit(
              state,
              new QueryEvent.AssistantThinking(
                  state.sessionId(),
                  state.runId(),
                  state.currentTurnIndex(),
                  clock.instant(),
                  text));
      case ModelChunk.MessageStop messageStop when !messageStop.citations().isEmpty() ->
          emitter.emit(
              state,
              new QueryEvent.AssistantCitations(
                  state.sessionId(),
                  state.runId(),
                  state.currentTurnIndex(),
                  clock.instant(),
                  messageStop.citations()));
      default -> {}
    }
  }

  /**
   * Close {@code stream} once; what closing it throws goes where any of the turn's failures go. A
   * second close waits for the first to finish, so the reader, which closes the stream as it ends,
   * ends only once a close the session's cancellation started has recorded what it threw.
   */
  synchronized void close(CloseableIterator<ModelChunk> stream) {
    if (closed) {
      return;
    }
    closed = true;
    try {
      stream.close();
    } catch (Throwable failure) {
      if (!ending.failWith(failure)) {
        LOGGER.log(
            Level.WARNING,
            "closing the model stream of session " + state.sessionId() + " threw",
            failure);
      }
    }
  }

  private TurnOutcome snapshot(Throwable error) {
    var stopped = stop != null;
    var failure =
        stopped || error != null
            ? error
            : new IllegalStateException("model stream ended without its MessageStop");
    return new TurnOutcome(
        failure != null ? FinishReason.ERROR : stop.finishReason(),
        content.toString(),
        toolCalls,
        stopped ? stop.citations() : List.of(),
        stopped ? stop.usage() : Usage.of(0, 0),
        stopped ? stop.metadata() : Map.of(),
        failure,
        1);
  }
}
