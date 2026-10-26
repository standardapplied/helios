/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The {@link EventStream} a session hands each reader: a bounded buffer the session's {@link
 * SessionEventPublisher} offers into on the emitting thread and the reader drains on its own. An
 * offer waits for room only as long as it is told to; {@link #end} marks the session's end, after
 * which the reader drains what is buffered and stops.
 */
final class BufferedEventStream implements EventStream {

  static final int CAPACITY = 256;

  private final Consumer<BufferedEventStream> onClose;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private final ArrayDeque<QueryEvent> buffer = new ArrayDeque<>(CAPACITY);
  private final AtomicBoolean read = new AtomicBoolean();
  private boolean ended;
  private boolean closed;

  BufferedEventStream(Consumer<BufferedEventStream> onClose) {
    this.onClose = onClose;
  }

  /**
   * Buffer {@code event}, waiting up to {@code waitNanos} for room; an interrupt of the waiting
   * thread ends the wait, its interrupt status kept.
   *
   * @return {@code false} if the stream missed the event, {@code true} if it was buffered or the
   *     stream is closed
   */
  boolean offer(QueryEvent event, long waitNanos) {
    lock.lock();
    try {
      var remaining = waitNanos;
      while (!closed && buffer.size() == CAPACITY) {
        if (remaining <= 0) {
          return false;
        }
        remaining = changed.awaitNanos(remaining);
      }
      if (!closed) {
        buffer.add(event);
        changed.signalAll();
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      lock.unlock();
    }
  }

  /** Mark the session's end: the reader drains what is buffered, then the iteration ends. */
  void end() {
    lock.lock();
    try {
      ended = true;
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      buffer.clear();
      changed.signalAll();
    } finally {
      lock.unlock();
    }
    onClose.accept(this);
  }

  @Override
  public Iterator<QueryEvent> iterator() {
    if (!read.compareAndSet(false, true)) {
      throw new IllegalStateException("an EventStream is read once");
    }
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return awaitHead(false) != null;
      }

      @Override
      public QueryEvent next() {
        var event = awaitHead(true);
        if (event == null) {
          throw new NoSuchElementException("the event stream has ended");
        }
        return event;
      }
    };
  }

  /**
   * Wait until an event is buffered or the stream has ended, then return the next event, removed
   * from the buffer if {@code take}, or {@code null} at the end. An interrupted reader closes the
   * stream, even with events buffered.
   */
  private QueryEvent awaitHead(boolean take) {
    lock.lock();
    try {
      while (buffer.isEmpty() && !ended && !closed) {
        changed.await();
      }
      if (!Thread.currentThread().isInterrupted()) {
        if (take) {
          changed.signalAll();
          return buffer.poll();
        }
        return buffer.peek();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      lock.unlock();
    }
    close();
    return null;
  }
}
