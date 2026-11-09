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
 *
 * <p>The events a full buffer misses in a row are a gap, counted, and marked by one {@link
 * QueryEvent.EventsDropped} buffered just before the next event the stream takes, or at {@link
 * #end} if none comes. A miss only happens to a full buffer, so the gap is always at its tail, and
 * the marker may take the buffer one past its capacity rather than displace an event. A marker that
 * {@link #end} finds no such room for waits until the reader takes an event.
 */
final class BufferedEventStream implements EventStream {

  static final int CAPACITY = 256;

  private final Consumer<BufferedEventStream> onClose;
  final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private final ArrayDeque<QueryEvent> buffer = new ArrayDeque<>(CAPACITY);
  private final AtomicBoolean read = new AtomicBoolean();
  private boolean ended;
  private boolean closed;
  private long misses;
  private long gapLength;
  private QueryEvent gapStart;

  BufferedEventStream(Consumer<BufferedEventStream> onClose) {
    this.onClose = onClose;
  }

  /**
   * Buffer {@code event}, behind the marker of the gap before it, waiting up to {@code waitNanos}
   * for room; an interrupt of the waiting thread ends the wait, its interrupt status kept.
   *
   * @return how many events the stream has missed, this one included, or {@code 0} if it was
   *     buffered or the stream is closed
   */
  long offer(QueryEvent event, long waitNanos) {
    lock.lock();
    try {
      var remaining = waitNanos;
      while (!closed && buffer.size() >= CAPACITY) {
        if (remaining <= 0) {
          return miss(event);
        }
        remaining = changed.awaitNanos(remaining);
      }
      if (!closed) {
        markGap();
        buffer.add(event);
        changed.signalAll();
      }
      return 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return closed ? 0 : miss(event);
    } finally {
      lock.unlock();
    }
  }

  /** Mark the session's end: the reader drains what is buffered, then the iteration ends. */
  void end() {
    lock.lock();
    try {
      ended = true;
      markGap();
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  int size() {
    lock.lock();
    try {
      return buffer.size();
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
      gapLength = 0;
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

  private long miss(QueryEvent event) {
    if (gapLength == 0) {
      gapStart = event;
    }
    gapLength++;
    return ++misses;
  }

  private void markGap() {
    if (gapLength > 0 && buffer.size() <= CAPACITY) {
      buffer.add(
          new QueryEvent.EventsDropped(
              gapStart.sessionId(),
              gapStart.runId(),
              gapStart.turnIndex(),
              gapStart.timestamp(),
              gapLength));
      gapLength = 0;
      gapStart = null;
    }
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
        return take ? poll() : buffer.peek();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      lock.unlock();
    }
    close();
    return null;
  }

  private QueryEvent poll() {
    var event = buffer.poll();
    if (ended) {
      markGap();
    }
    changed.signalAll();
    return event;
  }
}
