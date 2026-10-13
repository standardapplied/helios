/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.StubHttpServer;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
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

  /**
   * Echoes each payload as text, completes the stream on "stop", fails it on "fail", and reports
   * the payloads so far as its partial response.
   */
  private static class EchoParser implements SseReader.Parser {
    private final List<String> payloads = new ArrayList<>();

    @Override
    public StreamEvent parse(String payload) {
      payloads.add(payload);
      return switch (payload) {
        case "stop" -> new StreamEvent.Done(Response.newBuilder().withContent("done").build());
        case "fail" -> new StreamEvent.Error("failed", null, partial());
        case "silent" -> null;
        default -> new StreamEvent.TextDelta(payload);
      };
    }

    @Override
    public Response<Void> partial() {
      return Response.newBuilder()
          .withContent(String.join("|", payloads))
          .withFinishReason(FinishReason.ERROR)
          .build();
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

  /** Records whether the reader closed the body it owns. */
  private static final class Closing extends FilterInputStream {
    private boolean closed;

    Closing(InputStream body) {
      super(body);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  @Test
  void aBodyClosedMidStreamEndsInAnErrorCarryingWhatArrivedAndIsClosedByTheReader() {
    var feed = new FeedableInputStream();
    feed.feed("data: one\n\n");
    feed.end();
    var body = new Closing(feed);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    var events = drain(reader);

    assertEquals(new StreamEvent.TextDelta("one"), events.getFirst());
    var error = assertInstanceOf(StreamEvent.Error.class, events.getLast());
    assertInstanceOf(EOFException.class, error.cause());
    assertEquals("one", error.partial().content());
    assertTrue(body.closed);
  }

  @Test
  void yieldsTheDataOfEachEventUntilTheParsersTerminalAndClosesTheBody() {
    var body =
        new TrackingStream(
            "event: x\ndata: one\n\n"
                + ": a comment\n\n"
                + "data:    \n\n"
                + "data: [DONE]\n\n"
                + "id: 7\nretry: 10\ndata:  two  \n\n"
                + "data: silent\n\n"
                + "data: stop\n\n"
                + "data: never\n\n");
    var parser = new EchoParser();
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    assertEquals(
        List.of(
            new StreamEvent.TextDelta("one"),
            new StreamEvent.TextDelta("two"),
            new StreamEvent.Done(Response.newBuilder().withContent("done").build())),
        drain(reader));
    assertEquals(List.of("one", "two", "silent", "stop"), parser.payloads);
    assertTrue(body.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void readsDataWithAndWithoutTheSpaceAndJoinsSeveralDataLinesWithANewline() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data:{\"a\":\ndata\ndata:  1}\n\ndata: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    drain(reader);

    assertEquals(List.of("{\"a\":\n\n 1}", "stop"), parser.payloads);
  }

  @Test
  void endsLinesAtCarriageReturnLineFeedOrEither() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data: one\r\n\r\ndata: two\r\rdata: three\n\ndata: stop\r\n\r\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    drain(reader);

    assertEquals(List.of("one", "two", "three", "stop"), parser.payloads);
  }

  @Test
  void aParserErrorEndsTheStreamAndClosesTheBody() {
    var body = new TrackingStream("data: one\n\ndata: fail\n\ndata: never\n\n");
    var parser = new EchoParser();
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    var events = drain(reader);

    assertEquals(new StreamEvent.TextDelta("one"), events.getFirst());
    assertEquals("failed", assertInstanceOf(StreamEvent.Error.class, events.getLast()).message());
    assertEquals(2, events.size());
    assertEquals(List.of("one", "fail"), parser.payloads);
    assertTrue(body.closed);
  }

  @Test
  void aBodyThatEndsBeforeTheTerminalEndsInAnErrorCarryingThePartialResponse() {
    var body = new TrackingStream("data: one\n\ndata: two\n\n");
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    var events = drain(reader);

    assertEquals(
        List.of(new StreamEvent.TextDelta("one"), new StreamEvent.TextDelta("two")),
        events.subList(0, 2));
    var error = assertInstanceOf(StreamEvent.Error.class, events.get(2));
    assertEquals("Stream ended before its terminal event", error.message());
    assertEquals(
        "the response body ended before the provider's terminal event",
        assertInstanceOf(EOFException.class, error.cause()).getMessage());
    assertEquals("one|two", error.partial().content());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertEquals(3, events.size());
    assertTrue(body.closed);
  }

  @Test
  void anEmptyBodyEndsInAnErrorWithNothingProduced() {
    var reader = new SseReader(stream(""), NEVER_IDLE, new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertInstanceOf(EOFException.class, error.cause());
    assertEquals("", error.partial().content());
    assertFalse(reader.hasNext());
  }

  @Test
  void anEventTheBodyCutsOffBeforeItsBlankLineIsNeverParsed() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data: one\n\ndata: stop\n"), NEVER_IDLE, parser, ProviderException::new);

    var events = drain(reader);

    assertInstanceOf(EOFException.class, ((StreamEvent.Error) events.getLast()).cause());
    assertEquals(List.of("one"), parser.payloads);
  }

  @Test
  void aLineLongerThanTheFrameBoundEndsInTheProvidersException() {
    var line = "data: " + "x".repeat(SseFrames.MAX_FRAME_CHARS);
    var body = new Closing(stream(line + "\n\n"));
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream line exceeds 16777216 characters", error.message());
    assertTrue(body.closed);
    assertEquals(
        error.message(), assertInstanceOf(ProviderException.class, error.cause()).getMessage());
    assertFalse(reader.hasNext());
  }

  @Test
  void anEventLargerThanTheFrameBoundEndsInTheProvidersException() {
    var half = "data: " + "x".repeat(SseFrames.MAX_FRAME_CHARS / 2) + "\n";
    var parser = new EchoParser();
    var reader =
        new SseReader(stream(half + half + "\n"), NEVER_IDLE, parser, ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream event exceeds 16777216 characters", error.message());
    assertEquals(List.of(), parser.payloads);
  }

  @Test
  void hasNextAsksOnceUntilTheEventIsTaken() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data: one\n\ndata: two\n\n"), NEVER_IDLE, parser, ProviderException::new);

    assertTrue(reader.hasNext());
    assertTrue(reader.hasNext());

    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    assertEquals(List.of("one"), parser.payloads);
  }

  @Test
  void nextWithoutHasNextReadsTheNextEvent() {
    var reader =
        new SseReader(
            stream("data: one\n\ndata: stop\n\n"),
            NEVER_IDLE,
            new EchoParser(),
            ProviderException::new);

    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    assertInstanceOf(StreamEvent.Done.class, reader.next());
  }

  @Test
  void aLineThatDoesNotArriveInTimeFailsWithTheProvidersException() {
    var body = new Closing(new FeedableInputStream());
    try (var reader =
        new SseReader(
            body,
            Duration.ofMillis(50),
            new EchoParser(),
            (message, status, cause) -> new ProviderException("acme: " + message, status, cause))) {

      var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

      assertEquals(FinishReason.ERROR, error.partial().finishReason());
      assertTrue(body.closed);
      assertEquals("acme: Stream idle timeout: no data received for 0s", error.message());
      assertEquals(
          error.message(), assertInstanceOf(ProviderException.class, error.cause()).getMessage());
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void aReadFailureIsATerminalStreamReadError() {
    var failure = new IOException("connection reset");
    var body = new Closing(failing(failure));
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream read error", error.message());
    assertSame(failure, error.cause());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertTrue(body.closed);
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
    try (var server = server(200, "data: hello\n\n");
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

  private static List<StreamEvent> drain(SseReader reader) {
    var events = new ArrayList<StreamEvent>();
    while (reader.hasNext()) {
      events.add(reader.next());
    }
    return events;
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
