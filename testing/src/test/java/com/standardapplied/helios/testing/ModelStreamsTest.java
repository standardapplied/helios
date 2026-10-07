/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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

    assertEquals(2, requests.get());
    assertEquals(List.of("subscribed"), recorder.signals);
  }

  @Test
  void nullArgumentsAreRejected() {
    var error = new IllegalStateException("cut");
    assertThrows(NullPointerException.class, () -> ModelStreams.failing(null, TEXT));
    assertThrows(NullPointerException.class, () -> ModelStreams.stalled(null));
    assertThrows(NullPointerException.class, () -> ModelStreams.of(TEXT).subscribe(null));
    assertThrows(NullPointerException.class, () -> ModelStreams.failing(error, (ModelChunk) null));
  }
}
