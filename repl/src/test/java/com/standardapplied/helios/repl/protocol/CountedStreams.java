/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.protocol;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.LineSink;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The two streams of a {@link ProcessTransport}, played by the test: it feeds what the peer sends
 * and reads the lines the peer receives. Each stream counts its {@code close()} calls, and the
 * input reports the thread that reads it.
 */
final class CountedStreams {

  final FeedableInputStream fromPeer = new FeedableInputStream();
  final LineSink toPeer = new LineSink();
  final AtomicInteger inputCloses = new AtomicInteger();
  final AtomicInteger outputCloses = new AtomicInteger();

  private final CountDownLatch bothClosed = new CountDownLatch(2);
  private final CompletableFuture<Thread> reader = new CompletableFuture<>();

  ProcessTransport transport() {
    return new ProcessTransport(
        new FilterInputStream(fromPeer) {
          @Override
          public int read(byte[] target, int offset, int length) throws IOException {
            reader.complete(Thread.currentThread());
            return super.read(target, offset, length);
          }

          @Override
          public void close() throws IOException {
            inputCloses.incrementAndGet();
            bothClosed.countDown();
            super.close();
          }
        },
        new FilterOutputStream(toPeer) {
          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
          }

          @Override
          public void close() throws IOException {
            outputCloses.incrementAndGet();
            bothClosed.countDown();
            super.close();
          }
        });
  }

  void awaitBothClosed() {
    Await.latch("both streams to be closed", bothClosed);
  }

  /** The thread reading the input, once a read has started. */
  Thread reader() {
    return Await.value("a read of the input", reader);
  }
}
