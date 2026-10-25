/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import com.standardapplied.helios.core.model.CloseableIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * What one read of a stream to its end yielded, on the calling thread: every item, then the
 * exception {@code hasNext()} or {@code next()} threw, or {@code null} when the stream ended. The
 * stream is closed once read.
 *
 * @param items the items, in order
 * @param failure what ended the stream, or {@code null} when it ended normally
 * @param <T> the item type
 */
public record StreamRead<T>(List<T> items, RuntimeException failure) {

  /** Reads {@code stream} to its end and closes it. */
  public static <T> StreamRead<T> of(CloseableIterator<? extends T> stream) {
    var items = new ArrayList<T>();
    try (stream) {
      while (stream.hasNext()) {
        items.add(stream.next());
      }
      return new StreamRead<>(List.copyOf(items), null);
    } catch (RuntimeException failure) {
      return new StreamRead<>(List.copyOf(items), failure);
    }
  }
}
