/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.common.Weighted;
import io.helidon.http.DirectHandler;
import io.helidon.http.DirectHandler.EventType;
import io.helidon.http.Header;
import io.helidon.http.HeaderValues;
import io.helidon.http.HttpException;
import io.helidon.http.ServerResponseHeaders;
import io.helidon.http.Status;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.DirectHandlers;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The guard on a plain Helidon {@link WebServer}'s routing: an answer that another feature's filter
 * or an escaping exception produces while the body is unread is delivered whole and then ends the
 * connection, so the request the body carries never runs. The {@link RuntimeServer} cases are in
 * {@link RuntimeServerRequestFramingTest}.
 */
final class UnreadBodyGuardTest {

  /** A complete request, sent as the body of another, for the route that counts its runs. */
  private static final String SMUGGLED =
      "GET /smuggled HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";

  private static final Header REFUSE = HeaderValues.create("X-Refuse", "yes");

  private final AtomicInteger smuggledRuns = new AtomicInteger();
  private WebServer server;

  @AfterEach
  void stop() {
    server.stop();
  }

  @Test
  void anAnswerFromAnotherFeaturesFilterEndsTheConnection() throws IOException {
    start(DirectHandlers.builder().build(), routing -> routing.addFeature(new RefusingFeature()));
    try (var connection = RawConnection.open(server.port())) {
      connection.write(
          "POST /refused HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Refuse: yes\r\nExpect: 100-continue\r\n"
              + "Content-Length: "
              + SMUGGLED.length()
              + "\r\n\r\n");
      var refused = connection.response();
      connection.write(SMUGGLED);

      assertEquals("HTTP/1.1 403 Forbidden", refused.statusLine());
      assertTrue(refused.closesConnection(), () -> "headers: " + refused.headers());
      assertEquals(-1, connection.next());
    }
    assertEquals(0, smuggledRuns.get());
  }

  @Test
  void anExceptionARouteLetsEscapeIsAnsweredThroughTheListenersDirectHandlersThenEndsTheConnection()
      throws IOException {
    var teapot =
        new HttpException("brewing", Status.create(418, "I'm a teapot"), true)
            .header(HeaderValues.create("X-Brew", "tea"));
    var echo = new EchoingDirectHandler();
    start(
        DirectHandlers.builder()
            .addHandler(EventType.OTHER, echo)
            .addHandler(EventType.INTERNAL_ERROR, echo)
            .build(),
        routing ->
            routing
                .post(
                    "/teapot",
                    (req, resp) -> {
                      throw teapot;
                    })
                .post(
                    "/crash",
                    (req, resp) -> {
                      throw new AssertionError("crash");
                    }));
    try (var teapotConnection = RawConnection.open(server.port());
        var crashConnection = RawConnection.open(server.port())) {
      var brewing = postSmuggling(teapotConnection, "/teapot");
      var crashed = postSmuggling(crashConnection, "/crash");

      assertEquals("HTTP/1.1 418 I'm a teapot", brewing.statusLine());
      assertEquals("tea", brewing.headers().get("x-brew"));
      assertEquals("OTHER brewing caused by " + teapot, brewing.body());
      assertTrue(brewing.closesConnection());
      assertEquals(-1, teapotConnection.next());
      assertEquals("HTTP/1.1 500 Internal Server Error", crashed.statusLine());
      assertEquals(
          "INTERNAL_ERROR Internal Server Error caused by java.lang.AssertionError: crash",
          crashed.body());
      assertTrue(crashed.closesConnection());
      assertEquals(-1, crashConnection.next());
    }
    assertEquals(0, smuggledRuns.get());
  }

  /** Start a server whose routing has the guard, the counting route and {@code routes}. */
  private void start(DirectHandlers directHandlers, Consumer<HttpRouting.Builder> routes) {
    server =
        WebServer.builder()
            .host("127.0.0.1")
            .port(0)
            .featuresDiscoverServices(false)
            .directHandlers(directHandlers)
            .routing(
                (HttpRouting.Builder routing) -> {
                  routing
                      .addFeature(new UnreadBodyGuard())
                      .get(
                          "/smuggled",
                          (req, resp) -> resp.send(String.valueOf(smuggledRuns.incrementAndGet())));
                  routes.accept(routing);
                })
            .build()
            .start();
  }

  /**
   * Post to {@code path}, in one write, a body that is the smuggled request, and read the answer.
   */
  private static RawConnection.Response postSmuggling(RawConnection connection, String path)
      throws IOException {
    connection.write(
        "POST "
            + path
            + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: "
            + SMUGGLED.length()
            + "\r\n\r\n"
            + SMUGGLED);
    return connection.response();
  }

  /** Answers with the event type, the message and the cause of the exception it is handed. */
  private static final class EchoingDirectHandler implements DirectHandler {

    @Override
    public TransportResponse handle(
        TransportRequest request,
        EventType eventType,
        Status status,
        ServerResponseHeaders headers,
        Throwable thrown) {
      return handle(
          request,
          eventType,
          status,
          headers,
          eventType + " " + thrown.getMessage() + " caused by " + thrown.getCause());
    }

    @Override
    public TransportResponse handle(
        TransportRequest request,
        EventType eventType,
        Status status,
        ServerResponseHeaders headers,
        String message) {
      return TransportResponse.builder().status(status).headers(headers).entity(message).build();
    }
  }

  /** A feature weighted as Helidon's CORS whose filter refuses, unread, a request so marked. */
  private static final class RefusingFeature implements HttpFeature, Weighted {

    @Override
    public void setup(HttpRouting.Builder routing) {
      routing.addFilter(
          (chain, req, resp) -> {
            if (req.headers().contains(REFUSE)) {
              resp.status(Status.FORBIDDEN_403).send();
              return;
            }
            chain.proceed();
          });
    }

    @Override
    public double weight() {
      return 850;
    }
  }
}
