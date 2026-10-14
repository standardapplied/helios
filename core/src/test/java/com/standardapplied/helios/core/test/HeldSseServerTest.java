/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HeldSseServerTest {

  @Test
  void emptyPartsSendNothingSoTheHeadersCanGoOutAlone() throws IOException {
    var reply = HeldSseServer.Reply.of("", "data: a\n\n", "", "data: b\n\n");
    try (var server = HeldSseServer.start(reply);
        var client = HttpClient.newHttpClient()) {
      var response =
          Await.value(
              "the headers",
              client.sendAsync(get(server), HttpResponse.BodyHandlers.ofInputStream()));

      reply.release();
      reply.release();
      reply.release();

      try (var body = response.body()) {
        assertEquals(
            "data: a\n\ndata: b\n\n", new String(body.readAllBytes(), StandardCharsets.UTF_8));
      }
      reply.awaitFinished();
    }
  }

  @Test
  void aRequestBeyondTheRepliesFailsTheServer() throws IOException, InterruptedException {
    var reply = HeldSseServer.Reply.of("data: a\n\n");
    var server = HeldSseServer.start(reply);
    try (var client = HttpClient.newHttpClient()) {
      assertEquals(
          "data: a\n\n", client.send(get(server), HttpResponse.BodyHandlers.ofString()).body());

      assertInstanceOf(
          IOException.class,
          Await.failure(
              "the extra request",
              client.sendAsync(get(server), HttpResponse.BodyHandlers.ofString())));
    }

    var expected = "Request 2 arrived with no reply left for it";
    assertEquals(expected, assertThrows(AssertionError.class, server::requests).getMessage());
    assertEquals(expected, assertThrows(AssertionError.class, reply::awaitFinished).getMessage());
    assertEquals(expected, assertThrows(AssertionError.class, server::close).getMessage());
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
  void anUnreadableRequestFailsTheServer(String header, String expected) throws IOException {
    var reply = HeldSseServer.Reply.of("data: a\n\n");
    var server = HeldSseServer.start(reply);
    try (var socket = new Socket(server.uri().getHost(), server.uri().getPort())) {
      socket
          .getOutputStream()
          .write(("POST / HTTP/1.1\r\n" + header + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));

      assertEquals(expected, assertThrows(AssertionError.class, reply::awaitRequest).getMessage());
    }
    assertEquals(expected, assertThrows(AssertionError.class, server::close).getMessage());
  }

  private static HttpRequest get(HeldSseServer server) {
    return HttpRequest.newBuilder(server.uri()).version(HttpClient.Version.HTTP_1_1).build();
  }
}
