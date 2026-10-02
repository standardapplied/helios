/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FeedableInputStreamTest {

  @Test
  void deliversFedBytesInOrderThenTheEnd() throws IOException {
    var stream = new FeedableInputStream();
    stream.feed(new byte[] {1, 2});
    stream.feed(new byte[0]);
    stream.feed(new byte[] {(byte) 0xFF});
    stream.end();

    assertEquals(1, stream.read());
    assertEquals(2, stream.read());
    assertEquals(0xFF, stream.read());
    assertEquals(-1, stream.read());
    assertEquals(-1, stream.read());
  }

  @Test
  void aBulkReadReturnsWhatIsFedWithoutWaitingForMore() throws IOException {
    var stream = new FeedableInputStream();
    stream.feed("abc");
    var target = new byte[8];

    assertEquals(2, stream.read(target, 1, 2));
    assertEquals(1, stream.read(target, 3, 5));
    assertArrayEquals(new byte[] {0, 'a', 'b', 'c', 0, 0, 0, 0}, target);
  }

  @Test
  void aZeroLengthReadReturnsZeroWithoutBlocking() throws IOException {
    assertEquals(0, new FeedableInputStream().read(new byte[4], 0, 0));
  }

  @Test
  void aReadOutsideTheTargetIsRejected() {
    var stream = new FeedableInputStream();

    assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[2], 1, 2));
  }

  @Test
  void theFedArrayIsCopied() throws IOException {
    var stream = new FeedableInputStream();
    var bytes = new byte[] {7};
    stream.feed(bytes);
    bytes[0] = 9;

    assertEquals(7, stream.read());
  }

  @Test
  void aBlockedReadReturnsWhatIsFedLater() {
    var stream = new FeedableInputStream();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var line = executor.submit(() -> reader(stream).readLine());

      stream.feed("late line\n");

      assertEquals("late line", Await.value("the line fed after the read began", line));
    }
  }

  @Test
  void aBlockedReadSeesTheEndSignalledLater() {
    var stream = new FeedableInputStream();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = executor.submit(() -> stream.read());

      stream.end();

      assertEquals(-1, Await.value("the end of the stream", read));
    }
  }

  @Test
  void failureIsThrownAfterTheFedBytesAndOnEveryLaterRead() throws IOException {
    var stream = new FeedableInputStream();
    var cause = new IOException("connection reset");
    stream.feed("x");
    stream.fail(cause);

    assertEquals('x', stream.read());
    assertSame(cause, assertThrows(IOException.class, stream::read));
    assertSame(cause, assertThrows(IOException.class, stream::read));
  }

  @Test
  void aBlockedReadIsFailedLater() {
    var stream = new FeedableInputStream();
    var cause = new IOException("connection reset");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = executor.submit(() -> stream.read());

      stream.fail(cause);

      assertSame(cause, Await.failure("the failed read", read));
    }
  }

  @Test
  void feedingAfterTheEndOrAFailureIsRejected() {
    var ended = new FeedableInputStream();
    ended.end();
    var failed = new FeedableInputStream();
    failed.fail(new IOException("gone"));

    assertThrows(IllegalStateException.class, () -> ended.feed("more"));
    assertThrows(IllegalStateException.class, () -> failed.feed("more"));
  }

  @Test
  void failRequiresACause() {
    assertThrows(NullPointerException.class, () -> new FeedableInputStream().fail(null));
  }

  @Test
  void closeReleasesABlockedRead() {
    var stream = new FeedableInputStream();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = executor.submit(() -> stream.read());

      stream.close();

      var failure = Await.failure("the read released by close", read);
      assertInstanceOf(IOException.class, failure);
      assertEquals("Stream closed", failure.getMessage());
    }
  }

  @Test
  void aClosedStreamRefusesToReadEvenWhatWasFed() {
    var stream = new FeedableInputStream();
    stream.feed("unread");
    stream.close();

    assertEquals("Stream closed", assertThrows(IOException.class, stream::read).getMessage());
  }

  @Test
  void anInterruptReleasesABlockedReadAndStaysSet() {
    var stream = new FeedableInputStream();
    var interruptStillSet = new AtomicBoolean();
    var outcome = new CompletableFuture<Integer>();
    var reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    outcome.complete(stream.read());
                  } catch (IOException e) {
                    interruptStillSet.set(Thread.currentThread().isInterrupted());
                    outcome.completeExceptionally(e);
                  }
                });
    stream.awaitBlockedRead();

    reader.interrupt();

    assertInstanceOf(InterruptedIOException.class, Await.failure("the interrupted read", outcome));
    assertTrue(interruptStillSet.get());
  }

  private static BufferedReader reader(FeedableInputStream stream) {
    return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
  }
}
