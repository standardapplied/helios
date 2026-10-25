/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * A turn's event stream as the {@link ModelChunk}s a model yields. Each event becomes exactly one
 * chunk as it is read: a {@link StreamEvent.KeepAlive} a {@link ModelChunk.KeepAlive}; the {@link
 * StreamEvent.Done} the {@link ModelChunk.MessageStop}, once its response passes the turn's check,
 * which ends the stream. A {@link StreamEvent.Error} is thrown from {@code hasNext()} as the
 * exception it ends in, and events that end without a {@code Done} throw as unterminated; either
 * closes the events first. Closing closes the events, from any thread: what the events yield or
 * throw after it, save an {@link Error}, is thrown as a {@link CancellationException}.
 */
final class ChunkStream implements CloseableIterator<ModelChunk> {

  private final CloseableIterator<StreamEvent> events;
  private final StreamFailure failures;
  private final Consumer<Response<Void>> check;
  private ModelChunk next;
  private boolean ended;
  private volatile boolean closed;

  /**
   * @param events the turn's events
   * @param failures maps a failed turn to its exception
   * @param check runs on the completed response before its message stop, and fails the stream by
   *     throwing
   */
  ChunkStream(
      CloseableIterator<StreamEvent> events,
      StreamFailure failures,
      Consumer<Response<Void>> check) {
    this.events = events;
    this.failures = failures;
    this.check = check;
  }

  @Override
  public boolean hasNext() {
    if (next != null) {
      return true;
    }
    if (ended) {
      return false;
    }
    try {
      if (!events.hasNext()) {
        throw failures.unterminated();
      }
      next = chunkOf(events.next());
      return true;
    } catch (RuntimeException failure) {
      ended = true;
      var cancelled = closed && !(failure instanceof CancellationException);
      events.close();
      throw cancelled ? cancelled(failure) : failure;
    } catch (Error error) {
      ended = true;
      events.close();
      throw error;
    }
  }

  @Override
  public ModelChunk next() {
    if (!hasNext()) {
      throw new NoSuchElementException("the model's stream has ended");
    }
    var chunk = next;
    next = null;
    return chunk;
  }

  @Override
  public void close() {
    closed = true;
    events.close();
  }

  private static CancellationException cancelled(RuntimeException cause) {
    var cancelled = new CancellationException("the model's stream was closed");
    cancelled.initCause(cause);
    return cancelled;
  }

  @SuppressWarnings("unchecked")
  private ModelChunk chunkOf(StreamEvent event) {
    return switch (event) {
      case StreamEvent.TextDelta(var text) -> new ModelChunk.TextDelta(text);
      case StreamEvent.ThinkingDelta(var text) -> new ModelChunk.ThinkingDelta(text);
      case StreamEvent.KeepAlive _ -> new ModelChunk.KeepAlive();
      case StreamEvent.ToolCallStart(var callId, var toolName) ->
          new ModelChunk.ToolUseStart(callId, toolName);
      case StreamEvent.ToolCallDelta(var callId, var arguments) ->
          new ModelChunk.ToolUseDelta(callId, arguments);
      case StreamEvent.ToolCallComplete(var call) -> new ModelChunk.ToolUseStop(call);
      case StreamEvent.Done(var response) -> {
        check.accept((Response<Void>) response);
        ended = true;
        events.close();
        yield ModelChunk.MessageStop.of(response);
      }
      case StreamEvent.Error error -> throw failures.of(error);
    };
  }
}
