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
 * Nothing runs before the subscriber's first request or a cancel of the token: either starts one
 * virtual thread, owned by the subscription, which opens the iterator and pulls it at most one item
 * ahead of demand. A request made inside {@code onSubscribe} starts the thread once {@code
 * onSubscribe} returns. That thread sends every signal after {@code onSubscribe}, so signals never
 * overlap. A subscriber whose {@code onSubscribe} throws has its subscription cancelled.
 *
 * <p>Cancelling the subscription or the {@link CancellationToken} interrupts the owned thread while
 * it opens or pulls the iterator, never while it runs the subscriber's code, and closes the
 * iterator from the cancelling thread, so a pull blocked on I/O ends whether or not it honours
 * interrupts. A cancelled subscription receives no further signal; a cancelled token ends the
 * stream with {@code onError} carrying a {@link CancellationException} with the token's reason,
 * including when the token was cancelled before any request. Work that ignores both an interrupt
 * and a closed iterator runs until it returns, and what it yields is dropped: Java cannot end a
 * thread by force.
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
    var pull = new Pull<>(opener, cancellation, subscriber);
    try {
      subscriber.onSubscribe(pull);
    } catch (RuntimeException | Error failure) {
      pull.cancel();
      throw failure;
    }
    pull.readyToRun();
  }

  /**
   * Interrupts the owned thread if it is pulling, so a blocked pull ends; called under the lock
   * that guards {@code pulling}, so the interrupt never reaches the subscriber's code.
   */
  private static void interrupt(Thread owner, boolean pulling) {
    if (pulling && owner != Thread.currentThread()) {
      owner.interrupt();
    }
  }

  /**
   * Closes the open iterator, so a pull blocked on I/O ends whether or not it honours interrupts.
   */
  private static void close(CloseableIterator<?> open) {
    if (open != null) {
      open.close();
    }
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
    private boolean ready;
    private boolean pulling;
    private boolean ended;
    private String cancelReason;
    private IllegalArgumentException invalidRequest;
    private Thread worker;
    private CloseableIterator<? extends T> source;
    private CancellationToken.Registration pendingCancellation =
        CancellationToken.Registration.NOOP;

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
      CloseableIterator<? extends T> open;
      synchronized (lock) {
        if (ended || invalidRequest != null) {
          return;
        }
        if (n > 0) {
          demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
        } else {
          invalidRequest = new IllegalArgumentException("non-positive subscription request: " + n);
        }
        startWorker();
        lock.notifyAll();
        if (n > 0) {
          return;
        }
        open = source;
        source = null;
        interrupt(worker, pulling);
      }
      close(open);
    }

    @Override
    public void cancel() {
      stop(null);
    }

    /**
     * Lets the owned thread run, starting it now when {@code onSubscribe} already requested, and
     * starts it on a cancel of the token so a subscriber that never requests still hears of it.
     */
    void readyToRun() {
      var watch = cancellation.onCancel(this::startWorker);
      boolean stale;
      synchronized (lock) {
        ready = true;
        pendingCancellation = watch;
        stale = ended;
        if (worker != null && !stale) {
          worker.start();
        }
      }
      if (stale) {
        watch.remove();
      }
    }

    private void startWorker() {
      synchronized (lock) {
        if (ended || worker != null) {
          return;
        }
        worker = Thread.ofVirtual().name("helios-iterator-publisher").unstarted(this);
        if (ready) {
          worker.start();
        }
      }
    }

    /**
     * Ends the stream, cancelled with {@code reason} unless it already ended, and closes the open
     * iterator either way.
     */
    private void stop(String reason) {
      CloseableIterator<? extends T> open;
      CancellationToken.Registration watch;
      synchronized (lock) {
        if (!ended) {
          ended = true;
          cancelReason = reason;
          interrupt(worker, pulling);
          lock.notifyAll();
        }
        open = source;
        source = null;
        watch = pendingCancellation;
      }
      watch.remove();
      close(open);
    }

    @Override
    public void run() {
      pendingCancellation.remove();
      var registration = cancellation.onCancel(() -> stop(cancellation.reason().orElseThrow()));
      try {
        pull();
      } finally {
        registration.remove();
        stop(null);
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
          synchronized (lock) {
            if (ended || invalidRequest != null) {
              break;
            }
            pulling = true;
          }
        }
      } catch (RuntimeException | Error failure) {
        end(failure);
        return;
      }
      end(null);
    }

    /**
     * Holds {@code opened}, or nothing yet, for a cancel to close, and marks the thread as pulling;
     * {@code false} when the stream already ended or was sent an invalid request, and nothing more
     * must be opened or pulled.
     */
    private boolean adopt(CloseableIterator<? extends T> opened) {
      synchronized (lock) {
        source = opened;
        pulling = !ended && invalidRequest == null;
        return pulling;
      }
    }

    /**
     * Ends the pull and waits until the item may be delivered; {@code false} when the stream ended.
     *
     * @throws CancellationException if the thread is interrupted while waiting, which only the
     *     subscriber's own code can do
     */
    private boolean awaitDemand() {
      synchronized (lock) {
        pulling = false;
        while (demand == 0 && !ended && invalidRequest == null) {
          try {
            lock.wait();
          } catch (InterruptedException interrupted) {
            throw new CancellationException("interrupted while waiting for demand");
          }
        }
        if (ended || invalidRequest != null) {
          return false;
        }
        demand--;
        return true;
      }
    }

    /**
     * Sends the one terminal signal the stream's state calls for, or none once cancelled, clearing
     * an interrupt a cancel sent the pull.
     */
    private void end(Throwable failure) {
      Throwable error;
      synchronized (lock) {
        Thread.interrupted();
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
  }
}
