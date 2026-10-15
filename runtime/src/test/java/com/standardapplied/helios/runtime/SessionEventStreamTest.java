/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.sse.SseSink;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link SessionEventStream} as a subscriber, against a recording sink. */
final class SessionEventStreamTest {

  private static final QueryEvent EVENT =
      new QueryEvent.AssistantText("sess-1", 0L, Instant.EPOCH, "hello");

  @Test
  void subscribingRequestsEverythingAndEmitsReady() {
    var sink = new RecordingSink(null);
    var subscription = new RecordingSubscription();

    new SessionEventStream("sess-1", sink, new ObjectMapper()).onSubscribe(subscription);

    assertEquals(Long.MAX_VALUE, subscription.requested.get());
    assertEquals(List.of("Ready:{}"), sink.emitted);
  }

  @Test
  void aFailedReadyEmitIsIgnored() {
    var subscription = new RecordingSubscription();
    var stream =
        new SessionEventStream("sess-1", new RecordingSink(new IllegalStateException("x")), null);

    stream.onSubscribe(subscription);

    assertEquals(Long.MAX_VALUE, subscription.requested.get());
    assertFalse(subscription.cancelled.get());
  }

  @Test
  void eachEventIsEmittedNamedByItsSubtype() {
    var sink = new RecordingSink(null);
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());

    stream.onNext(EVENT);

    assertEquals(
        List.of(
            "AssistantText:{\"sessionId\":\"sess-1\",\"turnIndex\":0,"
                + "\"timestamp\":\"1970-01-01T00:00:00Z\",\"text\":\"hello\"}"),
        sink.emitted);
  }

  @Test
  void aToolResultIsEmittedWithItsRedactionsAndNoData() {
    var sink = new RecordingSink(null);
    var stream =
        new SessionEventStream("sess-1", sink, ToolOutcomeSerializer.addTo(new ObjectMapper()));

    stream.onNext(
        new QueryEvent.ToolResult(
            "sess-1",
            0L,
            Instant.EPOCH,
            new ToolCall("c1", "leak", Map.of()),
            ToolResult.success("token=<redacted:API_KEY>"),
            Map.of("API_KEY", 1)));

    assertEquals(
        List.of(
            "ToolResult:{\"sessionId\":\"sess-1\",\"turnIndex\":0,"
                + "\"timestamp\":\"1970-01-01T00:00:00Z\","
                + "\"call\":{\"id\":\"c1\",\"name\":\"leak\",\"arguments\":{}},"
                + "\"result\":{\"outcome\":{\"kind\":\"Succeeded\"},"
                + "\"output\":\"token=<redacted:API_KEY>\","
                + "\"attachments\":[]},"
                + "\"redactions\":{\"API_KEY\":1}}"),
        sink.emitted);
  }

  @Test
  void aTimedOutCallStreamsTheSameThroughEitherMapperTheRuntimeIsGiven() {
    var event =
        new QueryEvent.ToolResult(
            "sess-1",
            0L,
            Instant.EPOCH,
            new ToolCall("c1", "slow", Map.of()),
            ToolResult.timedOut("slow", Duration.ofMinutes(1)),
            Map.of());
    var expected =
        "ToolResult:{\"sessionId\":\"sess-1\",\"turnIndex\":0,"
            + "\"timestamp\":\"1970-01-01T00:00:00Z\","
            + "\"call\":{\"id\":\"c1\",\"name\":\"slow\",\"arguments\":{}},"
            + "\"result\":{\"outcome\":{\"kind\":\"TimedOut\",\"limit\":\"PT1M\"},"
            + "\"output\":\"tool 'slow' timed out after PT1M\",\"attachments\":[]},"
            + "\"redactions\":{}}";

    for (var mapper : List.of(new ObjectMapper(), JsonMapper.builder().build())) {
      var sink = new RecordingSink(null);
      new SessionEventStream("sess-1", sink, ToolOutcomeSerializer.addTo(mapper)).onNext(event);
      assertEquals(List.of(expected), sink.emitted);
    }
  }

  @Test
  void aFailedEmitCancelsTheSubscriptionAndEndsTheStream() {
    var sink = new RecordingSink(new IllegalStateException("sink broke"));
    var subscription = new RecordingSubscription();
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());
    stream.onSubscribe(subscription);

    stream.onNext(EVENT);

    assertTrue(subscription.cancelled.get());
    awaitEnd(stream);
    assertTrue(sink.closed.get());
  }

  @Test
  void aDisconnectCancelsTheSubscriptionAndEndsTheStream() {
    var sink = new RecordingSink(new CloseConnectionException("peer closed"));
    var subscription = new RecordingSubscription();
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());
    stream.onSubscribe(subscription);

    stream.onNext(EVENT);

    assertTrue(subscription.cancelled.get());
    awaitEnd(stream);
  }

  @Test
  void aFailedEmitBeforeSubscriptionEndsTheStream() {
    var stream =
        new SessionEventStream(
            "sess-1", new RecordingSink(new IllegalStateException("x")), new ObjectMapper());

    stream.onNext(EVENT);

    awaitEnd(stream);
  }

  @Test
  void endingCancelsTheSubscriptionAndEndsTheStreamWithoutAFurtherEvent() {
    var sink = new RecordingSink(null);
    var subscription = new RecordingSubscription();
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());
    stream.onSubscribe(subscription);

    stream.end();

    assertTrue(subscription.cancelled.get());
    awaitEnd(stream);
    assertEquals(List.of("Ready:{}"), sink.emitted);
    assertTrue(sink.closed.get());
  }

  @Test
  void anEventStillSerializingWhenTheStreamEndsIsNotWritten() {
    var sink = new RecordingSink(null);
    var stream = new AtomicReference<SessionEventStream>();
    var endingMidSerialization =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            stream.get().end();
            return super.writeValueAsString(value);
          }
        };
    stream.set(new SessionEventStream("sess-1", sink, endingMidSerialization));
    stream.get().onSubscribe(new RecordingSubscription());

    stream.get().onNext(EVENT);

    assertEquals(List.of("Ready:{}"), sink.emitted);
    awaitEnd(stream.get());
  }

  @Test
  void anEventArrivingAfterTheEndIsNeitherSerializedNorWritten() {
    var sink = new RecordingSink(null);
    var serialized = new AtomicBoolean();
    var recording =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            serialized.set(true);
            return super.writeValueAsString(value);
          }
        };
    var stream = new SessionEventStream("sess-1", sink, recording);
    stream.end();

    stream.onNext(EVENT);

    assertFalse(serialized.get());
    assertTrue(sink.emitted.isEmpty());
    awaitEnd(stream);
  }

  @Test
  void aSubscriptionArrivingAfterTheEndIsCancelledAndNothingIsRequested() {
    var sink = new RecordingSink(null);
    var subscription = new RecordingSubscription();
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());
    stream.end();

    stream.onSubscribe(subscription);

    assertTrue(subscription.cancelled.get());
    assertEquals(0L, subscription.requested.get());
    assertTrue(sink.emitted.isEmpty());
    awaitEnd(stream);
  }

  @Test
  void aStreamEndingAsItSubscribesWritesNoReady() {
    var sink = new RecordingSink(null);
    var stream = new AtomicReference<SessionEventStream>();
    var endingOnRequest =
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            stream.get().end();
          }

          @Override
          public void cancel() {}
        };
    stream.set(new SessionEventStream("sess-1", sink, new ObjectMapper()));

    stream.get().onSubscribe(endingOnRequest);

    assertTrue(sink.emitted.isEmpty());
    awaitEnd(stream.get());
  }

  @Test
  void aPublisherErrorEndsTheStream() {
    var sink = new RecordingSink(null);
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());

    stream.onError(new IllegalStateException("publisher broke"));

    awaitEnd(stream);
    assertTrue(sink.closed.get());
  }

  @Test
  void completionEndsTheStream() {
    var sink = new RecordingSink(null);
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());

    stream.onComplete();

    awaitEnd(stream);
    assertTrue(sink.closed.get());
  }

  @Test
  void aSinkThatFailsToCloseStillEndsTheStream() {
    var sink = new RecordingSink(null);
    sink.failClose = true;
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());

    stream.onComplete();

    awaitEnd(stream);
  }

  @Test
  void anInterruptedWaitClosesTheSinkAndKeepsTheInterrupt() {
    var sink = new RecordingSink(null);
    var stream = new SessionEventStream("sess-1", sink, new ObjectMapper());

    Thread.currentThread().interrupt();
    stream.awaitEndThenClose();

    assertTrue(Thread.interrupted());
    assertTrue(sink.closed.get());
  }

  private static void awaitEnd(SessionEventStream stream) {
    Await.value(
        "the end of the event stream", CompletableFuture.runAsync(stream::awaitEndThenClose));
  }

  private static final class RecordingSink implements SseSink {

    private final RuntimeException emitFailure;
    private final List<String> emitted = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean failClose;

    RecordingSink(RuntimeException emitFailure) {
      this.emitFailure = emitFailure;
    }

    @Override
    public SseSink emit(SseEvent event) {
      if (emitFailure != null) {
        throw emitFailure;
      }
      emitted.add(event.name().orElseThrow() + ":" + event.data());
      return this;
    }

    @Override
    public void close() {
      closed.set(true);
      if (failClose) {
        throw new IllegalStateException("already closed");
      }
    }
  }

  private static final class RecordingSubscription implements Flow.Subscription {

    private final AtomicLong requested = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void request(long n) {
      requested.set(n);
    }

    @Override
    public void cancel() {
      cancelled.set(true);
    }
  }
}
