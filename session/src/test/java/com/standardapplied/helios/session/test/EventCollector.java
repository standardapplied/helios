/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.EventStream;
import com.standardapplied.helios.session.QueryEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Reads a session's {@link EventStream} to its end on a virtual thread of its own, recording each
 * event in arrival order and handing it to an optional reaction (answering a question, say). It
 * closes the stream and counts down when the read ends, normally or with a reaction's failure,
 * which it keeps; a session's stream ends once the session is closed, and the end of its first run
 * is {@link #awaitRunEnded()}.
 */
public final class EventCollector {

  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final CountDownLatch done = new CountDownLatch(1);
  private final CountDownLatch runEnded = new CountDownLatch(1);
  private final AtomicReference<Throwable> error = new AtomicReference<>();
  private final Consumer<QueryEvent> onEvent;

  /** A collector that only records. */
  public EventCollector() {
    this(event -> {});
  }

  /** A collector that records each event, then passes it to {@code onEvent}. */
  public EventCollector(Consumer<QueryEvent> onEvent) {
    this.onEvent = onEvent;
  }

  /**
   * Start reading {@code stream} on a virtual thread; call once.
   *
   * @return this collector
   */
  public EventCollector read(EventStream stream) {
    Thread.ofVirtual().name("event-collector").start(() -> readToEnd(stream));
    return this;
  }

  private void readToEnd(EventStream stream) {
    try (stream) {
      for (var event : stream) {
        events.add(event);
        if (event instanceof QueryEvent.RunEnded) {
          runEnded.countDown();
        }
        onEvent.accept(event);
      }
    } catch (RuntimeException | Error e) {
      error.set(e);
    } finally {
      done.countDown();
    }
  }

  /** The events received so far, in arrival order. */
  public List<QueryEvent> events() {
    return List.copyOf(events);
  }

  /** The events received so far of type {@code type}, in arrival order. */
  public <E extends QueryEvent> List<E> eventsOf(Class<E> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  /** What the read ended with, or empty while it runs or once it ended normally. */
  public Optional<Throwable> error() {
    return Optional.ofNullable(error.get());
  }

  /** Waits through {@link Await} until a {@link QueryEvent.RunEnded} has been recorded. */
  public void awaitRunEnded() {
    Await.latch("the run to end", runEnded);
  }

  /** Waits through {@link Await} until the read ends. */
  public void awaitDone() {
    Await.latch("the event stream to end", done);
  }
}
