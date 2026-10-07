/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.testing;

import com.standardapplied.helios.core.model.ModelChunk;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

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
    return new Replay(List.of(chunks), null);
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
    return new Replay(List.of(chunks), Objects.requireNonNull(error, "error must not be null"));
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
    private boolean ended;

    StalledSubscription(Runnable onRequest, Flow.Subscriber<? super ModelChunk> subscriber) {
      this.onRequest = onRequest;
      this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
      synchronized (this) {
        if (ended) {
          return;
        }
        if (n <= 0) {
          ended = true;
          subscriber.onError(nonPositiveRequest(n));
          return;
        }
      }
      onRequest.run();
    }

    @Override
    public synchronized void cancel() {
      ended = true;
    }
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
    private int next;
    private long demand;
    private boolean draining;
    private boolean ended;

    ReplaySubscription(Replay replay, Flow.Subscriber<? super ModelChunk> subscriber) {
      this.replay = replay;
      this.subscriber = subscriber;
    }

    @Override
    public synchronized void request(long n) {
      if (ended) {
        return;
      }
      if (n <= 0) {
        ended = true;
        subscriber.onError(nonPositiveRequest(n));
        return;
      }
      demand = n >= Long.MAX_VALUE - demand ? Long.MAX_VALUE : demand + n;
      if (!draining) {
        drain();
      }
    }

    /**
     * Delivers demanded chunks in a loop rather than by recursion: a subscriber that requests from
     * {@code onNext} re-enters this monitor and only adds to {@code demand}, so the stack stays
     * flat however long the sequence.
     */
    private void drain() {
      draining = true;
      try {
        while (demand > 0 && next < replay.chunks().size() && !ended) {
          demand--;
          subscriber.onNext(replay.chunks().get(next++));
        }
        if (next == replay.chunks().size() && !ended) {
          ended = true;
          end();
        }
      } finally {
        draining = false;
      }
    }

    @Override
    public synchronized void cancel() {
      ended = true;
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
