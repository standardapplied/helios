/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ModelChunk;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that moves only when a model stream it paces moves it on, before each chunk the stream
 * yields, on whichever thread reads the stream.
 */
public final class HandClock implements InstantSource {

  private final AtomicReference<Instant> now;

  /** A clock reading {@code start} until moved. */
  public HandClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  /**
   * {@code stream}, moving this clock on by the next of {@code gaps} milliseconds before each chunk
   * it yields, the first gap before the first chunk; once the gaps run out, by none.
   */
  public CloseableIterator<ModelChunk> paced(CloseableIterator<ModelChunk> stream, long... gaps) {
    var taken = new AtomicInteger();
    return new CloseableIterator<>() {
      @Override
      public boolean hasNext() {
        return stream.hasNext();
      }

      @Override
      public ModelChunk next() {
        var index = taken.getAndIncrement();
        if (index < gaps.length) {
          now.updateAndGet(instant -> instant.plusMillis(gaps[index]));
        }
        return stream.next();
      }

      @Override
      public void close() {
        stream.close();
      }
    };
  }
}
