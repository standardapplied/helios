/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.util.Iterator;

/**
 * An iterator that holds resources (e.g., HTTP connections, streams) that must be released when
 * iteration is complete or abandoned early.
 *
 * <p>Consumers should use try-with-resources:
 *
 * <pre>{@code
 * try (var events = model.chatStream(messages, tools)) {
 *   while (events.hasNext()) {
 *     var event = events.next();
 *     // process event
 *   }
 * }
 * }</pre>
 *
 * @param <T> the element type
 */
public interface CloseableIterator<T> extends Iterator<T>, AutoCloseable {

  /** Releases any underlying resources. Does not throw checked exceptions. */
  @Override
  void close();

  /**
   * Wraps a plain iterator as a no-op closeable iterator. Useful for default implementations that
   * don't hold resources.
   *
   * @param <T> the element type
   * @param iterator the iterator to wrap
   * @return a closeable iterator that delegates to the given iterator
   */
  static <T> CloseableIterator<T> of(Iterator<T> iterator) {
    return new CloseableIterator<>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public T next() {
        return iterator.next();
      }

      @Override
      public void close() {}
    };
  }
}
