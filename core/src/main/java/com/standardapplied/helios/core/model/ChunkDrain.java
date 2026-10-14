/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

/**
 * The response a chunk stream adds up to, for {@link Model}'s blocking {@code chat}: text and
 * thinking deltas joined, completed tool calls in order, and the finish reason, usage, metadata and
 * citations of the {@link ModelChunk.MessageStop}. The stream's failure is thrown as it came, and a
 * stream that completes without its {@code MessageStop} throws {@link IllegalStateException}.
 */
final class ChunkDrain implements Flow.Subscriber<ModelChunk> {

  private final CountDownLatch done = new CountDownLatch(1);
  private final StringBuilder content = new StringBuilder();
  private final StringBuilder thinking = new StringBuilder();
  private final List<ToolCall> toolCalls = new ArrayList<>();
  private ModelChunk.MessageStop stop;
  private volatile Flow.Subscription subscription;
  private volatile boolean abandoned;
  private Throwable failure;

  private ChunkDrain() {}

  /**
   * Subscribes to {@code chunks} and waits for the response they add up to.
   *
   * @throws CancellationException if interrupted while waiting; the stream is cancelled and the
   *     interrupt restored
   */
  static Response<Void> drain(Flow.Publisher<ModelChunk> chunks) {
    var drain = new ChunkDrain();
    chunks.subscribe(drain);
    return drain.await();
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    if (abandoned) {
      subscription.cancel();
      return;
    }
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ModelChunk chunk) {
    switch (chunk) {
      case ModelChunk.TextDelta(var text) -> content.append(text);
      case ModelChunk.ThinkingDelta(var text) -> thinking.append(text);
      case ModelChunk.ToolUseStop(var call) -> toolCalls.add(call);
      case ModelChunk.MessageStop messageStop -> stop = messageStop;
      case ModelChunk.ToolUseStart ignored -> {}
      case ModelChunk.ToolUseDelta ignored -> {}
      case ModelChunk.UsageDelta ignored -> {}
      case ModelChunk.KeepAlive ignored -> {}
    }
  }

  @Override
  public void onError(Throwable failure) {
    this.failure = failure;
    done.countDown();
  }

  @Override
  public void onComplete() {
    done.countDown();
  }

  private Response<Void> response() {
    return Response.newBuilder()
        .withContent(content.toString())
        .withThinking(thinking.isEmpty() ? null : thinking.toString())
        .withToolCalls(toolCalls)
        .withFinishReason(stop.finishReason())
        .withUsage(stop.usage())
        .withMetadata(stop.metadata())
        .withCitations(stop.citations())
        .build();
  }

  private Response<Void> await() {
    try {
      done.await();
    } catch (InterruptedException interrupted) {
      abandoned = true;
      var open = subscription;
      try {
        if (open != null) {
          open.cancel();
        }
      } finally {
        Thread.currentThread().interrupt();
      }
      throw new CancellationException("interrupted while waiting for the model's response");
    }
    if (failure != null) {
      throw rethrown(failure);
    }
    if (stop == null) {
      throw new IllegalStateException("the model's stream completed without its MessageStop");
    }
    return response();
  }

  private static RuntimeException rethrown(Throwable failure) {
    return switch (failure) {
      case RuntimeException unchecked -> unchecked;
      case Error error -> throw error;
      default -> new IllegalStateException("the model's stream failed: " + failure, failure);
    };
  }
}
