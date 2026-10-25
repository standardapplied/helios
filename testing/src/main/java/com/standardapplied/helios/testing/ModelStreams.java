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
 * yield a chunk sequence and end, yield one and fail, or never end. A test that scripts the same
 * stream twice builds it twice. Script one into a {@link ScriptedModel} with {@link
 * ScriptedModel.Builder#withStreamTurn}.
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
   * A stream that never yields a chunk or ends, as a stalled provider connection does. Its first
   * {@code hasNext()} runs {@code onFirstPull}, so a test learns the reader is now waiting, then
   * blocks until the stream is closed or the reading thread interrupted, and throws a {@link
   * CancellationException}; an interrupt stays set.
   *
   * @param onFirstPull what to run on the first {@code hasNext()}
   * @return the stream
   */
  public static CloseableIterator<ModelChunk> stalled(Runnable onFirstPull) {
    return new Stalled(Objects.requireNonNull(onFirstPull, "onFirstPull must not be null"));
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

    private final Runnable onFirstPull;
    private final CountDownLatch closed = new CountDownLatch(1);
    private boolean pulled;

    Stalled(Runnable onFirstPull) {
      this.onFirstPull = onFirstPull;
    }

    @Override
    public boolean hasNext() {
      throw stall();
    }

    @Override
    public ModelChunk next() {
      throw stall();
    }

    private CancellationException stall() {
      if (!pulled) {
        pulled = true;
        onFirstPull.run();
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
