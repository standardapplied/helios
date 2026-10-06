/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.StubHttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SseReaderTest {

  private static final Duration NEVER_IDLE = Duration.ofMinutes(10);

  /** Echoes each payload as text, ends the stream on "stop" and completes with "complete". */
  private static class EchoParser implements SseReader.Parser {
    private final List<String> payloads = new ArrayList<>();
    private boolean finished;

    @Override
    public StreamEvent parse(String payload) {
      payloads.add(payload);
      finished = payload.equals("stop");
      return payload.equals("silent") ? null : new StreamEvent.TextDelta(payload);
    }

    @Override
    public boolean finished() {
      return finished;
    }

    @Override
    public StreamEvent complete() {
      return new StreamEvent.TextDelta("complete");
    }
  }

  private static final class TrackingStream extends ByteArrayInputStream {
    private boolean closed;

    TrackingStream(String text) {
      super(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
      closed = true;
      throw new IOException("close failed");
    }
  }

  @Test
  void yieldsEachDataPayloadAndCompletesAtTheEndOfTheBody() {
    var body =
        new TrackingStream(
            "event: x\ndata: one\n\ndata:    \n\ndata: [DONE]\nid: 7\ndata:  two  \ndata: silent\n");
    var parser = new EchoParser();
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    assertEquals(List.of("one", "two", "complete"), texts(reader));
    assertEquals(List.of("one", "two", "silent"), parser.payloads);
    assertTrue(body.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void aFinishingPayloadEndsTheStreamAndClosesTheBody() {
    var body = new TrackingStream("data: one\ndata: stop\ndata: never\n");
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    assertEquals(List.of("one", "stop"), texts(reader));
    assertTrue(body.closed);
  }

  @Test
  void hasNextAsksOnceUntilTheEventIsTaken() {
    var parser = new EchoParser();
    var reader =
        new SseReader(stream("data: one\ndata: two\n"), NEVER_IDLE, parser, ProviderException::new);

    assertTrue(reader.hasNext());
    assertTrue(reader.hasNext());

    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    assertEquals(List.of("one"), parser.payloads);
  }

  @Test
  void aStreamWithNothingToCompleteWithEndsWithoutAnEvent() {
    var parser =
        new EchoParser() {
          @Override
          public StreamEvent complete() {
            return null;
          }
        };
    var reader = new SseReader(stream(""), NEVER_IDLE, parser, ProviderException::new);

    assertFalse(reader.hasNext());
  }

  @Test
  void nextWithoutHasNextReadsTheNextEvent() {
    var reader =
        new SseReader(stream("data: one\n"), NEVER_IDLE, new EchoParser(), ProviderException::new);

    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    assertEquals(new StreamEvent.TextDelta("complete"), reader.next());
  }

  @Test
  void aLineThatDoesNotArriveInTimeFailsWithTheProvidersException() {
    var body = new FeedableInputStream();
    try (var reader =
        new SseReader(
            body,
            Duration.ofMillis(50),
            new EchoParser(),
            (message, status, cause) -> new ProviderException("acme: " + message, status, cause))) {

      var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

      assertEquals("acme: Stream idle timeout: no data received for 0s", error.message());
      assertEquals(
          error.message(), assertInstanceOf(ProviderException.class, error.cause()).getMessage());
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void aReadFailureIsATerminalStreamReadError() {
    var failure = new IOException("connection reset");
    var reader =
        new SseReader(failing(failure), NEVER_IDLE, new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream read error", error.message());
    assertSame(failure, error.cause());
    assertFalse(reader.hasNext());
  }

  @Test
  void anUncheckedReadFailureIsWrappedAsAReadFailure() {
    var failure = new IllegalStateException("decoder broke");
    var reader =
        new SseReader(failing(failure), NEVER_IDLE, new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    var cause = assertInstanceOf(IOException.class, error.cause());
    assertEquals("Stream read failed", cause.getMessage());
    assertSame(failure, cause.getCause());
  }

  @Test
  void anInterruptWhileWaitingForALineIsAReadFailureAndStaysSet() {
    try (var reader =
        new SseReader(
            new FeedableInputStream(), NEVER_IDLE, new EchoParser(), ProviderException::new)) {
      Thread.currentThread().interrupt();

      var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

      assertTrue(Thread.interrupted());
      assertEquals("Stream read interrupted", error.cause().getMessage());
    }
  }

  @Test
  void openStreamsATwoHundredResponse() throws Exception {
    try (var server = server(200, "data: hello\n");
        var client = HttpClient.newHttpClient();
        var reader =
            SseReader.open(
                client, get(server), NEVER_IDLE, new EchoParser(), ProviderException::new)) {

      assertEquals(new StreamEvent.TextDelta("hello"), reader.next());
    }
  }

  @Test
  void openFailsAnotherStatusWithTheProvidersExceptionAndTheBody() throws Exception {
    try (var server = server(529, "{\"error\":\"overloaded\"}");
        var client = HttpClient.newHttpClient()) {

      var failure =
          assertThrows(
              ProviderException.class,
              () ->
                  SseReader.open(
                      client, get(server), NEVER_IDLE, new EchoParser(), ProviderException::new));

      assertEquals("API error (status 529): {\"error\":\"overloaded\"}", failure.getMessage());
      assertEquals(529, failure.statusCode());
      assertNull(failure.getCause());
    }
  }

  private static List<String> texts(SseReader reader) {
    var texts = new ArrayList<String>();
    while (reader.hasNext()) {
      texts.add(((StreamEvent.TextDelta) reader.next()).text());
    }
    return texts;
  }

  private static InputStream stream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private static InputStream failing(Exception failure) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        if (failure instanceof IOException io) {
          throw io;
        }
        throw (RuntimeException) failure;
      }
    };
  }

  private static StubHttpServer server(int status, String body) {
    return StubHttpServer.start(
        InetAddress.getLoopbackAddress(),
        0,
        request -> new StubHttpServer.Reply(status, Map.of(), body));
  }

  private static HttpRequest get(StubHttpServer server) {
    return HttpRequest.newBuilder(server.uri()).build();
  }
}
