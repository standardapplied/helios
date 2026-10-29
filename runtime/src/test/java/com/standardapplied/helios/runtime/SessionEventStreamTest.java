/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link SessionEventStream} reading a hand-fed event stream into a recording sink.
 */
final class SessionEventStreamTest {

  private static final QueryEvent EVENT =
      new QueryEvent.AssistantText("sess-1", "run-1", 0L, Instant.EPOCH, "hello");
  private static final String EVENT_LINE =
      "AssistantText:{\"sessionId\":\"sess-1\",\"runId\":\"run-1\",\"turnIndex\":0,"
          + "\"timestamp\":\"1970-01-01T00:00:00Z\",\"text\":\"hello\"}";

  @Test
  void readyThenEachEventNamedByItsSubtypeUntilTheStreamEndsThenTheSinkCloses() {
    var sink = new RecordingSink();
    var events = FedEventStream.of(EVENT, EVENT);

    forward(sink, new ObjectMapper(), events);

    assertEquals(List.of("Ready:{}", EVENT_LINE, EVENT_LINE), sink.emitted);
    assertTrue(sink.closed.get());
  }

  @Test
  void aToolResultIsEmittedWithItsRedactionsAndNoData() {
    var sink = new RecordingSink();

    forward(
        sink,
        JsonBody.MAPPER,
        new QueryEvent.ToolResult(
            "sess-1",
            "run-1",
            0L,
            Instant.EPOCH,
            new ToolCall("c1", "leak", Map.of()),
            ToolResult.success("token=<redacted:API_KEY>"),
            Map.of("API_KEY", 1)));

    assertEquals(
        List.of(
            "Ready:{}",
            "ToolResult:{\"sessionId\":\"sess-1\",\"runId\":\"run-1\",\"turnIndex\":0,"
                + "\"timestamp\":\"1970-01-01T00:00:00Z\","
                + "\"call\":{\"id\":\"c1\",\"name\":\"leak\",\"arguments\":{}},"
                + "\"result\":{\"outcome\":{\"kind\":\"Succeeded\"},"
                + "\"output\":\"token=<redacted:API_KEY>\","
                + "\"attachments\":[]},"
                + "\"redactions\":{\"API_KEY\":1}}"),
        sink.emitted);
  }

  @Test
  void aTimedOutCallStreamsItsOutcomeKindThroughTheRuntimesMapper() {
    var event =
        new QueryEvent.ToolResult(
            "sess-1",
            "run-1",
            0L,
            Instant.EPOCH,
            new ToolCall("c1", "slow", Map.of()),
            ToolResult.timedOut("slow", Duration.ofMinutes(1)),
            Map.of());
    var expected =
        "ToolResult:{\"sessionId\":\"sess-1\",\"runId\":\"run-1\",\"turnIndex\":0,"
            + "\"timestamp\":\"1970-01-01T00:00:00Z\","
            + "\"call\":{\"id\":\"c1\",\"name\":\"slow\",\"arguments\":{}},"
            + "\"result\":{\"outcome\":{\"kind\":\"TimedOut\",\"limit\":\"PT1M\"},"
            + "\"output\":\"tool 'slow' timed out after PT1M\",\"attachments\":[]},"
            + "\"redactions\":{}}";

    var sink = new RecordingSink();

    forward(sink, JsonBody.MAPPER, event);

    assertEquals(List.of("Ready:{}", expected), sink.emitted);
  }

  @Test
  void aFailedReadyWriteEndsTheStreamWithoutReadingAnEvent() {
    var sink = new RecordingSink(new IllegalStateException("sink broke"), 0);
    var events = FedEventStream.of(EVENT);

    forward(sink, new ObjectMapper(), events);

    assertTrue(sink.emitted.isEmpty());
    assertTrue(sink.closed.get());
    assertTrue(events.iterator().hasNext(), "the event was never taken");
  }

  @Test
  void aFailedEventWriteEndsTheStream() {
    var sink = new RecordingSink(new IllegalStateException("sink broke"), 1);
    var events = new FedEventStream();
    events.push(EVENT);

    forward(sink, new ObjectMapper(), events);

    assertEquals(List.of("Ready:{}"), sink.emitted);
    assertTrue(sink.closed.get());
  }

  @Test
  void aDisconnectEndsTheStream() {
    var sink = new RecordingSink(new CloseConnectionException("peer closed"), 1);
    var events = new FedEventStream();
    events.push(EVENT);

    forward(sink, new ObjectMapper(), events);

    assertEquals(List.of("Ready:{}"), sink.emitted);
    assertTrue(sink.closed.get());
  }

  @Test
  void theIdentityEndingClosesTheStreamWithoutAFurtherEvent() {
    var sink = new RecordingSink();
    var events = new FedEventStream();
    var identityEnded = new CompletableFuture<Void>();
    var forwarding =
        CompletableFuture.runAsync(
            () ->
                new SessionEventStream("sess-1", sink, new ObjectMapper(), identityEnded)
                    .forward(events));
    Await.until("the Ready event", () -> !sink.emitted.isEmpty());

    identityEnded.complete(null);

    Await.value("the stream to end", forwarding);
    assertTrue(events.isClosed());
    assertEquals(List.of("Ready:{}"), sink.emitted);
    assertTrue(sink.closed.get());
  }

  @Test
  void anIdentityAlreadyEndedWritesNothingAndClosesTheStream() {
    var sink = new RecordingSink();
    var events = FedEventStream.of(EVENT);

    new SessionEventStream(
            "sess-1", sink, new ObjectMapper(), CompletableFuture.completedFuture(null))
        .forward(events);

    assertTrue(sink.emitted.isEmpty());
    assertTrue(events.isClosed());
    assertTrue(sink.closed.get());
  }

  @Test
  void anEventStillSerializingWhenTheIdentityEndsIsNotWritten() {
    var sink = new RecordingSink();
    var identityEnded = new CompletableFuture<Void>();
    var endingMidSerialization =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            identityEnded.complete(null);
            return super.writeValueAsString(value);
          }
        };

    new SessionEventStream("sess-1", sink, endingMidSerialization, identityEnded)
        .forward(FedEventStream.of(EVENT));

    assertEquals(List.of("Ready:{}"), sink.emitted);
    assertTrue(sink.closed.get());
  }

  @Test
  void aSinkThatFailsToCloseStillEndsTheStream() {
    var sink = new RecordingSink();
    sink.failClose = true;

    forward(sink, new ObjectMapper(), FedEventStream.of());

    assertEquals(List.of("Ready:{}"), sink.emitted);
    assertTrue(sink.closed.get());
  }

  private static void forward(SseSink sink, ObjectMapper mapper, QueryEvent event) {
    forward(sink, mapper, FedEventStream.of(event));
  }

  private static void forward(SseSink sink, ObjectMapper mapper, FedEventStream events) {
    new SessionEventStream("sess-1", sink, mapper, new CompletableFuture<>()).forward(events);
  }

  private static final class RecordingSink implements SseSink {

    private final RuntimeException emitFailure;
    private final int writesBeforeFailure;
    private final List<String> emitted = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean failClose;

    RecordingSink() {
      this(null, Integer.MAX_VALUE);
    }

    RecordingSink(RuntimeException emitFailure, int writesBeforeFailure) {
      this.emitFailure = emitFailure;
      this.writesBeforeFailure = writesBeforeFailure;
    }

    @Override
    public SseSink emit(SseEvent event) {
      if (emitted.size() == writesBeforeFailure) {
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
}
