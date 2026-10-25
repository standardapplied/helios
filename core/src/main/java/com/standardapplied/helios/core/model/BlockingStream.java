/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * The chunk stream of {@link BlockingModel#streamOf}: runs its call once, at the first {@code
 * hasNext()} on the reading thread, which {@code close()} interrupts while the call runs; a stream
 * closed, or a reading thread interrupted, before that never runs it. A call that ends after a
 * close, or that throws on an interrupt of the reading thread, ends the stream in a {@link
 * CancellationException}, as does a close or an interrupt between the chunks of its answer; an
 * {@link Error} the call throws is thrown as itself. Whatever ends the stream, it never runs the
 * call again.
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
      if (closed || Thread.currentThread().isInterrupted()) {
        throw cancelled(null);
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
   * Ends the call, clearing the interrupt of a close now that the call no longer runs, and says
   * whether it was {@linkplain #cutShort() cut short}.
   */
  private boolean finish() {
    synchronized (lock) {
      running = null;
      if (closed) {
        Thread.interrupted();
      }
    }
    return cutShort();
  }

  /** Whether the stream was closed or the reading thread, whose interrupt is kept, interrupted. */
  private boolean cutShort() {
    synchronized (lock) {
      if (closed) {
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
