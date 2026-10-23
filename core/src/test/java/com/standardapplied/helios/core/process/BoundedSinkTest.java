/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.process;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.test.Await;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class BoundedSinkTest {

  /** A child's pipe fails once the child is killed; what arrived before stays captured. */
  @Test
  void streamFailingMidDrainKeepsWhatArrived() {
    var sink = new BoundedSink(100);

    sink.drain(new FailingAfter("partial".getBytes(StandardCharsets.UTF_8)));

    assertArrayEquals(
        "partial".getBytes(StandardCharsets.UTF_8), sink.bytes(new SecretRegistry().redactor()));
    assertFalse(sink.truncated());
  }

  /**
   * When the child exits, the JDK cuts its pipe under the stream's monitor, though a backgrounded
   * descendant may still be writing to it. Here the drain is held up in the sink between two reads,
   * and a thread that wants the stream's monitor ({@link ByteArrayInputStream#available()} takes
   * it) still waits for the drain.
   */
  @Test
  void theDrainHoldsTheStreamsMonitorBetweenReads() {
    var sink = new BoundedSink(100);
    var stream = new ByteArrayInputStream(new byte[] {'x'});
    Thread drain;
    Thread contender;
    synchronized (sink) {
      drain = Thread.ofPlatform().start(() -> sink.drain(stream));
      Await.until("the drain to wait for the sink", () -> drain.getState() == Thread.State.BLOCKED);
      contender = Thread.ofPlatform().start(stream::available);
      Await.until(
          "the contender to block or finish",
          () -> contender.getState() == Thread.State.BLOCKED || !contender.isAlive());

      assertEquals(Thread.State.BLOCKED, contender.getState());
    }
    Await.termination("the drain", drain);
    Await.termination("the contender", contender);
    assertArrayEquals(new byte[] {'x'}, sink.bytes(new SecretRegistry().redactor()));
  }

  /**
   * A reaping that ran before the wait keeps its interrupt for the caller; an ended drain gives the
   * wait nothing to block on, so only a check on entry passes the interrupt on.
   */
  @Test
  void anInterruptPendingOnEntryThrowsEvenWhenTheDrainHasEnded() {
    var drainer = new CompletableFuture<Thread>();
    var sink = BoundedSink.draining(new EndingAt(drainer), 100);
    Await.termination("the drain", Await.value("the drain to start", drainer));

    Thread.currentThread().interrupt();

    assertThrows(InterruptedException.class, () -> sink.awaitEnd(0));
    assertFalse(sink.truncated());
  }

  /** An empty stream that reports the thread reading it. */
  private static final class EndingAt extends InputStream {
    private final CompletableFuture<Thread> reader;

    EndingAt(CompletableFuture<Thread> reader) {
      this.reader = reader;
    }

    @Override
    public int read() {
      reader.complete(Thread.currentThread());
      return -1;
    }
  }

  private static final class FailingAfter extends InputStream {
    private final byte[] data;
    private int position;

    FailingAfter(byte[] data) {
      this.data = data;
    }

    @Override
    public int read() throws IOException {
      if (position == data.length) {
        throw new IOException("Stream closed");
      }
      return data[position++];
    }
  }
}
