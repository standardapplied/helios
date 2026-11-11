/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * A turn's event stream as the {@link ModelChunk}s a model yields, one chunk per event; the {@link
 * StreamEvent.Done} becomes the {@link ModelChunk.MessageStop} once its response passes the turn's
 * check, and a provider's {@link StreamEvent.UsageDelta} becomes a {@link ModelChunk.UsageDelta}. A
 * {@link StreamEvent.Error}, a {@code Done} that fails the check, and events that end without a
 * {@code Done}, are thrown from {@code hasNext()} after closing the events; an error whose partial
 * reports tokens, and a {@code Done} that fails the check whose response does, first yield that
 * usage as a {@link ModelChunk.UsageDelta}, so a reader counts what the provider billed. Once the
 * stream is closed or its reading thread interrupted (the interrupt kept), whatever is read, a
 * chunk read ahead included, is thrown as a {@link CancellationException}, unless it is an {@link
 * Error} or a {@link CompletionException} carrying one, so nothing retries a failure read then.
 */
final class ChunkStream implements CloseableIterator<ModelChunk> {

  private final CloseableIterator<StreamEvent> events;
  private final StreamFailure failures;
  private final Consumer<Response<Void>> check;
  private ModelChunk next;
  private RuntimeException pendingFailure;
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
    if (ended && next == null) {
      return false;
    }
    if (stopped()) {
      next = null;
      ended = true;
      close();
      throw cancelled(pendingFailure);
    }
    if (next == null) {
      next = read();
    }
    return true;
  }

  private ModelChunk read() {
    try {
      if (pendingFailure != null) {
        throw pendingFailure;
      }
      if (!events.hasNext()) {
        throw failures.unterminated();
      }
      var chunk = chunkOf(events.next());
      if (stopped()) {
        throw cancelled(pendingFailure);
      }
      return chunk;
    } catch (RuntimeException failure) {
      ended = true;
      events.close();
      throw stopped() && !thrownAsItself(failure) ? cancelled(failure) : failure;
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

  private boolean stopped() {
    return closed || Thread.currentThread().isInterrupted();
  }

  @Override
  public void close() {
    closed = true;
    events.close();
  }

  /**
   * A cancellation, and a {@link CompletionException} carrying an {@link Error}, such as a {@code
   * join} of a failed future throws, are thrown as themselves, so whoever reads the stream sees the
   * host {@code Error}.
   */
  private static boolean thrownAsItself(RuntimeException failure) {
    return failure instanceof CancellationException
        || failure instanceof CompletionException && failure.getCause() instanceof Error;
  }

  private static CancellationException cancelled(RuntimeException cause) {
    var cancelled = new CancellationException("the model's stream was closed or interrupted");
    cancelled.initCause(cause);
    return cancelled;
  }

  private ModelChunk chunkOf(StreamEvent event) {
    return switch (event) {
      case StreamEvent.TextDelta(var text) -> new ModelChunk.TextDelta(text);
      case StreamEvent.ThinkingDelta(var text) -> new ModelChunk.ThinkingDelta(text);
      case StreamEvent.KeepAlive _ -> new ModelChunk.KeepAlive();
      case StreamEvent.UsageDelta(var usage) -> new ModelChunk.UsageDelta(usage);
      case StreamEvent.ToolCallStart(var callId, var toolName) ->
          new ModelChunk.ToolUseStart(callId, toolName);
      case StreamEvent.ToolCallDelta(var callId, var arguments) ->
          new ModelChunk.ToolUseDelta(callId, arguments);
      case StreamEvent.ToolCallComplete(var call) -> new ModelChunk.ToolUseStop(call);
      case StreamEvent.Done(var response) -> completed(response);
      case StreamEvent.Error error -> usageBefore(failures.of(error), error.partial().usage());
    };
  }

  /**
   * The message stop of a {@code response} that passes the turn's check, which ends the stream;
   * what the check throws fails the turn as an error event does.
   */
  @SuppressWarnings("unchecked")
  private ModelChunk completed(Response<?> response) {
    try {
      check.accept((Response<Void>) response);
    } catch (RuntimeException rejected) {
      return usageBefore(rejected, response.usage());
    }
    ended = true;
    events.close();
    return ModelChunk.MessageStop.of(response);
  }

  /**
   * Yields the usage a failed turn reported and leaves {@code failure} to throw on the next read,
   * or throws it now when the turn reported none.
   */
  private ModelChunk usageBefore(RuntimeException failure, Response.Usage usage) {
    if (usage == null || !usage.reportsTokens()) {
      throw failure;
    }
    pendingFailure = failure;
    return new ModelChunk.UsageDelta(usage);
  }
}
