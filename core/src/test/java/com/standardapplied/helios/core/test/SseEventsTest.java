/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SseEventsTest {

  private static final SseReader.Parser ECHO =
      new SseReader.Parser() {
        @Override
        public StreamEvent parse(String payload) {
          return new StreamEvent.TextDelta(payload);
        }

        @Override
        public boolean finished() {
          return false;
        }

        @Override
        public StreamEvent complete() {
          return new StreamEvent.TextDelta("end");
        }
      };

  @Test
  void dataIsOnePayloadLineEndedByABlankLine() {
    assertEquals("data: {\"a\":1}\n\n", SseEvents.data("{\"a\":1}"));
  }

  @Test
  void namedPutsTheEventLineBeforeTheData() {
    assertEquals("event: ping\ndata: {}\n\n", SseEvents.named("ping", "{}"));
  }

  @Test
  void bodyHoldsTheUtf8Bytes() throws IOException {
    assertEquals(
        "data: naïve\n\n",
        new String(SseEvents.body("data: naïve\n\n").readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void drainReturnsEveryEventInOrderAndClosesTheIterator() {
    var events =
        List.<StreamEvent>of(new StreamEvent.TextDelta("a"), new StreamEvent.TextDelta("b"));
    var closed = new AtomicBoolean();
    var iterator = events.iterator();

    var drained =
        SseEvents.drain(
            new CloseableIterator<>() {
              @Override
              public boolean hasNext() {
                return iterator.hasNext();
              }

              @Override
              public StreamEvent next() {
                return iterator.next();
              }

              @Override
              public void close() {
                closed.set(true);
              }
            });

    assertEquals(events, drained);
    assertTrue(closed.get());
  }

  @Test
  void drainReadsABodyThroughAReader() {
    var body = SseEvents.body(SseEvents.data("one") + SseEvents.named("x", "two"));

    assertEquals(
        List.of(
            new StreamEvent.TextDelta("one"),
            new StreamEvent.TextDelta("two"),
            new StreamEvent.TextDelta("end")),
        SseEvents.drain(echo(body)));
  }

  @Test
  void drainInterruptedEndsWithTheErrorTheInterruptCauses() {
    var drained = SseEvents.drainInterrupted(SseEventsTest::echo);

    assertFalse(drained.isEmpty());
    assertInstanceOf(StreamEvent.Error.class, drained.getFirst());
  }

  private static SseReader echo(InputStream body) {
    return new SseReader(body, SseEvents.NEVER_IDLE, ECHO, ProviderException::new);
  }
}
