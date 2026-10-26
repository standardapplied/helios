/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * The chunk stream of {@link BlockingModel#streamOf}. The call runs at most once; a close or an
 * interrupt of the reading thread before the call, during it or between the chunks of its answer
 * ends the stream in a {@link CancellationException}, unless the call threw an {@link Error} or a
 * {@link CompletionException} carrying one. The interrupt of a close stays set: it cannot be told
 * apart from one of the reading thread, which the stream keeps.
 */
final class BlockingStream implements CloseableIterator<ModelChunk> {

  private final Supplier<? extends Response<?>> call;
  private final Object lock = new Object();
  private Thread running;
  private volatile boolean closed;
  private Iterator<ModelChunk> chunks;

  BlockingStream(Supplier<? extends Response<?>> call) {
    this.call = call;
  }

  @Override
  public boolean hasNext() {
    if (chunks == null) {
      chunks = Collections.emptyIterator();
      chunks = chunksOf(run()).iterator();
    }
    if (chunks.hasNext() && cutShort()) {
      chunks = Collections.emptyIterator();
      throw cancelled(null);
    }
    return chunks.hasNext();
  }

  @Override
  public ModelChunk next() {
    if (!hasNext()) {
      throw new NoSuchElementException("the model's stream has ended");
    }
    return chunks.next();
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      if (running != null) {
        running.interrupt();
      }
    }
  }

  private Response<?> run() {
    synchronized (lock) {
      if (cutShort()) {
        throw cancelled(null);
      }
      running = Thread.currentThread();
    }
    try {
      return call.get();
    } catch (RuntimeException failure) {
      throw cutShort() && !thrownAsItself(failure) ? cancelled(failure) : failure;
    } finally {
      synchronized (lock) {
        running = null;
      }
    }
  }

  private boolean cutShort() {
    return closed || Thread.currentThread().isInterrupted();
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

  private static List<ModelChunk> chunksOf(Response<?> response) {
    var chunks = new ArrayList<ModelChunk>();
    if (response.hasThinking()) {
      chunks.add(new ModelChunk.ThinkingDelta(response.thinking()));
    }
    if (response.content() != null && !response.content().isEmpty()) {
      chunks.add(new ModelChunk.TextDelta(response.content()));
    }
    for (var toolCall : response.toolCalls()) {
      chunks.add(new ModelChunk.ToolUseStart(toolCall.id(), toolCall.name()));
      chunks.add(new ModelChunk.ToolUseStop(toolCall));
    }
    chunks.add(ModelChunk.MessageStop.of(response));
    return chunks;
  }
}
