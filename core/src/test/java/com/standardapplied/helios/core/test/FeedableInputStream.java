/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;

/**
 * An {@link InputStream} the test feeds. A read blocks, without a limit, until the test feeds
 * bytes, ends the stream or fails it, so an instance that is never fed is a stream that never
 * delivers. Fed bytes are delivered before the end or the failure. Like a socket stream, a blocked
 * read is released by {@link #close()} and by an interrupt, each with an {@link IOException}.
 */
public final class FeedableInputStream extends InputStream {

  private final Queue<byte[]> fed = new ArrayDeque<>();
  private final CountDownLatch readBlocked = new CountDownLatch(1);
  private byte[] current = new byte[0];
  private int position;
  private boolean ended;
  private IOException failure;
  private boolean closed;

  /** Makes {@code bytes} readable. */
  public synchronized void feed(byte[] bytes) {
    if (ended || failure != null) {
      throw new IllegalStateException("The stream was already ended or failed");
    }
    fed.add(bytes.clone());
    notifyAll();
  }

  /** Makes the UTF-8 bytes of {@code text} readable. */
  public void feed(String text) {
    feed(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Signals end-of-stream once the bytes fed so far are read. */
  public synchronized void end() {
    ended = true;
    notifyAll();
  }

  /** Makes every read throw {@code cause} once the bytes fed so far are read. */
  public synchronized void fail(IOException cause) {
    failure = Objects.requireNonNull(cause);
    notifyAll();
  }

  /**
   * Waits, through {@link Await}, until a read has found nothing to deliver and is blocked: the
   * event a test needs before it interrupts, cancels or times out the reader.
   */
  public void awaitBlockedRead() {
    Await.latch("a read to block on the stream", readBlocked);
  }

  @Override
  public int read() throws IOException {
    var one = new byte[1];
    return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
  }

  @Override
  public synchronized int read(byte[] target, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, target.length);
    if (length == 0) {
      return 0;
    }
    if (!awaitBytes()) {
      return -1;
    }
    var count = Math.min(length, current.length - position);
    System.arraycopy(current, position, target, offset, count);
    position += count;
    return count;
  }

  @Override
  public synchronized void close() {
    closed = true;
    notifyAll();
  }

  private boolean awaitBytes() throws IOException {
    while (position == current.length) {
      if (closed) {
        throw new IOException("Stream closed");
      }
      var next = fed.poll();
      if (next != null) {
        current = next;
        position = 0;
      } else if (failure != null) {
        throw failure;
      } else if (ended) {
        return false;
      } else {
        awaitFeeding();
      }
    }
    return true;
  }

  private void awaitFeeding() throws InterruptedIOException {
    readBlocked.countDown();
    try {
      wait();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted while waiting to be fed");
    }
  }
}
