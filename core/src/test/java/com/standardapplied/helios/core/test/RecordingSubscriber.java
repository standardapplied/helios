/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link Flow.Subscriber} that records what a publisher sends, for a test to wait on through
 * {@link Await}: each item as it arrives, and the terminal signal. It requests {@code
 * initialDemand} on subscribe (none when {@code 0}), and counts every signal that breaks the
 * publisher protocol: a second {@code onSubscribe}, a signal after the terminal one, and an item
 * beyond what was requested.
 *
 * @param <T> the item type
 */
public final class RecordingSubscriber<T> implements Flow.Subscriber<T> {

  private final long initialDemand;
  private final List<T> items = new CopyOnWriteArrayList<>();
  private final LinkedBlockingQueue<T> arrivals = new LinkedBlockingQueue<>();
  private final CompletableFuture<Flow.Subscription> subscribed = new CompletableFuture<>();
  private final CompletableFuture<Void> terminal = new CompletableFuture<>();
  private final AtomicInteger violations = new AtomicInteger();
  private long requested;

  /** A subscriber that requests every item on subscribe. */
  public RecordingSubscriber() {
    this(Long.MAX_VALUE);
  }

  /** A subscriber that requests {@code initialDemand} items on subscribe, none when {@code 0}. */
  public RecordingSubscriber(long initialDemand) {
    this.initialDemand = initialDemand;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    if (!subscribed.complete(subscription)) {
      violations.incrementAndGet();
      return;
    }
    if (initialDemand > 0) {
      request(initialDemand);
    }
  }

  @Override
  public void onNext(T item) {
    synchronized (this) {
      if (terminal.isDone() || requested == 0) {
        violations.incrementAndGet();
      } else if (requested != Long.MAX_VALUE) {
        requested--;
      }
    }
    items.add(item);
    arrivals.add(item);
  }

  @Override
  public void onError(Throwable failure) {
    if (!terminal.completeExceptionally(failure)) {
      violations.incrementAndGet();
    }
  }

  @Override
  public void onComplete() {
    if (!terminal.complete(null)) {
      violations.incrementAndGet();
    }
  }

  /** Requests {@code n} more items through the subscription, once it has arrived. */
  public void request(long n) {
    synchronized (this) {
      requested = requested + n < 0 ? Long.MAX_VALUE : requested + n;
    }
    subscription().request(n);
  }

  /** Cancels the subscription, once it has arrived. */
  public void cancel() {
    subscription().cancel();
  }

  /** The subscription, waiting for {@code onSubscribe}. */
  public Flow.Subscription subscription() {
    return Await.value("onSubscribe", subscribed);
  }

  /** The next item to arrive, waiting for it. */
  public T next() {
    return Await.next("the next item", arrivals);
  }

  /** Every item received so far, in order. */
  public List<T> items() {
    return List.copyOf(items);
  }

  /** Waits for {@code onComplete} and returns every item received. */
  public List<T> awaitCompletion() {
    Await.value("onComplete", terminal);
    return items();
  }

  /** Waits for {@code onError} and returns its failure. */
  public Throwable awaitFailure() {
    return Await.failure("onError", terminal);
  }

  /**
   * Waits for either terminal signal; returns the failure of {@code onError}, else {@code null}.
   */
  public Throwable awaitEnd() {
    return Await.value("a terminal signal", terminal.handle((completed, failure) -> failure));
  }

  /** Whether a terminal signal has arrived. */
  public boolean ended() {
    return terminal.isDone();
  }

  /** How many signals broke the publisher protocol. */
  public int violations() {
    return violations.get();
  }
}
