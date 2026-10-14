/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * A turn's event stream as the {@link ModelChunk}s a model publishes. Each event becomes exactly
 * one chunk as it arrives: a {@link StreamEvent.KeepAlive} and a {@link
 * StreamEvent.ThinkingComplete} a {@link ModelChunk.KeepAlive}; the {@link StreamEvent.Done} the
 * {@link ModelChunk.MessageStop}, once its response passes the turn's check; the {@link
 * StreamEvent.Error} becomes {@code onError} with the exception it ends in. Demand and cancellation
 * pass straight through to the event stream.
 */
final class ChunkStream implements Flow.Publisher<ModelChunk> {

  private final Flow.Publisher<StreamEvent> events;
  private final StreamFailure failures;
  private final Consumer<Response<Void>> check;

  /**
   * @param events the turn's events
   * @param failures maps a failed turn to its exception
   * @param check runs on the completed response before its message stop, and fails the stream by
   *     throwing
   */
  ChunkStream(
      Flow.Publisher<StreamEvent> events, StreamFailure failures, Consumer<Response<Void>> check) {
    this.events = events;
    this.failures = failures;
    this.check = check;
  }

  @Override
  public void subscribe(Flow.Subscriber<? super ModelChunk> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    events.subscribe(new Relay(subscriber));
  }

  private final class Relay implements Flow.Subscriber<StreamEvent>, Flow.Subscription {

    private final Flow.Subscriber<? super ModelChunk> downstream;
    private volatile Flow.Subscription upstream;
    private boolean completed;
    private boolean ended;

    Relay(Flow.Subscriber<? super ModelChunk> downstream) {
      this.downstream = downstream;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      upstream = subscription;
      downstream.onSubscribe(this);
    }

    @Override
    public void request(long n) {
      upstream.request(n);
    }

    @Override
    public void cancel() {
      upstream.cancel();
    }

    @Override
    public void onNext(StreamEvent event) {
      if (ended) {
        return;
      }
      ModelChunk chunk;
      try {
        chunk = chunkOf(event);
      } catch (RuntimeException failure) {
        ended = true;
        try {
          upstream.cancel();
        } finally {
          downstream.onError(failure);
        }
        return;
      }
      downstream.onNext(chunk);
    }

    @SuppressWarnings("unchecked")
    private ModelChunk chunkOf(StreamEvent event) {
      return switch (event) {
        case StreamEvent.TextDelta(var text) -> new ModelChunk.TextDelta(text);
        case StreamEvent.ThinkingDelta(var text) -> new ModelChunk.ThinkingDelta(text);
        case StreamEvent.ThinkingComplete _ -> new ModelChunk.KeepAlive();
        case StreamEvent.KeepAlive _ -> new ModelChunk.KeepAlive();
        case StreamEvent.ToolCallStart(var callId, var toolName) ->
            new ModelChunk.ToolUseStart(callId, toolName);
        case StreamEvent.ToolCallDelta(var callId, var arguments) ->
            new ModelChunk.ToolUseDelta(callId, arguments);
        case StreamEvent.ToolCallComplete(var call) -> new ModelChunk.ToolUseStop(call);
        case StreamEvent.Done(var response) -> {
          check.accept((Response<Void>) response);
          completed = true;
          yield ModelChunk.MessageStop.of(response);
        }
        case StreamEvent.Error error -> throw failures.of(error);
      };
    }

    @Override
    public void onError(Throwable failure) {
      if (!ended) {
        ended = true;
        downstream.onError(failure);
      }
    }

    @Override
    public void onComplete() {
      if (ended) {
        return;
      }
      ended = true;
      if (completed) {
        downstream.onComplete();
      } else {
        downstream.onError(failures.unterminated());
      }
    }
  }
}
