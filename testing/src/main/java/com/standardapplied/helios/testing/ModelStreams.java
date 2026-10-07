/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.testing;

import com.standardapplied.helios.core.model.ModelChunk;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed model streams for tests: publishers of {@link ModelChunk} that replay a chunk sequence and
 * complete, replay one and fail, or never end. Each publisher serves any number of subscribers,
 * each receiving the whole sequence. Script one into a {@link ScriptedModel} with {@link
 * ScriptedModel.Builder#withStreamTurn}.
 */
public final class ModelStreams {

  private ModelStreams() {}

  /**
   * A stream that delivers {@code chunks} in order, as the subscriber demands them, then completes.
   *
   * @param chunks the chunks to deliver
   * @return the stream
   */
  public static Flow.Publisher<ModelChunk> of(ModelChunk... chunks) {
    return new Replay(sequence(chunks), null);
  }

  /**
   * A stream that delivers {@code chunks} in order, as the subscriber demands them, then fails with
   * {@code error}, as a provider does when its connection breaks or its output does not parse.
   *
   * @param error the error that ends the stream
   * @param chunks the chunks to deliver before the error; none to fail on the first request
   * @return the stream
   */
  public static Flow.Publisher<ModelChunk> failing(Throwable error, ModelChunk... chunks) {
    Objects.requireNonNull(error, "error must not be null");
    return new Replay(sequence(chunks), error);
  }

  /**
   * A stream that never delivers a chunk or ends, as a stalled provider connection does. {@code
   * onRequest} runs on the requesting thread each time the subscriber requests chunks, so a test
   * learns the consumer is now waiting. A non-positive request fails the stream with {@link
   * IllegalArgumentException} instead, as {@link Flow.Subscription#request} requires.
   *
   * @param onRequest what to run on each request
   * @return the stream
   */
  public static Flow.Publisher<ModelChunk> stalled(Runnable onRequest) {
    Objects.requireNonNull(onRequest, "onRequest must not be null");
    return subscriber -> {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      subscriber.onSubscribe(new StalledSubscription(onRequest, subscriber));
    };
  }

  private static final class StalledSubscription implements Flow.Subscription {

    private final Runnable onRequest;
    private final Flow.Subscriber<? super ModelChunk> subscriber;
    private final AtomicBoolean ended = new AtomicBoolean();

    StalledSubscription(Runnable onRequest, Flow.Subscriber<? super ModelChunk> subscriber) {
      this.onRequest = onRequest;
      this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
      if (n > 0 && !ended.get()) {
        onRequest.run();
      } else if (n <= 0 && ended.compareAndSet(false, true)) {
        subscriber.onError(nonPositiveRequest(n));
      }
    }

    @Override
    public void cancel() {
      ended.set(true);
    }
  }

  private static List<ModelChunk> sequence(ModelChunk[] chunks) {
    Objects.requireNonNull(chunks, "chunks must not be null");
    Arrays.stream(chunks).forEach(chunk -> Objects.requireNonNull(chunk, "chunk must not be null"));
    return List.of(chunks);
  }

  private static IllegalArgumentException nonPositiveRequest(long n) {
    return new IllegalArgumentException("non-positive subscription request: " + n);
  }

  private record Replay(List<ModelChunk> chunks, Throwable error)
      implements Flow.Publisher<ModelChunk> {

    @Override
    public void subscribe(Flow.Subscriber<? super ModelChunk> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      subscriber.onSubscribe(new ReplaySubscription(this, subscriber));
    }
  }

  private static final class ReplaySubscription implements Flow.Subscription {

    private final Replay replay;
    private final Flow.Subscriber<? super ModelChunk> subscriber;
    private final AtomicLong demand = new AtomicLong();
    private final AtomicInteger drainRequests = new AtomicInteger();
    private volatile IllegalArgumentException invalidRequest;
    private volatile boolean ended;
    private int next;

    ReplaySubscription(Replay replay, Flow.Subscriber<? super ModelChunk> subscriber) {
      this.replay = replay;
      this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
      if (n <= 0) {
        invalidRequest = nonPositiveRequest(n);
      } else {
        demand.accumulateAndGet(n, ReplaySubscription::saturatedAdd);
      }
      drain();
    }

    @Override
    public void cancel() {
      ended = true;
    }

    private static long saturatedAdd(long demand, long n) {
      return n >= Long.MAX_VALUE - demand ? Long.MAX_VALUE : demand + n;
    }

    /**
     * Delivers on one thread at a time, in a loop, and never under a lock. A request from {@code
     * onNext} or from another thread only records its demand and leaves delivery to the thread
     * already draining, which passes again until no request arrived since its last pass; so the
     * stack stays flat however long the sequence, and neither {@code request} nor {@code cancel}
     * waits on a subscriber callback.
     */
    private void drain() {
      if (drainRequests.getAndIncrement() != 0) {
        return;
      }
      var missed = 1;
      do {
        deliver();
        missed = drainRequests.addAndGet(-missed);
      } while (missed != 0);
    }

    private void deliver() {
      while (!ended) {
        if (invalidRequest != null) {
          ended = true;
          subscriber.onError(invalidRequest);
        } else if (next == replay.chunks().size()) {
          ended = true;
          end();
        } else if (demand.get() == 0) {
          return;
        } else {
          demand.decrementAndGet();
          subscriber.onNext(replay.chunks().get(next++));
        }
      }
    }

    private void end() {
      if (replay.error() == null) {
        subscriber.onComplete();
      } else {
        subscriber.onError(replay.error());
      }
    }
  }
}
