/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.testing;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ModelChunk;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;

/**
 * Fixed model streams for tests: one-pass {@link CloseableIterator}s of {@link ModelChunk} that
 * yield a chunk sequence and end, yield one and fail, or yield one, possibly empty, and never end.
 * A test that scripts the same stream twice builds it twice. Script one into a {@link
 * ScriptedModel} with {@link ScriptedModel.Builder#withStreamTurn}.
 */
public final class ModelStreams {

  private ModelStreams() {}

  /**
   * A stream that yields {@code chunks} in order, then ends.
   *
   * @param chunks the chunks to yield
   * @return the stream
   */
  public static CloseableIterator<ModelChunk> of(ModelChunk... chunks) {
    return CloseableIterator.of(sequence(chunks).iterator());
  }

  /**
   * A stream that yields {@code chunks} in order, then throws {@code error} from {@code hasNext()}
   * as itself, as a provider's stream does when its connection breaks or its output does not parse.
   *
   * @param error the error that ends the stream: a {@link RuntimeException} or an {@link Error}
   * @param chunks the chunks to yield before the error; none to fail on the first {@code hasNext()}
   * @return the stream
   * @throws IllegalArgumentException if {@code error} is a checked exception, which {@code
   *     hasNext()} cannot throw
   */
  public static CloseableIterator<ModelChunk> failing(Throwable error, ModelChunk... chunks) {
    Objects.requireNonNull(error, "error must not be null");
    if (!(error instanceof RuntimeException) && !(error instanceof Error)) {
      throw new IllegalArgumentException(
          "a stream fails with a RuntimeException or an Error, not " + error.getClass().getName());
    }
    return new Failing(sequence(chunks).iterator(), error);
  }

  /**
   * A stream that yields {@code chunks} in order, then never yields another or ends, as a stalled
   * provider connection does. The {@code hasNext()} after the last chunk runs {@code onStall}, so a
   * test learns the reader has read every chunk and is now waiting, then blocks until the stream is
   * closed or the reading thread interrupted, and throws a {@link CancellationException}; an
   * interrupt stays set. {@code onStall} runs once, however often the stream is read after.
   *
   * @param onStall what to run when the stream stalls
   * @param chunks the chunks to yield before it stalls; none to stall at the first {@code
   *     hasNext()}
   * @return the stream
   */
  public static CloseableIterator<ModelChunk> stalled(Runnable onStall, ModelChunk... chunks) {
    Objects.requireNonNull(onStall, "onStall must not be null");
    return new Stalled(onStall, sequence(chunks).iterator());
  }

  private static List<ModelChunk> sequence(ModelChunk[] chunks) {
    Objects.requireNonNull(chunks, "chunks must not be null");
    Arrays.stream(chunks).forEach(chunk -> Objects.requireNonNull(chunk, "chunk must not be null"));
    return List.of(chunks);
  }

  private record Failing(Iterator<ModelChunk> chunks, Throwable error)
      implements CloseableIterator<ModelChunk> {

    @Override
    public boolean hasNext() {
      if (chunks.hasNext()) {
        return true;
      }
      if (error instanceof Error fatal) {
        throw fatal;
      }
      throw (RuntimeException) error;
    }

    @Override
    public ModelChunk next() {
      hasNext();
      return chunks.next();
    }

    @Override
    public void close() {}
  }

  private static final class Stalled implements CloseableIterator<ModelChunk> {

    private final Runnable onStall;
    private final Iterator<ModelChunk> chunks;
    private final CountDownLatch closed = new CountDownLatch(1);
    private boolean stalled;

    Stalled(Runnable onStall, Iterator<ModelChunk> chunks) {
      this.onStall = onStall;
      this.chunks = chunks;
    }

    @Override
    public boolean hasNext() {
      if (chunks.hasNext()) {
        return true;
      }
      throw stall();
    }

    @Override
    public ModelChunk next() {
      hasNext();
      return chunks.next();
    }

    private CancellationException stall() {
      if (!stalled) {
        stalled = true;
        onStall.run();
      }
      try {
        closed.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new CancellationException("the stalled stream was closed or interrupted");
    }

    @Override
    public void close() {
      closed.countDown();
    }
  }
}
