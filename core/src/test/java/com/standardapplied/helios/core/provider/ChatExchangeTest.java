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
import com.standardapplied.helios.core.model.TransientStreamException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatExchangeTest {

  /** The exception a test provider throws, so a pass-through is told apart from a wrap. */
  static final class AcmeException extends ProviderException {
    AcmeException(String message, int statusCode, Throwable cause) {
      super(message, statusCode, cause);
    }
  }

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
  void streamHandsBackTheOpenedStream() {
    var stream = new Recorded(new StreamEvent.Done(DONE));

    assertSame(stream, exchange(request -> stream).stream("req"));
  }

  @Test
  void streamTurnsTheProvidersExceptionIntoItsOneErrorEvent() {
    var own = new AcmeException("API error (status 400): bad", 400, null);

    var error =
        onlyError(
            exchange(
                request -> {
                  throw own;
                })
                .stream("req"));

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
        AcmeException::new);
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
