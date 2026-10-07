/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static com.standardapplied.helios.gemini.GeminiSse.HELLO_DELTA;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_COMPLETED;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_START;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_STOP;
import static com.standardapplied.helios.gemini.GeminiSse.TEXT_FLOW;
import static com.standardapplied.helios.gemini.GeminiSse.drain;
import static com.standardapplied.helios.gemini.GeminiSse.reader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.test.FailingInputStream;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.SseEvents;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Reading a Gemini stream: framing, read failures, idle timeout and closing. */
class StreamingIteratorTest {

  private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofMillis(200);

  @Test
  void textDeltaEvents() {
    var events = drain(TEXT_FLOW);

    assertEquals(2, events.size());
    assertEquals("Hello", assertInstanceOf(StreamEvent.TextDelta.class, events.get(0)).text());
    var done = assertInstanceOf(StreamEvent.Done.class, events.get(1));
    assertEquals("Hello", done.response().content());
    assertEquals(FinishReason.STOP, done.response().finishReason());
    assertEquals("int_xyz", done.response().metadata().get(ContinuationPoint.INTERACTION_ID_KEY));
  }

  @Test
  void emptyAndDoneDataLinesAreSkipped() {
    var sse = "data: \n\ndata: [DONE]\n\n" + TEXT_FLOW;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
  }

  @Test
  void nonDataLinesAreIgnored() {
    var sse = "event: step.delta\n" + TEXT_FLOW;
    var events = drain(sse);
    assertEquals(2, events.size());
  }

  @Test
  void malformedJsonEmitsErrorEvent() {
    var sse = "data: {not valid json}\n\n" + INTERACTION_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.Error.class, events.get(0));
    assertInstanceOf(StreamEvent.Done.class, events.get(1));
  }

  @Test
  void idleTimeoutEmitsErrorEvent() {
    var neverDelivers = new FeedableInputStream();

    try (var iterator =
        new SseReader(
            neverDelivers,
            SHORT_IDLE_TIMEOUT,
            new GeminiStreamParser(true, GeminiEndpoint.DEFAULT_API_VERSION),
            GeminiException::new)) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("idle timeout"));
      assertInstanceOf(GeminiException.class, error.cause());
      assertTrue(((GeminiException) error.cause()).isRetryable());
    }
  }

  @Test
  void closeIsIdempotent() {
    var iterator = reader(SseEvents.body(TEXT_FLOW));
    iterator.close();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @Test
  void closeAfterPartialConsumption() {
    var sse =
        MODEL_OUTPUT_START + HELLO_DELTA + HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    var iterator = reader(SseEvents.body(sse));
    assertTrue(iterator.hasNext());
    iterator.next();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @Test
  void nextWithoutHasNextStillReadsAhead() {
    // Exercises StreamingIterator.next() when nextEvent is not pre-buffered by hasNext().
    try (var iterator = reader(SseEvents.body(TEXT_FLOW))) {
      var first = iterator.next();
      assertInstanceOf(StreamEvent.TextDelta.class, first);
      var second = iterator.next();
      assertInstanceOf(StreamEvent.Done.class, second);
      assertFalse(iterator.hasNext());
    }
  }

  @Test
  void hasNextIsIdempotentAcrossRepeatedCalls() {
    try (var iterator = reader(SseEvents.body(TEXT_FLOW))) {
      assertTrue(iterator.hasNext());
      // Second call must short-circuit through the already-buffered nextEvent path.
      assertTrue(iterator.hasNext());
      iterator.next();
      iterator.next();
      // After Done: hasNext must return false even when called again.
      assertFalse(iterator.hasNext());
      assertFalse(iterator.hasNext());
    }
  }

  @Test
  void closeSwallowsIoExceptionFromUnderlyingStream() {
    var iterator = reader(FailingInputStream.onClose(new IOException("close failure")));
    // Drain so the iterator reaches Done and then closes itself.
    while (iterator.hasNext()) {
      iterator.next();
    }
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @Test
  void ioExceptionFromReaderEmitsErrorEvent() {
    var error = firstError(FailingInputStream.onRead(new IOException("Simulated I/O failure")));

    assertTrue(error.message().contains("Stream read error"));
  }

  @Test
  void runtimeExceptionFromReaderEmitsErrorEvent() {
    var error = firstError(FailingInputStream.onRead(new RuntimeException("Unexpected failure")));

    assertTrue(error.message().contains("Stream read error"));
  }

  @Test
  void interruptedThreadEmitsErrorEvent() {
    var events = SseEvents.drainInterrupted(GeminiSse::reader);

    assertFalse(events.isEmpty());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
  }

  private static StreamEvent.Error firstError(InputStream body) {
    return assertInstanceOf(StreamEvent.Error.class, SseEvents.drain(reader(body)).getFirst());
  }
}
