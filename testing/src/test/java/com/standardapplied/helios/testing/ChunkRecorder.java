/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.testing;

import com.standardapplied.helios.core.model.ModelChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Records every signal a model stream sends, as text, and requests chunks only when told to, so a
 * test controls the demand.
 */
final class ChunkRecorder implements Flow.Subscriber<ModelChunk> {

  final List<String> signals = new ArrayList<>();
  private Flow.Subscription subscription;

  static ChunkRecorder drain(Flow.Publisher<ModelChunk> stream) {
    var recorder = new ChunkRecorder();
    stream.subscribe(recorder);
    recorder.request(Long.MAX_VALUE);
    return recorder;
  }

  void request(long n) {
    subscription.request(n);
  }

  void cancel() {
    subscription.cancel();
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    signals.add("subscribed");
  }

  @Override
  public void onNext(ModelChunk chunk) {
    signals.add(chunk.toString());
  }

  @Override
  public void onError(Throwable throwable) {
    signals.add("error: " + throwable.getMessage());
  }

  @Override
  public void onComplete() {
    signals.add("complete");
  }
}
