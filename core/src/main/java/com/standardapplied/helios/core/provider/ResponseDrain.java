/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

/**
 * The response of a turn's event stream, waited for: the {@link StreamEvent.Done}'s response
 * exactly, or the exception its {@link StreamEvent.Error} ends in. An interrupt while waiting
 * cancels the stream, restores the interrupt and throws the provider's exception.
 */
final class ResponseDrain implements Flow.Subscriber<StreamEvent> {

  private final StreamFailure failures;
  private final CountDownLatch done = new CountDownLatch(1);
  private volatile Flow.Subscription subscription;
  private Response<Void> response;
  private Throwable failure;

  private ResponseDrain(StreamFailure failures) {
    this.failures = failures;
  }

  /** Subscribes to {@code events} and waits for the turn's response. */
  static Response<Void> drain(Flow.Publisher<StreamEvent> events, StreamFailure failures) {
    var drain = new ResponseDrain(failures);
    events.subscribe(drain);
    return drain.await();
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onNext(StreamEvent event) {
    switch (event) {
      case StreamEvent.Done(var completed) -> end((Response<Void>) completed, null);
      case StreamEvent.Error error -> end(null, failures.of(error));
      default -> {}
    }
  }

  @Override
  public void onError(Throwable failure) {
    end(null, failure);
  }

  @Override
  public void onComplete() {
    end(null, failures.unterminated());
  }

  /** Records the turn's outcome unless its terminal event already did; signals arrive in order. */
  private void end(Response<Void> completed, Throwable failed) {
    if (done.getCount() > 0) {
      response = completed;
      failure = failed;
      done.countDown();
    }
  }

  private Response<Void> await() {
    try {
      done.await();
    } catch (InterruptedException interrupted) {
      subscription.cancel();
      Thread.currentThread().interrupt();
      throw failures.failure().create("Request interrupted", 0, interrupted);
    }
    if (failure == null) {
      return response;
    }
    throw switch (failure) {
      case RuntimeException unchecked -> unchecked;
      case Error error -> throw error;
      case Throwable checked -> failures.failure().create("Stream failed", 0, checked);
    };
  }
}
