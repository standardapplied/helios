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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Server-sent events for a provider stream test: the wire text of one event, a response body
 * carrying such text, and every {@link StreamEvent} a reader yields from a body.
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
   * Every event a reader yields from a body that never delivers, drained on its own thread, which
   * is interrupted once the reader is blocked reading the body.
   *
   * @param reader opens the reader over the body
   * @return the events yielded before the interrupted thread ended
   */
  public static List<StreamEvent> drainInterrupted(
      Function<InputStream, CloseableIterator<StreamEvent>> reader) {
    var neverDelivers = new FeedableInputStream();
    var events = new CopyOnWriteArrayList<StreamEvent>();
    var consumer =
        Thread.ofPlatform().start(() -> events.addAll(drain(reader.apply(neverDelivers))));
    neverDelivers.awaitBlockedRead();
    consumer.interrupt();
    Await.termination("the interrupted consumer thread", consumer);
    return List.copyOf(events);
  }
}
