/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static com.standardapplied.helios.core.provider.SseFixtures.NEVER_IDLE;
import static com.standardapplied.helios.core.provider.SseFixtures.stream;
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
import com.standardapplied.helios.core.provider.SseFixtures.Closing;
import com.standardapplied.helios.core.provider.SseFixtures.EchoParser;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FakeHttpClient;
import com.standardapplied.helios.core.test.FakeHttpResponse;
import com.standardapplied.helios.core.test.HeldSseServer;
import com.standardapplied.helios.core.test.StubHttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SseRequestTest {

  private static final Response<Void> NOTHING =
      Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build();

  @Test
  void everyPartOfAReaderIsRequiredWhenItIsBuilt() {
    var client = failingClient(new IOException("unused"));
    var request = post(URI.create("http://localhost/"));
    var parser = new EchoParser();
    ProviderFailure failure = ProviderException::new;
    var body = stream("");

    assertNullRejected(
        "client", () -> new SseReader(null, request, "Acme API", NEVER_IDLE, parser, failure, "a"));
    assertNullRejected(
        "request", () -> new SseReader(client, null, "Acme API", NEVER_IDLE, parser, failure, "a"));
    assertNullRejected(
        "apiName", () -> new SseReader(client, request, null, NEVER_IDLE, parser, failure, "a"));
    assertNullRejected(
        "providerName",
        () -> new SseReader(client, request, "Acme API", NEVER_IDLE, parser, failure, null));
    assertNullRejected("body", () -> new SseReader(null, NEVER_IDLE, parser, failure));
    assertNullRejected("idleTimeout", () -> new SseReader(body, null, parser, failure));
    assertNullRejected("parser", () -> new SseReader(body, NEVER_IDLE, null, failure));
    assertNullRejected("failure", () -> new SseReader(body, NEVER_IDLE, parser, null));
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
  void anInterruptWhileReadingTheBodyOfARefusalThrowsCancellationAndStaysSet() {
    var refusal =
        new Closing(
            new InputStream() {
              @Override
              public int read() throws IOException {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while reading the error body");
              }
            });
    var client =
        FakeHttpClient.sendingAsync(
            request ->
                CompletableFuture.completedFuture(
                    new FakeHttpResponse(request, 503, Map.of(), () -> refusal)));
    var reader = lazy(client, post(URI.create("http://example.invalid")));

    CancellationException cancelled;
    try {
      cancelled = assertThrows(CancellationException.class, reader::hasNext);
    } finally {
      assertTrue(Thread.interrupted());
    }
    assertEquals("interrupted while reading the error body", cancelled.getCause().getMessage());
    assertTrue(refusal.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void anInterruptWhileTheBodyOfARetryableRefusalIsReadWholeThrowsCancellationAndStaysSet() {
    var refusal =
        new Closing(
            new InputStream() {
              @Override
              public int read() {
                Thread.currentThread().interrupt();
                return -1;
              }
            });
    var client =
        FakeHttpClient.sendingAsync(
            request ->
                CompletableFuture.completedFuture(
                    new FakeHttpResponse(request, 503, Map.of(), () -> refusal)));
    var reader = lazy(client, post(URI.create("http://example.invalid")));

    CancellationException cancelled;
    try {
      cancelled = assertThrows(CancellationException.class, reader::hasNext);
    } finally {
      assertTrue(Thread.interrupted());
    }
    assertNull(cancelled.getCause());
    assertTrue(refusal.closed);
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

  private static void assertNullRejected(String part, Executable build) {
    assertEquals(
        part + " must not be null", assertThrows(NullPointerException.class, build).getMessage());
  }
}
