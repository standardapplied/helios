/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.EventStream;
import com.standardapplied.helios.session.QueryEvent;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link EventStream} fed by hand, standing for a session's: {@link #push} buffers an event
 * without a bound, {@link #end} ends the stream after what was pushed, as a session's end does, and
 * {@link #close} ends it at once and is recorded.
 */
final class FedEventStream implements EventStream {

  private static final Object END = new Object();

  private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  /** A stream holding {@code events}, then its end. */
  static FedEventStream of(QueryEvent... events) {
    var stream = new FedEventStream();
    for (var event : events) {
      stream.push(event);
    }
    stream.end();
    return stream;
  }

  void push(QueryEvent event) {
    queue.add(event);
  }

  void end() {
    queue.add(END);
  }

  boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      queue.add(END);
    }
  }

  @Override
  public Iterator<QueryEvent> iterator() {
    return new Iterator<>() {
      private Object head;

      @Override
      public boolean hasNext() {
        if (closed.get()) {
          return false;
        }
        if (head == null) {
          head = take();
        }
        return head != END;
      }

      @Override
      public QueryEvent next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        var event = (QueryEvent) head;
        head = null;
        return event;
      }
    };
  }

  private Object take() {
    try {
      return queue.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return END;
    }
  }
}
