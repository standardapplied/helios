/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.runtime.CancellationToken;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A single-subscriber {@link Flow.Publisher} of what a blocking {@link CloseableIterator} yields.
 * Nothing runs before the subscriber's first request: that request starts one virtual thread, owned
 * by the subscription, which opens the iterator and pulls it at most one item ahead of demand. That
 * thread sends every signal after {@code onSubscribe}, so signals never overlap.
 *
 * <p>Cancelling the subscription or the {@link CancellationToken} interrupts the owned thread and
 * closes the iterator from the cancelling thread, so a pull blocked on I/O ends whether or not it
 * honours interrupts. A cancelled subscription receives no further signal; a cancelled token ends
 * the stream with {@code onError} carrying a {@link CancellationException} with the token's reason,
 * including when the token was cancelled before the first request. Work that ignores both an
 * interrupt and a closed iterator runs until it returns, and what it yields is dropped: Java cannot
 * end a thread by force.
 *
 * @param <T> the item type
 */
public final class IteratorPublisher<T> implements Flow.Publisher<T> {

  private final Supplier<? extends CloseableIterator<? extends T>> opener;
  private final CancellationToken cancellation;
  private final AtomicBoolean subscribed = new AtomicBoolean();

  /**
   * A publisher that opens its iterator through {@code opener} on the subscriber's first request.
   *
   * @param opener opens the iterator; may block, and runs on the subscription's own thread
   * @param cancellation ends the stream with a {@link CancellationException} once cancelled
   * @throws NullPointerException if an argument is null
   */
  public IteratorPublisher(
      Supplier<? extends CloseableIterator<? extends T>> opener, CancellationToken cancellation) {
    this.opener = Objects.requireNonNull(opener, "opener must not be null");
    this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
  }

  /**
   * Subscribes {@code subscriber}. A second subscriber receives {@code onSubscribe} with a
   * subscription that does nothing, then {@code onError} with an {@link IllegalStateException}.
   */
  @Override
  public void subscribe(Flow.Subscriber<? super T> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    if (!subscribed.compareAndSet(false, true)) {
      subscriber.onSubscribe(new Refused());
      subscriber.onError(new IllegalStateException("this publisher admits one subscriber"));
      return;
    }
    subscriber.onSubscribe(new Pull<>(opener, cancellation, subscriber));
  }

  private record Refused() implements Flow.Subscription {

    @Override
    public void request(long n) {
      // Refused: nothing is ever delivered.
    }

    @Override
    public void cancel() {
      // Refused: nothing to release.
    }
  }

  private static final class Pull<T> implements Flow.Subscription, Runnable {

    private final Supplier<? extends CloseableIterator<? extends T>> opener;
    private final CancellationToken cancellation;
    private final Flow.Subscriber<? super T> subscriber;
    private final Object lock = new Object();
    private long demand;
    private boolean started;
    private boolean ended;
    private String cancelReason;
    private IllegalArgumentException invalidRequest;
    private Thread worker;
    private CloseableIterator<? extends T> source;

    Pull(
        Supplier<? extends CloseableIterator<? extends T>> opener,
        CancellationToken cancellation,
        Flow.Subscriber<? super T> subscriber) {
      this.opener = opener;
      this.cancellation = cancellation;
      this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
      synchronized (lock) {
        if (ended || invalidRequest != null) {
          return;
        }
        if (n <= 0) {
          invalidRequest = new IllegalArgumentException("non-positive subscription request: " + n);
        } else {
          demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
        }
        if (!started) {
          started = true;
          worker = Thread.ofVirtual().name("helios-iterator-publisher").start(this);
        }
        lock.notifyAll();
      }
    }

    @Override
    public void cancel() {
      stop(null);
    }

    private void stop(String reason) {
      CloseableIterator<? extends T> open;
      Thread owner;
      synchronized (lock) {
        if (ended) {
          return;
        }
        ended = true;
        cancelReason = reason;
        open = source;
        source = null;
        owner = worker;
        lock.notifyAll();
      }
      if (owner != null && owner != Thread.currentThread()) {
        owner.interrupt();
      }
      if (open != null) {
        open.close();
      }
    }

    @Override
    public void run() {
      var registration = cancellation.onCancel(() -> stop(cancellation.reason().orElseThrow()));
      try {
        pull();
      } finally {
        registration.remove();
        closeSource();
      }
    }

    private void pull() {
      try {
        if (!adopt(null)) {
          end(null);
          return;
        }
        var opened = opener.get();
        if (!adopt(opened)) {
          end(null);
          return;
        }
        while (opened.hasNext()) {
          T item = opened.next();
          if (!awaitDemand()) {
            break;
          }
          subscriber.onNext(item);
        }
      } catch (RuntimeException failure) {
        end(failure);
        return;
      }
      end(null);
    }

    /**
     * Holds {@code opened}, or nothing yet, for a cancel to close; {@code false} when the stream
     * already ended or was sent an invalid request, and nothing more must be opened or pulled.
     */
    private boolean adopt(CloseableIterator<? extends T> opened) {
      synchronized (lock) {
        source = opened;
        return !ended && invalidRequest == null;
      }
    }

    /** Waits until the item may be delivered; {@code false} when the stream ended instead. */
    private boolean awaitDemand() {
      synchronized (lock) {
        while (demand == 0 && !ended && invalidRequest == null) {
          try {
            lock.wait();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
          }
        }
        if (ended || invalidRequest != null) {
          return false;
        }
        demand--;
        return true;
      }
    }

    /** Sends the one terminal signal the stream's state calls for, or none once cancelled. */
    private void end(RuntimeException failure) {
      Throwable error;
      synchronized (lock) {
        if (invalidRequest != null && !ended) {
          error = invalidRequest;
        } else if (cancelReason != null) {
          error = new CancellationException(cancelReason);
        } else if (ended) {
          return;
        } else {
          error = failure;
        }
        ended = true;
      }
      if (error == null) {
        subscriber.onComplete();
      } else {
        subscriber.onError(error);
      }
    }

    private void closeSource() {
      CloseableIterator<? extends T> open;
      synchronized (lock) {
        ended = true;
        open = source;
        source = null;
      }
      if (open != null) {
        open.close();
      }
    }
  }
}
