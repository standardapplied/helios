/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * The chunk stream of {@link BlockingModel#streamOf}: runs its call at the first {@code hasNext()}
 * on the reading thread, which {@code close()} interrupts while the call runs. A call that ends
 * after a close, or that throws on an interrupt of the reading thread, ends the stream in a {@link
 * CancellationException}; an {@link Error} the call throws is thrown as itself.
 */
final class BlockingStream implements CloseableIterator<ModelChunk> {

  private final Supplier<? extends Response<?>> call;
  private final Object lock = new Object();
  private Thread running;
  private boolean closed;
  private Iterator<ModelChunk> chunks;

  BlockingStream(Supplier<? extends Response<?>> call) {
    this.call = call;
  }

  @Override
  public boolean hasNext() {
    if (chunks == null) {
      chunks = chunksOf(run()).iterator();
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
      if (closed) {
        throw new CancellationException("the model's stream was closed");
      }
      running = Thread.currentThread();
    }
    Response<?> response = null;
    RuntimeException failure = null;
    boolean cutShort;
    try {
      response = call.get();
    } catch (RuntimeException thrown) {
      failure = thrown;
    } finally {
      cutShort = finish();
    }
    if (cutShort && !(failure instanceof CancellationException)) {
      throw cancelled(failure);
    }
    if (failure != null) {
      throw failure;
    }
    return response;
  }

  /**
   * Ends the call and says whether it was cut short: by a close, whose interrupt is cleared now
   * that the call no longer runs, or by an interrupt of the reading thread, which is kept.
   */
  private boolean finish() {
    synchronized (lock) {
      running = null;
      if (closed) {
        Thread.interrupted();
        return true;
      }
    }
    return Thread.currentThread().isInterrupted();
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
