/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.StreamEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Server-sent events for a provider stream test: the wire text of one event, a response body
 * carrying such text, and every {@link StreamEvent} a reader yields from a body, or only those that
 * carry content.
 */
public final class SseEvents {

  /**
   * An idle timeout no test stream reaches, longer than {@link Await#HANG_GUARD} so that a stream
   * that stalls fails the test's wait rather than ending the read.
   */
  public static final Duration NEVER_IDLE = Duration.ofMinutes(10);

  private SseEvents() {}

  /** One unnamed event: {@code data: <payload>} and the blank line that ends it. */
  public static String data(String payload) {
    return "data: " + payload + "\n\n";
  }

  /** One event named {@code name}: an {@code event:} line before its {@code data:} line. */
  public static String named(String name, String payload) {
    return "event: " + name + "\n" + data(payload);
  }

  /** A response body holding the UTF-8 bytes of {@code sse}. */
  public static InputStream body(String sse) {
    return new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
  }

  /** Every event {@code events} yields until it ends; closes it. */
  public static List<StreamEvent> drain(CloseableIterator<StreamEvent> events) {
    try (events) {
      var drained = new ArrayList<StreamEvent>();
      while (events.hasNext()) {
        drained.add(events.next());
      }
      return drained;
    }
  }

  /**
   * Every event {@code events} yields until it ends except its {@link StreamEvent.KeepAlive}s: what
   * a provider's parser makes of the stream's content; closes it.
   */
  public static List<StreamEvent> drainContent(CloseableIterator<StreamEvent> events) {
    return drain(events).stream()
        .filter(event -> !(event instanceof StreamEvent.KeepAlive))
        .toList();
  }

  /**
   * What a reader over a body that never delivers throws when the thread reading it is interrupted
   * once blocked on the body. The interrupt must stay set on that thread.
   *
   * @param reader opens the reader over the body
   * @return what the interrupted read threw
   */
  public static Throwable readInterrupted(
      Function<InputStream, CloseableIterator<StreamEvent>> reader) {
    var neverDelivers = new FeedableInputStream();
    var interruptKept = new AtomicBoolean();
    var read =
        new FutureTask<>(
            () -> {
              try {
                return drain(reader.apply(neverDelivers));
              } finally {
                interruptKept.set(Thread.currentThread().isInterrupted());
              }
            });
    var consumer = Thread.ofPlatform().start(read);
    neverDelivers.awaitBlockedRead();
    consumer.interrupt();
    var thrown = Await.failure("the interrupted read", read);
    if (!interruptKept.get()) {
      throw new AssertionError("the interrupt was cleared", thrown);
    }
    return thrown;
  }
}
