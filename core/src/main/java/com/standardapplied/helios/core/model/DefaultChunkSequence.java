/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.runtime.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The chunk sequence {@link Model}'s default streaming methods synthesise from a blocking {@link
 * Response}: thinking, text, a start/stop pair per tool call, then the message stop. Each
 * subscriber receives the whole sequence synchronously as it requests chunks.
 */
final class DefaultChunkSequence implements Flow.Publisher<ModelChunk> {

  private final Response<?> response;
  private final CancellationToken cancellation;

  DefaultChunkSequence(Response<?> response, CancellationToken cancellation) {
    this.response = response;
    this.cancellation = cancellation;
  }

  @Override
  public void subscribe(Flow.Subscriber<? super ModelChunk> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    subscriber.onSubscribe(new ChunkSubscription(chunks(), cancellation, subscriber));
  }

  private List<ModelChunk> chunks() {
    var chunks = new ArrayList<ModelChunk>();
    if (response.hasThinking()) {
      chunks.add(new ModelChunk.ThinkingDelta(response.thinking()));
    }
    if (response.content() != null && !response.content().isEmpty()) {
      chunks.add(new ModelChunk.TextDelta(response.content()));
    }
    for (var call : response.toolCalls()) {
      chunks.add(new ModelChunk.ToolUseStart(call.id(), call.name()));
      chunks.add(new ModelChunk.ToolUseStop(call));
    }
    chunks.add(messageStop());
    return chunks;
  }

  private ModelChunk.MessageStop messageStop() {
    var stopReason =
        response.finishReason() != null ? response.finishReason().name() : FinishReason.STOP.name();
    var usage = response.usage() != null ? response.usage() : Response.Usage.of(0, 0);
    var metadata = response.metadata() != null ? response.metadata() : Map.<String, String>of();
    var citations = response.citations() != null ? response.citations() : List.<Citation>of();
    return new ModelChunk.MessageStop(stopReason, usage, metadata, citations);
  }

  private static final class ChunkSubscription implements Flow.Subscription {

    private final List<ModelChunk> chunks;
    private final CancellationToken cancellation;
    private final Flow.Subscriber<? super ModelChunk> subscriber;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private int index;

    ChunkSubscription(
        List<ModelChunk> chunks,
        CancellationToken cancellation,
        Flow.Subscriber<? super ModelChunk> subscriber) {
      this.chunks = chunks;
      this.cancellation = cancellation;
      this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
      if (!active.get()) {
        return;
      }
      if (n <= 0) {
        fail(() -> new IllegalArgumentException("non-positive subscription request: " + n));
      } else if (cancellation.isCancelled()) {
        fail(() -> new CancellationException(cancellation.reason().orElseThrow()));
      } else {
        deliver(n);
      }
    }

    @Override
    public void cancel() {
      active.set(false);
    }

    private void deliver(long n) {
      var remaining = n;
      while (remaining > 0 && index < chunks.size() && active.get()) {
        subscriber.onNext(chunks.get(index++));
        remaining--;
      }
      if (index >= chunks.size() && active.compareAndSet(true, false)) {
        subscriber.onComplete();
      }
    }

    private void fail(Supplier<? extends Throwable> error) {
      if (active.compareAndSet(true, false)) {
        subscriber.onError(error.get());
      }
    }
  }
}
