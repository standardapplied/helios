/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelStreamsTest {

  private static final ModelChunk TEXT = new ModelChunk.TextDelta("hi");
  private static final ModelChunk STOP =
      new ModelChunk.MessageStop("STOP", Usage.of(1, 1), Map.of());

  @Test
  void ofDeliversEveryChunkInOrderThenCompletes() {
    var recorder = ChunkRecorder.drain(ModelStreams.of(TEXT, STOP));

    assertEquals(
        List.of("subscribed", TEXT.toString(), STOP.toString(), "complete"), recorder.signals);
  }

  @Test
  void ofDeliversOnlyWhatIsDemanded() {
    var recorder = new ChunkRecorder();
    ModelStreams.of(TEXT, STOP).subscribe(recorder);

    recorder.request(1);
    assertEquals(List.of("subscribed", TEXT.toString()), recorder.signals);

    recorder.request(1);
    assertEquals(
        List.of("subscribed", TEXT.toString(), STOP.toString(), "complete"), recorder.signals);
  }

  @Test
  void everySubscriberReceivesTheWholeSequence() {
    var stream = ModelStreams.of(TEXT);

    ChunkRecorder.drain(stream);
    var second = ChunkRecorder.drain(stream);

    assertEquals(List.of("subscribed", TEXT.toString(), "complete"), second.signals);
  }

  @Test
  void anEmptyStreamCompletesOnTheFirstRequest() {
    assertEquals(List.of("subscribed", "complete"), ChunkRecorder.drain(ModelStreams.of()).signals);
  }

  @Test
  void aCancelledStreamDeliversNothingMore() {
    var recorder = new ChunkRecorder();
    ModelStreams.of(TEXT, STOP).subscribe(recorder);
    recorder.request(1);

    recorder.cancel();
    recorder.request(1);

    assertEquals(List.of("subscribed", TEXT.toString()), recorder.signals);
  }

  @Test
  void aStreamCancelledFromItsSubscriberStopsAtThatChunkAndNeverCompletes() {
    for (var stream : List.of(ModelStreams.of(TEXT), ModelStreams.of(TEXT, STOP))) {
      var recorder = new ChunkRecorder().cancellingOnEveryChunk();
      stream.subscribe(recorder);

      recorder.request(Long.MAX_VALUE);

      assertEquals(List.of("subscribed", TEXT.toString()), recorder.signals);
    }
  }

  @Test
  void demandRequestedFromEveryChunkDeliversALongSequenceWithoutRecursion() {
    var chunks = Collections.nCopies(100_000, TEXT).toArray(ModelChunk[]::new);
    var streams =
        Map.of(
            "complete", ModelStreams.of(chunks),
            "error: cut", ModelStreams.failing(new IllegalStateException("cut"), chunks));
    streams.forEach(
        (terminal, stream) -> {
          var recorder = new ChunkRecorder().requestingOneOnEveryChunk();
          stream.subscribe(recorder);

          recorder.request(1);

          assertEquals(chunks.length + 2, recorder.signals.size());
          assertEquals(terminal, recorder.signals.getLast());
        });
  }

  @Test
  void cancelFromAnotherThreadReturnsDuringDeliveryAndStopsTheRemainingChunks() {
    var recorder =
        actWhileFirstSignalIsPaused(
            ModelStreams.of(TEXT, STOP), r -> r.request(Long.MAX_VALUE), ChunkRecorder::cancel);

    assertEquals(List.of("subscribed", TEXT.toString()), recorder.signals);
  }

  @Test
  void requestFromAnotherThreadReturnsDuringDeliveryAndLeavesItToTheDeliveringThread() {
    var recorder =
        actWhileFirstSignalIsPaused(
            ModelStreams.of(TEXT, STOP), r -> r.request(1), r -> r.request(1));

    assertEquals(
        List.of("subscribed", TEXT.toString(), STOP.toString(), "complete"), recorder.signals);
  }

  @Test
  void aNonPositiveRequestFailsTheStreamOnce() {
    var recorder = new ChunkRecorder();
    ModelStreams.of(TEXT).subscribe(recorder);

    recorder.request(0);
    recorder.request(1);

    assertEquals(
        List.of("subscribed", "error: non-positive subscription request: 0"), recorder.signals);
  }

  @Test
  void failingDeliversItsChunksThenTheError() {
    var recorder =
        ChunkRecorder.drain(ModelStreams.failing(new IllegalStateException("cut"), TEXT));

    assertEquals(List.of("subscribed", TEXT.toString(), "error: cut"), recorder.signals);
  }

  @Test
  void failingWithoutChunksFailsOnTheFirstRequest() {
    var recorder = ChunkRecorder.drain(ModelStreams.failing(new IllegalStateException("cut")));

    assertEquals(List.of("subscribed", "error: cut"), recorder.signals);
  }

  @Test
  void stalledRunsItsHookOnEveryRequestAndNeverSignals() {
    var requests = new AtomicInteger();
    var recorder = new ChunkRecorder();
    ModelStreams.stalled(requests::incrementAndGet).subscribe(recorder);

    recorder.request(1);
    recorder.request(Long.MAX_VALUE);
    recorder.cancel();

    assertEquals(2, requests.get());
    assertEquals(List.of("subscribed"), recorder.signals);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE})
  void stalledFailsOnceOnANonPositiveRequestWithoutRunningItsHook(long n) {
    var requests = new AtomicInteger();
    var recorder = new ChunkRecorder();
    ModelStreams.stalled(requests::incrementAndGet).subscribe(recorder);

    recorder.request(n);
    recorder.request(1);
    recorder.request(n);

    assertEquals(0, requests.get());
    assertEquals(
        List.of("subscribed", "error: non-positive subscription request: " + n), recorder.signals);
  }

  @Test
  void stalledIgnoresRequestsAfterCancel() {
    var requests = new AtomicInteger();
    var recorder = new ChunkRecorder();
    ModelStreams.stalled(requests::incrementAndGet).subscribe(recorder);

    recorder.cancel();
    recorder.request(1);
    recorder.request(0);

    assertEquals(0, requests.get());
    assertEquals(List.of("subscribed"), recorder.signals);
  }

  @Test
  void stalledCancelFromAnotherThreadReturnsWhileTheErrorIsDelivered() {
    var recorder =
        actWhileFirstSignalIsPaused(
            ModelStreams.stalled(() -> {}), r -> r.request(0), ChunkRecorder::cancel);

    assertEquals(
        List.of("subscribed", "error: non-positive subscription request: 0"), recorder.signals);
  }

  @Test
  void nullArgumentsAreRejected() {
    var error = new IllegalStateException("cut");
    assertThrows(NullPointerException.class, () -> ModelStreams.failing(null, TEXT));
    assertThrows(NullPointerException.class, () -> ModelStreams.stalled(null));
    assertThrows(NullPointerException.class, () -> ModelStreams.stalled(() -> {}).subscribe(null));
    assertThrows(NullPointerException.class, () -> ModelStreams.of(TEXT).subscribe(null));
    assertThrows(NullPointerException.class, () -> ModelStreams.failing(error, (ModelChunk) null));
  }

  private static ChunkRecorder actWhileFirstSignalIsPaused(
      Flow.Publisher<ModelChunk> stream,
      Consumer<ChunkRecorder> deliver,
      Consumer<ChunkRecorder> act) {
    var paused = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var recorder = new ChunkRecorder().pausingOnEverySignal(paused, release);
    stream.subscribe(recorder);
    var deliverer = Thread.ofVirtual().start(() -> deliver.accept(recorder));
    try {
      Await.latch("the first signal", paused);
      Await.value(
          "a subscription call made while a signal is paused",
          CompletableFuture.runAsync(() -> act.accept(recorder)));
    } finally {
      release.countDown();
    }
    Await.termination("the delivering thread", deliverer);
    return recorder;
  }
}
