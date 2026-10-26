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
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FakeHttpClient;
import com.standardapplied.helios.core.test.FakeHttpResponse;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.HeldSseServer;
import com.standardapplied.helios.core.test.StubHttpServer;
import com.standardapplied.helios.core.test.Uninterruptible;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SseReaderTest {

  private static final Duration NEVER_IDLE = Duration.ofMinutes(10);

  private static final Response<Void> NOTHING =
      Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build();

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

  /** A body whose read blocks through interrupts and fails only once the body is closed. */
  private static final class ClosedOnlyByClose extends InputStream {
    private final CountDownLatch reading = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      reading.countDown();
      Uninterruptible.await(closed);
      throw new IOException("body closed");
    }

    @Override
    public void close() {
      closed.countDown();
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
            new StreamEvent.KeepAlive(),
            new StreamEvent.KeepAlive(),
            new StreamEvent.KeepAlive(),
            new StreamEvent.TextDelta("two"),
            new StreamEvent.KeepAlive(),
            new StreamEvent.Done(Response.newBuilder().withContent("done").build())),
        drain(reader));
    assertEquals(List.of("one", "two", "silent", "stop"), parser.payloads);
    assertTrue(body.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void everyEventWithoutContentIsAKeepAliveTheParserNeverSees() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream(
                ": ping\n\n"
                    + "event: ping\n\n"
                    + "data:\n\n"
                    + "data: [DONE]\n\n"
                    + "data: silent\n\n"
                    + "data: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    var events = drain(reader);

    var keepAlive = new StreamEvent.KeepAlive();
    assertEquals(
        List.of(
            keepAlive,
            keepAlive,
            keepAlive,
            keepAlive,
            keepAlive,
            new StreamEvent.Done(Response.newBuilder().withContent("done").build())),
        events);
    assertEquals(List.of("silent", "stop"), parser.payloads);
  }

  @Test
  void aBlankLineThatEndsNoEventYieldsNothing() {
    var reader =
        new SseReader(
            stream("\n\r\n\ndata: one\n\n\n\ndata: stop\n\n"),
            NEVER_IDLE,
            new EchoParser(),
            ProviderException::new);

    assertEquals(
        List.of(
            new StreamEvent.TextDelta("one"),
            new StreamEvent.Done(Response.newBuilder().withContent("done").build())),
        drain(reader));
  }

  @Test
  void readsDataWithAndWithoutTheSpaceAndJoinsSeveralDataLinesWithANewline() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data:{\"a\":\ndataset: no\ndata\ndata:  1}\n\ndata: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    drain(reader);

    assertEquals(List.of("{\"a\":\n\n 1}", "stop"), parser.payloads);
  }

  @Test
  void endsLinesAtCarriageReturnLineFeedOrEitherWhereverTheReadsSplitThem() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            byteByByte(
                "data: one\r\ndata: more\r\n\r\ndata: two\r\rdata: three\n\ndata: stop\r\n\r\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    drain(reader);

    assertEquals(List.of("one\nmore", "two", "three", "stop"), parser.payloads);
  }

  @Test
  void dropsOneByteOrderMarkAtTheStartOfTheBodyAndKeepsEveryOther() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("﻿data: one\n\n﻿data: lost\n\ndata: ﻿two\n\ndata: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    drain(reader);

    assertEquals(List.of("one", "﻿two", "stop"), parser.payloads);
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
  void aLineAndAnEventOfExactlyTheFrameBoundAreRead() {
    var line = "data:" + "x".repeat(SseFrames.MAX_FRAME_CHARS - 5);
    var halves =
        "data:"
            + "x".repeat(SseFrames.MAX_FRAME_CHARS / 2)
            + "\ndata:"
            + "x".repeat(SseFrames.MAX_FRAME_CHARS / 2 - 1);
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream(line + "\n\n" + halves + "\n\ndata: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    assertInstanceOf(StreamEvent.Done.class, drain(reader).getLast());
    assertEquals(
        List.of(SseFrames.MAX_FRAME_CHARS - 5, SseFrames.MAX_FRAME_CHARS, 4),
        parser.payloads.stream().map(String::length).toList());
  }

  @Test
  void aLineOneCharacterOverTheFrameBoundEndsInTheProvidersException() {
    var line = "data:" + "x".repeat(SseFrames.MAX_FRAME_CHARS - 4);
    var body = new Closing(stream(line + "\n\n"));
    var parser = new EchoParser();
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream line exceeds 16777216 characters", error.message());
    assertTrue(body.closed);
    assertEquals(
        error.message(), assertInstanceOf(ProviderException.class, error.cause()).getMessage());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertEquals(List.of(), parser.payloads);
    assertFalse(reader.hasNext());
  }

  @Test
  void aLineThatNeverEndsFailsAtTheFrameBoundInsteadOfBufferingTheBody() {
    var reader =
        new SseReader(
            endless("data: ".getBytes(StandardCharsets.UTF_8), (byte) 'x'),
            NEVER_IDLE,
            new EchoParser(),
            ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream line exceeds 16777216 characters", error.message());
  }

  @Test
  void anEventOneCharacterOverTheFrameBoundEndsInTheProvidersExceptionWithThePartial() {
    var half = "data: " + "x".repeat(SseFrames.MAX_FRAME_CHARS / 2) + "\n";
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data: one\n\n" + half + half + "\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);
    reader.next();

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream event exceeds 16777216 characters", error.message());
    assertInstanceOf(ProviderException.class, error.cause());
    assertEquals("one", error.partial().content());
    assertEquals(List.of("one"), parser.payloads);
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
  void theTerminalIsYieldedOnceHoweverOftenHasNextAsksAndNothingFollowsIt() {
    var reader =
        new SseReader(
            stream("data: stop\n\n"), NEVER_IDLE, new EchoParser(), ProviderException::new);

    assertTrue(reader.hasNext());
    assertTrue(reader.hasNext());
    assertInstanceOf(StreamEvent.Done.class, reader.next());
    assertFalse(reader.hasNext());
    assertThrows(NoSuchElementException.class, reader::next);
  }

  @Test
  void aClosedReaderHasNoMoreEventsAndHasClosedTheBody() {
    var feed = new FeedableInputStream();
    feed.feed("data: one\n\ndata: two\n\n");
    var body = new Closing(feed);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    assertTrue(reader.hasNext());

    reader.close();

    assertTrue(body.closed);
    assertFalse(reader.hasNext());
    assertThrows(NoSuchElementException.class, reader::next);
  }

  @Test
  void closingTheReaderReleasesALineReadBlockedOnTheBodyAsOneError() {
    var feed = new FeedableInputStream();
    var body = new Closing(feed);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    try (var consumer = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocked = consumer.submit(reader::next);
      feed.awaitBlockedRead();

      reader.close();

      var error =
          assertInstanceOf(StreamEvent.Error.class, Await.value("the blocked next", blocked));
      assertEquals("Stream read error", error.message());
      assertInstanceOf(IOException.class, error.cause());
      assertTrue(body.closed);
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void aReaderClosedWhileItsParserReadsAnEventYieldsThatEventAndNothingAfter() {
    var body = new Closing(stream("data: silent\n\ndata: two\n\n"));
    var readers = new ArrayList<SseReader>();
    var closingParser =
        new EchoParser() {
          @Override
          public StreamEvent parse(String payload) {
            readers.getFirst().close();
            return super.parse(payload);
          }
        };
    var reader = new SseReader(body, NEVER_IDLE, closingParser, ProviderException::new);
    readers.add(reader);

    assertEquals(new StreamEvent.KeepAlive(), reader.next());
    assertTrue(body.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void aReaderClosedBetweenTheLinesOfAnEventEndsInOneErrorInsteadOfThrowing() {
    var readers = new ArrayList<SseReader>();
    var firstLine = "data: one\n".getBytes(StandardCharsets.UTF_8);
    var body =
        new InputStream() {
          private boolean served;

          @Override
          public int read() {
            throw new UnsupportedOperationException("read in bulk");
          }

          @Override
          public int read(byte[] target, int offset, int length) {
            if (served) {
              return -1;
            }
            served = true;
            System.arraycopy(firstLine, 0, target, offset, firstLine.length);
            readers.getFirst().close();
            return firstLine.length;
          }
        };
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    readers.add(reader);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream read error", error.message());
    assertEquals("Stream closed", error.cause().getMessage());
    assertFalse(reader.hasNext());
  }

  @Test
  void closeReleasesAReadThatIgnoresInterruptsByClosingTheBodyFirst() {
    var body = new ClosedOnlyByClose();
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    var threads = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var blocked = threads.submit(reader::next);
      Await.latch("a read to block on the body", body.reading);

      Await.value("the close", threads.submit(reader::close));

      var error =
          assertInstanceOf(StreamEvent.Error.class, Await.value("the blocked next", blocked));
      assertEquals("body closed", error.cause().getMessage());
    } finally {
      threads.shutdownNow();
    }
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
      assertEquals("acme: Stream idle timeout: no data received for 50 ms", error.message());
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
  void anInterruptWhileWaitingForALineThrowsCancellationAndStaysSet() {
    var body = new Closing(new FeedableInputStream());
    try (var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new)) {
      Thread.currentThread().interrupt();

      assertThrows(CancellationException.class, reader::next);

      assertTrue(Thread.interrupted());
      assertTrue(body.closed);
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void aLazyReaderSendsNothingUntilItsFirstHasNext() throws Exception {
    var reply = HeldSseServer.Reply.of("data: hello\n\n");
    try (var server = HeldSseServer.start(reply);
        var client = HttpClient.newHttpClient()) {
      var reader = lazy(client, post(server.uri()));
      var sentBeforeRead = server.requests();

      assertEquals(new StreamEvent.TextDelta("hello"), reader.next());

      assertEquals(0, sentBeforeRead);
      assertEquals(1, server.requests());
      reader.close();
    }
  }

  @Test
  void aReaderClosedBeforeItsFirstHasNextSendsNothing() throws Exception {
    try (var server = HeldSseServer.start();
        var client = HttpClient.newHttpClient()) {
      var reader = lazy(client, post(server.uri()));

      reader.close();

      assertFalse(reader.hasNext());
      assertEquals(0, server.requests());
    }
  }

  @Test
  void openStreamsATwoHundredResponse() throws Exception {
    try (var server = server(200, Map.of(), "data: hello\n\n");
        var client = HttpClient.newHttpClient();
        var reader = open(client, server)) {

      assertEquals(new StreamEvent.TextDelta("hello"), reader.next());
    }
  }

  @Test
  void aRetryableStatusIsTheOneErrorCarryingATransientFailureWithTheProvidersException()
      throws Exception {
    try (var server = server(529, Map.of(), "{\"error\":\"overloaded\"}");
        var client = HttpClient.newHttpClient();
        var reader = open(client, server)) {

      var error = onlyError(reader);

      var failure = assertInstanceOf(TransientStreamException.class, error.cause());
      assertEquals("API error (status 529): {\"error\":\"overloaded\"}", failure.getMessage());
      assertEquals(failure.getMessage(), error.message());
      assertEquals(NOTHING, error.partial());
      assertEquals("stub", failure.providerName());
      assertEquals(Optional.empty(), failure.retryAfter());
      var cause = assertInstanceOf(ProviderException.class, failure.getCause());
      assertEquals(529, cause.statusCode());
      assertEquals(failure.getMessage(), cause.getMessage());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 429, 500, 503, 504, 529})
  void everyRetryableStatusIsATransientFailure(int status) throws Exception {
    try (var server = server(status, Map.of(), "busy");
        var client = HttpClient.newHttpClient();
        var reader = open(client, server)) {

      var failure = assertInstanceOf(TransientStreamException.class, onlyError(reader).cause());

      assertEquals(
          status, assertInstanceOf(ProviderException.class, failure.getCause()).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 413, 422})
  void anotherStatusIsTheOneErrorCarryingTheProvidersException(int status) throws Exception {
    try (var server = server(status, Map.of("Retry-After", "0"), "refused");
        var client = HttpClient.newHttpClient();
        var reader = open(client, server)) {

      var failure = assertInstanceOf(ProviderException.class, onlyError(reader).cause());

      assertEquals("API error (status " + status + "): refused", failure.getMessage());
      assertEquals(status, failure.statusCode());
      assertNull(failure.getCause());
    }
  }

  @Test
  void aRetryableStatusCarriesItsRetryAfter() throws Exception {
    try (var server = server(429, Map.of("Retry-After", "2"), "slow down");
        var client = HttpClient.newHttpClient();
        var reader = open(client, server)) {

      var failure = assertInstanceOf(TransientStreamException.class, onlyError(reader).cause());

      assertEquals(Optional.of(Duration.ofSeconds(2)), failure.retryAfter());
    }
  }

  @Test
  void aFailureToConnectIsTheOneErrorNamingTheApi() throws Exception {
    URI closed;
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closed = URI.create("http://127.0.0.1:" + socket.getLocalPort());
    }
    try (var client = HttpClient.newHttpClient();
        var reader = lazy(client, post(closed))) {

      var error = onlyError(reader);

      assertEquals("Failed to communicate with Acme API", error.message());
      assertInstanceOf(IOException.class, error.cause());
      assertEquals(NOTHING, error.partial());
    }
  }

  @Test
  void headersThatMissTheRequestTimeoutAreAFailureToCommunicate() throws Exception {
    var reply = HeldSseServer.Reply.holdingHeaders("data: late\n\n");
    try (var server = HeldSseServer.start(reply);
        var client = HttpClient.newHttpClient();
        var reader =
            lazy(
                client,
                HttpRequest.newBuilder(server.uri())
                    .timeout(Duration.ofMillis(50))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build())) {

      var error = onlyError(reader);

      assertEquals("Failed to communicate with Acme API", error.message());
      assertInstanceOf(HttpTimeoutException.class, error.cause());
    }
  }

  @Test
  void closingWhileAwaitingTheHeadersClosesTheConnectionAndTheBlockedReadThrowsCancellation()
      throws Exception {
    var reply = HeldSseServer.Reply.holdingHeaders("data: a\n\n");
    try (var server = HeldSseServer.start(reply);
        var client = HttpClient.newHttpClient();
        var reading = Executors.newVirtualThreadPerTaskExecutor()) {
      var reader = lazy(client, post(server.uri()));
      var blocked = reading.submit(reader::hasNext);
      reply.awaitRequest();

      reader.close();

      reply.awaitClientClosed();
      assertInstanceOf(CancellationException.class, Await.failure("the blocked read", blocked));
    }
  }

  @Test
  void closingMidBodyClosesTheConnection() throws Exception {
    var reply = HeldSseServer.Reply.of("data: a\n\n", "data: b\n\n");
    try (var server = HeldSseServer.start(reply);
        var client = HttpClient.newHttpClient()) {
      var reader = lazy(client, post(server.uri()));
      assertEquals(new StreamEvent.TextDelta("a"), reader.next());

      reader.close();

      reply.awaitClientClosed();
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void anInterruptWhileAwaitingTheHeadersCancelsTheRequestAndStaysSet() throws Exception {
    var reply = HeldSseServer.Reply.holdingHeaders("data: a\n\n");
    var interruptKept = new AtomicBoolean();
    try (var server = HeldSseServer.start(reply);
        var client = HttpClient.newHttpClient()) {
      var reader = lazy(client, post(server.uri()));
      var read =
          new FutureTask<>(
              () -> {
                try {
                  return reader.hasNext();
                } finally {
                  interruptKept.set(Thread.currentThread().isInterrupted());
                }
              });
      var thread = Thread.ofVirtual().start(read);
      reply.awaitRequest();

      thread.interrupt();

      reply.awaitClientClosed();
      assertInstanceOf(CancellationException.class, Await.failure("the read", read));
      assertTrue(interruptKept.get());
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void aResponseThatArrivesAfterTheCloseIsClosedAndTheReadThrowsCancellation() {
    var readers = new ArrayList<SseReader>();
    var body = new Closing(stream("data: a\n\n"));
    var client =
        FakeHttpClient.sendingAsync(
            request ->
                CompletableFuture.completedFuture(
                    new FakeHttpResponse(
                        request,
                        200,
                        Map.of(),
                        () -> {
                          readers.getFirst().close();
                          return body;
                        })));
    var reader = lazy(client, post(URI.create("http://example.invalid")));
    readers.add(reader);

    assertThrows(CancellationException.class, reader::hasNext);
    assertTrue(body.closed);
  }

  @Test
  void aResponseThatArrivesAsItsWaitIsInterruptedIsClosedAndTheReadThrowsCancellation() {
    var body = new Closing(stream("data: a\n\n"));
    var client =
        FakeHttpClient.sendingAsync(
            request ->
                new CompletableFuture<>() {
                  @Override
                  public HttpResponse<InputStream> get() throws InterruptedException {
                    complete(new FakeHttpResponse(request, 200, Map.of(), () -> body));
                    throw new InterruptedException("interrupted as the headers arrived");
                  }
                });
    var reader = lazy(client, post(URI.create("http://example.invalid")));

    try {
      assertThrows(CancellationException.class, reader::hasNext);
    } finally {
      assertTrue(Thread.interrupted());
    }
    assertTrue(body.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void anUncheckedOrErrorFailureOfTheRequestIsThrownAsItselfAndAnyOtherIsAFailureToCommunicate() {
    var refused = new IllegalStateException("refused");
    var fatal = new StackOverflowError();
    var odd = new Exception("odd");

    var unchecked = lazy(failingClient(refused), post(URI.create("http://example.invalid")));
    var error = lazy(failingClient(fatal), post(URI.create("http://example.invalid")));
    var checked = lazy(failingClient(odd), post(URI.create("http://example.invalid")));

    assertSame(refused, assertThrows(IllegalStateException.class, unchecked::hasNext));
    assertSame(fatal, assertThrows(StackOverflowError.class, error::hasNext));
    var communication = onlyError(checked);
    assertEquals("Failed to communicate with Acme API", communication.message());
    assertSame(odd, communication.cause().getCause());
  }

  private static HttpClient failingClient(Throwable failure) {
    return FakeHttpClient.sendingAsync(request -> CompletableFuture.failedFuture(failure));
  }

  private static StreamEvent.Error onlyError(SseReader reader) {
    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());
    assertFalse(reader.hasNext());
    return error;
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

  private static InputStream byteByByte(String text) {
    var bytes = stream(text);
    return new InputStream() {
      @Override
      public int read() throws IOException {
        return bytes.read();
      }

      @Override
      public int read(byte[] target, int offset, int length) throws IOException {
        return length == 0 ? 0 : bytes.read(target, offset, 1);
      }
    };
  }

  private static InputStream endless(byte[] prefix, byte repeated) {
    return new InputStream() {
      private int position;

      @Override
      public int read() {
        return position < prefix.length ? prefix[position++] : repeated;
      }
    };
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

  private static StubHttpServer server(int status, Map<String, String> headers, String body) {
    return StubHttpServer.start(
        InetAddress.getLoopbackAddress(),
        0,
        request -> new StubHttpServer.Reply(status, headers, body));
  }

  private static SseReader open(HttpClient client, StubHttpServer server) {
    return new SseReader(
        client,
        HttpRequest.newBuilder(server.uri()).build(),
        "Acme API",
        NEVER_IDLE,
        new EchoParser(),
        ProviderException::new,
        "stub");
  }

  private static SseReader lazy(HttpClient client, HttpRequest request) {
    return new SseReader(
        client, request, "Acme API", NEVER_IDLE, new EchoParser(), ProviderException::new, "acme");
  }

  private static HttpRequest post(URI uri) {
    return HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
  }
}
