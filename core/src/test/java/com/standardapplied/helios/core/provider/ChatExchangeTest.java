/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatExchangeTest {

  public record Answer(String summary) {}

  /** The exception a test provider throws, so a pass-through is told apart from a wrap. */
  static final class AcmeException extends ProviderException {
    AcmeException(String message, int statusCode, Throwable cause) {
      super(message, statusCode, cause);
    }
  }

  private static final StructuredContentParser.JsonAdapter ANSWERS =
      new StructuredContentParser.JsonAdapter() {
        @Override
        public Map<String, Object> toMap(String json) {
          return Map.of("summary", json.replaceAll("\\W", "").replace("summary", ""));
        }

        @Override
        public <T> T fromMap(Map<String, Object> map, Class<T> type) {
          return type.cast(new Answer((String) map.get("summary")));
        }
      };

  private static final Response<Void> DONE =
      Response.newBuilder()
          .withContent("{\"summary\":\"ok\"}")
          .withFinishReason(FinishReason.STOP)
          .build();

  private final List<String> opened = new ArrayList<>();

  @Test
  void chatDrainsTheStreamToItsResponseAndClosesIt() {
    var stream = new Recorded(new StreamEvent.TextDelta("ok"), new StreamEvent.Done(DONE));

    assertSame(DONE, exchange(request -> stream).chat("req"));
    assertTrue(stream.closed);
    assertEquals(List.of("req"), opened);
  }

  @Test
  void chatRethrowsTheProvidersOwnExceptionFromAnErrorEvent() {
    var own = new AcmeException("boom", 503, null);

    var thrown = assertThrows(AcmeException.class, () -> chatOn(new StreamEvent.Error("x", own)));

    assertSame(own, thrown);
  }

  @Test
  void chatRethrowsATransientFailureFromAnErrorEvent() {
    var transientFailure = new TransientStreamException("overloaded", null, "acme");

    var thrown =
        assertThrows(
            TransientStreamException.class,
            () -> chatOn(new StreamEvent.Error("x", transientFailure)));

    assertSame(transientFailure, thrown);
  }

  @Test
  void chatTurnsAMidStreamReadFailureIntoARetryableFailureNamingTheProvider() {
    var reset = new IOException("reset");

    var thrown =
        assertThrows(
            TransientStreamException.class,
            () -> chatOn(new StreamEvent.Error("Stream read error", reset)));

    assertEquals("Stream read error", thrown.getMessage());
    assertSame(reset, thrown.getCause());
    assertEquals("acme", thrown.providerName());
  }

  @Test
  void chatWrapsAnyOtherErrorInTheProvidersException() {
    var parse = new IllegalArgumentException("bad json");

    var wrapped =
        assertThrows(
            AcmeException.class, () -> chatOn(new StreamEvent.Error("Failed to parse", parse)));
    var bare =
        assertThrows(AcmeException.class, () -> chatOn(new StreamEvent.Error("API error", null)));

    assertEquals("Failed to parse", wrapped.getMessage());
    assertSame(parse, wrapped.getCause());
    assertEquals("API error", bare.getMessage());
    assertNull(bare.getCause());
  }

  @Test
  void chatFailsAStreamThatEndsWithoutCompleting() {
    var thrown = assertThrows(AcmeException.class, () -> chatOn(new StreamEvent.TextDelta("x")));

    assertEquals("Stream ended without completion event", thrown.getMessage());
  }

  @Test
  void chatTurnsAFailureToSendIntoARetryableFailureNamingTheApi() {
    var refused = new IOException("refused");

    var thrown =
        assertThrows(
            TransientStreamException.class,
            () ->
                exchange(
                        request -> {
                          throw refused;
                        })
                    .chat("req"));

    assertEquals("Failed to communicate with Acme API", thrown.getMessage());
    assertSame(refused, thrown.getCause());
    assertEquals("acme", thrown.providerName());
  }

  @Test
  void chatRestoresAnInterruptAndFailsWithTheProvidersException() {
    var interrupt = new InterruptedException();

    var thrown =
        assertThrows(
            AcmeException.class,
            () ->
                exchange(
                        request -> {
                          throw interrupt;
                        })
                    .chat("req"));

    assertTrue(Thread.interrupted());
    assertEquals("Request interrupted", thrown.getMessage());
    assertSame(interrupt, thrown.getCause());
  }

  @Test
  void structuredParsesTheContentOfATurnWithoutToolCalls() {
    var response = exchange(request -> null).structured(DONE, OutputSchema.of(Answer.class));

    assertEquals(new Answer("ok"), response.parsed());
    assertEquals(DONE.content(), response.content());
    assertEquals(FinishReason.STOP, response.finishReason());
  }

  @Test
  void structuredLeavesATurnThatCalledToolsUnparsed() {
    var call = ToolCall.newBuilder().withId("c").withName("t").build();
    var toolTurn =
        Response.newBuilder()
            .withContent("Let me look that up.")
            .withToolCalls(List.of(call))
            .withFinishReason(FinishReason.TOOL_CALLS)
            .build();

    var response = exchange(request -> null).structured(toolTurn, OutputSchema.of(Answer.class));

    assertNull(response.parsed());
    assertEquals(List.of(call), response.toolCalls());
  }

  @Test
  void streamHandsBackTheOpenedStream() {
    var stream = new Recorded(new StreamEvent.Done(DONE));

    assertSame(stream, exchange(request -> stream).stream("req"));
  }

  @Test
  void streamTurnsTheProvidersExceptionIntoItsOneErrorEvent() {
    var own = new AcmeException("API error (status 400): bad", 400, null);

    var error =
        onlyError(
            exchange(request -> null).stream(
                request -> {
                  throw own;
                },
                "req"));

    assertEquals(own.getMessage(), error.message());
    assertSame(own, error.cause());
  }

  @Test
  void streamTurnsAFailureToConnectIntoItsOneErrorEvent() {
    var refused = new IOException("refused");

    var error =
        onlyError(
            exchange(
                request -> {
                  throw refused;
                })
                .stream("req"));

    assertEquals("Failed to connect", error.message());
    assertSame(refused, error.cause());
  }

  @Test
  void streamRestoresAnInterruptAndReportsItAsItsOneErrorEvent() {
    var interrupt = new InterruptedException();

    var error =
        onlyError(
            exchange(
                request -> {
                  throw interrupt;
                })
                .stream("req"));

    assertTrue(Thread.interrupted());
    assertEquals("Request interrupted", error.message());
    assertSame(interrupt, error.cause());
  }

  private Response<Void> chatOn(StreamEvent... events) {
    return exchange(request -> new Recorded(events)).chat("req");
  }

  private ChatExchange<String> exchange(ChatExchange.StreamOpener<String> opener) {
    return new ChatExchange<>(
        "acme",
        "Acme API",
        request -> {
          opened.add(request);
          return opener.open(request);
        },
        AcmeException::new,
        ANSWERS,
        RawOutputCapturePolicy.ENABLED);
  }

  private static StreamEvent.Error onlyError(CloseableIterator<StreamEvent> events) {
    var error = assertInstanceOf(StreamEvent.Error.class, events.next());
    assertFalse(events.hasNext());
    return error;
  }

  private static final class Recorded implements CloseableIterator<StreamEvent> {
    private final List<StreamEvent> events;
    private int next;
    private boolean closed;

    Recorded(StreamEvent... events) {
      this.events = List.of(events);
    }

    @Override
    public boolean hasNext() {
      return next < events.size();
    }

    @Override
    public StreamEvent next() {
      return events.get(next++);
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
