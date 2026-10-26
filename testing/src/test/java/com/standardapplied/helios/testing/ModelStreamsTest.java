/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.StreamRead;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ModelStreamsTest {

  private static final ModelChunk TEXT = new ModelChunk.TextDelta("hi");
  private static final ModelChunk STOP =
      new ModelChunk.MessageStop("STOP", Usage.of(1, 1), Map.of());

  @Test
  void ofYieldsEveryChunkInOrderThenEnds() {
    var read = StreamRead.of(ModelStreams.of(TEXT, STOP));

    assertEquals(List.of(TEXT, STOP), read.items());
    assertNull(read.failure());
  }

  @Test
  void anEmptyStreamEndsAtTheFirstRead() {
    var read = StreamRead.of(ModelStreams.of());

    assertEquals(List.of(), read.items());
    assertNull(read.failure());
  }

  @Test
  void aStreamIsReadOnce() {
    var stream = ModelStreams.of(TEXT);
    StreamRead.of(stream);

    assertEquals(List.of(), StreamRead.of(stream).items());
  }

  @Test
  void failingYieldsItsChunksThenThrowsTheErrorAsItself() {
    var error = new IllegalStateException("cut");

    var read = StreamRead.of(ModelStreams.failing(error, TEXT, STOP));

    assertEquals(List.of(TEXT, STOP), read.items());
    assertSame(error, read.failure());
  }

  @Test
  void failingWithoutChunksThrowsAtTheFirstRead() {
    var error = new IllegalStateException("cut");

    var read = StreamRead.of(ModelStreams.failing(error));

    assertEquals(List.of(), read.items());
    assertSame(error, read.failure());
  }

  @Test
  void failingThrowsAnErrorAsTheSameInstanceFromHasNextAndNext() {
    var error = new OutOfMemoryError("host");
    var stream = ModelStreams.failing(error, TEXT);

    assertEquals(TEXT, stream.next());
    assertSame(error, assertThrows(OutOfMemoryError.class, stream::hasNext));
    assertSame(error, assertThrows(OutOfMemoryError.class, stream::next));
  }

  @Test
  void failingRejectsACheckedException() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> ModelStreams.failing(new IOException("closed"), TEXT));

    assertEquals(
        "a stream fails with a RuntimeException or an Error, not java.io.IOException",
        thrown.getMessage());
  }

  @Test
  void stalledRunsOnFirstPullThenBlocksUntilClosed() {
    var pulled = new CountDownLatch(1);
    var stream = ModelStreams.stalled(pulled::countDown);
    var interruptedAfter = readOnAVirtualThread(stream).interruptedAfter();

    Await.latch("the first pull", pulled);
    assertFalse(interruptedAfter.isDone(), "a stalled stream ends only when closed or interrupted");
    stream.close();

    assertFalse(Await.value("the reader's end after the close", interruptedAfter));
  }

  @Test
  void stalledEndsOnAnInterruptAndKeepsItSet() {
    var pulled = new CountDownLatch(1);
    var reading = readOnAVirtualThread(ModelStreams.stalled(pulled::countDown));

    Await.latch("the first pull", pulled);
    reading.reader().interrupt();

    assertTrue(Await.value("the reader's end after the interrupt", reading.interruptedAfter()));
  }

  @Test
  void aClosedStalledStreamThrowsOnEveryReadAndRunsOnFirstPullOnce() {
    var pulls = new AtomicInteger();
    var stream = ModelStreams.stalled(pulls::incrementAndGet);
    stream.close();

    assertThrows(CancellationException.class, stream::hasNext);
    assertThrows(CancellationException.class, stream::hasNext);
    assertThrows(CancellationException.class, stream::next);

    assertEquals(1, pulls.get());
  }

  @Test
  void nullArgumentsAreRejected() {
    var error = new IllegalStateException("cut");
    assertNullRejected("error must not be null", () -> ModelStreams.failing(null, TEXT));
    assertNullRejected("onFirstPull must not be null", () -> ModelStreams.stalled(null));
    assertNullRejected("chunks must not be null", () -> ModelStreams.of((ModelChunk[]) null));
    assertNullRejected("chunk must not be null", () -> ModelStreams.of(TEXT, null));
    assertNullRejected(
        "chunk must not be null", () -> ModelStreams.failing(error, TEXT, (ModelChunk) null));
  }

  private static void assertNullRejected(String message, Executable call) {
    assertEquals(message, assertThrows(NullPointerException.class, call).getMessage());
  }

  private record Reading(Thread reader, CompletableFuture<Boolean> interruptedAfter) {}

  private static Reading readOnAVirtualThread(CloseableIterator<ModelChunk> stream) {
    var interruptedAfter = new CompletableFuture<Boolean>();
    var reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    stream.hasNext();
                    interruptedAfter.completeExceptionally(
                        new AssertionError("a stalled stream's hasNext returned"));
                  } catch (CancellationException e) {
                    interruptedAfter.complete(Thread.currentThread().isInterrupted());
                  } catch (RuntimeException e) {
                    interruptedAfter.completeExceptionally(e);
                  }
                });
    return new Reading(reader, interruptedAfter);
  }
}
