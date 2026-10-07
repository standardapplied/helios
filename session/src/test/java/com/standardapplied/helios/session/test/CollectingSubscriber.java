/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.QueryEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A {@link Flow.Subscriber} of a session's events that requests everything, records each event in
 * arrival order, hands it to an optional reaction (answering a question, say) and counts down when
 * the stream ends, normally or with an error, which it keeps.
 */
public final class CollectingSubscriber implements Flow.Subscriber<QueryEvent> {

  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final CountDownLatch done = new CountDownLatch(1);
  private final AtomicReference<Throwable> error = new AtomicReference<>();
  private final Consumer<QueryEvent> onEvent;

  /** A collector that only records. */
  public CollectingSubscriber() {
    this(event -> {});
  }

  /** A collector that records each event, then passes it to {@code onEvent}. */
  public CollectingSubscriber(Consumer<QueryEvent> onEvent) {
    this.onEvent = onEvent;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(QueryEvent event) {
    events.add(event);
    onEvent.accept(event);
  }

  @Override
  public void onError(Throwable throwable) {
    error.set(throwable);
    done.countDown();
  }

  @Override
  public void onComplete() {
    done.countDown();
  }

  /** The events received so far, in arrival order. */
  public List<QueryEvent> events() {
    return List.copyOf(events);
  }

  /** The events received so far of type {@code type}, in arrival order. */
  public <E extends QueryEvent> List<E> eventsOf(Class<E> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  /** The error the stream ended with, or empty while it runs or once it completed normally. */
  public Optional<Throwable> error() {
    return Optional.ofNullable(error.get());
  }

  /** Waits through {@link Await} until the stream completes or fails. */
  public void awaitDone() {
    Await.latch("the event stream to complete", done);
  }
}
