/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StubHttpServerTest {

  @Test
  void recordsEachRequestAndAnswersWithTheHandlersReply() throws IOException, InterruptedException {
    try (var server =
            start(request -> new StubHttpServer.Reply(201, Map.of("X-Stub", "yes"), "hi"));
        var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              post(server, "ping").header("X-Probe", "1").build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(201, response.statusCode());
      assertEquals("yes", response.headers().firstValue("X-Stub").orElseThrow());
      assertEquals("hi", response.body());
      var request = server.requests().getFirst();
      assertEquals("POST / HTTP/1.1", request.requestLine());
      assertEquals("1", request.headers().get("x-probe"));
      assertEquals("ping", request.body());
      assertEquals(1, server.connections());
    }
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          no colon here                | Request header without a colon: no colon here
          Content-Length: -1           | Unreadable Content-Length: -1
          Content-Length: ten          | Unreadable Content-Length: ten
          Transfer-Encoding: chunked   | Request body sent with Transfer-Encoding: chunked; \
          only a Content-Length body is read
          """)
  void anUnreadableRequestFailsTheServerAndDropsTheConnection(String header, String expected)
      throws IOException {
    var server = start(request -> new StubHttpServer.Reply(200, Map.of(), ""));
    try (var socket = new Socket(server.uri().getHost(), server.port())) {
      socket
          .getOutputStream()
          .write(("GET / HTTP/1.1\r\n" + header + "\r\n").getBytes(StandardCharsets.US_ASCII));
      var read = new FutureTask<>(() -> socket.getInputStream().read());
      Thread.ofVirtual().start(read);

      assertEquals(-1, Await.value("the server dropping the connection", read));
    }
    assertEquals(expected, assertThrows(AssertionError.class, server::requests).getMessage());
    assertEquals(expected, assertThrows(AssertionError.class, server::connections).getMessage());
    assertEquals(expected, assertThrows(AssertionError.class, server::close).getMessage());
  }

  @Test
  void aFailingHandlerFailsTheServerAndTheClient() throws IOException {
    var boom = new IllegalStateException("boom");
    var server =
        start(
            request -> {
              throw boom;
            });

    assertInstanceOf(IOException.class, failedCall(server));

    var failure = assertThrows(AssertionError.class, server::requests);
    assertEquals("The handler failed on GET / HTTP/1.1", failure.getMessage());
    assertSame(boom, failure.getCause().getCause());
    assertThrows(AssertionError.class, server::close);
  }

  @Test
  void aHandlerReturningNoReplyFailsTheServerAndTheClient() throws IOException {
    var server = start(request -> null);

    assertInstanceOf(IOException.class, failedCall(server));

    assertEquals(
        "The handler returned no reply to GET / HTTP/1.1",
        assertThrows(AssertionError.class, server::requests).getMessage());
    assertThrows(AssertionError.class, server::close);
  }

  @Test
  void aReplyWithoutABodyOrHeadersIsRejected() {
    assertThrows(NullPointerException.class, () -> new StubHttpServer.Reply(200, Map.of(), null));
    assertThrows(NullPointerException.class, () -> new StubHttpServer.Reply(200, null, ""));
  }

  private static StubHttpServer start(
      Function<StubHttpServer.Request, StubHttpServer.Reply> handler) {
    return StubHttpServer.start(InetAddress.getLoopbackAddress(), 0, handler);
  }

  private static Throwable failedCall(StubHttpServer server) {
    try (var client = HttpClient.newHttpClient()) {
      return Await.failure(
          "the call",
          client.sendAsync(
              HttpRequest.newBuilder(server.uri()).version(HttpClient.Version.HTTP_1_1).build(),
              HttpResponse.BodyHandlers.ofString()));
    }
  }

  private static HttpRequest.Builder post(StubHttpServer server, String body) {
    return HttpRequest.newBuilder(server.uri())
        .version(HttpClient.Version.HTTP_1_1)
        .POST(HttpRequest.BodyPublishers.ofString(body));
  }
}
