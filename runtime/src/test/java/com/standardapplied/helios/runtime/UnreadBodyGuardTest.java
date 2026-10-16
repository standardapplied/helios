/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import io.helidon.common.Weighted;
import io.helidon.http.Header;
import io.helidon.http.HeaderValues;
import io.helidon.http.Status;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every answer, Helios's or Helidon's own, that leaves a request body unread is delivered complete
 * and then ends the connection, so nothing the client sends after it is drained or run as a
 * request; a request whose body was read to its end, or that has none, keeps its connection. Each
 * request reaches a {@link RuntimeServer} over a raw socket from the loopback peer that its {@link
 * TrustedProxyAuthenticator} trusts.
 */
final class UnreadBodyGuardTest {

  private static final int MAX_BODY = 64;

  /** The most one read of Helidon's {@code PlainSocket} takes off the connection. */
  private static final int SERVER_READ_BUFFER = 8 * 1024;

  private static final String SOCKET_READ = "jdk.SocketRead";

  /** A complete request, sent as the body of another, from an identity no client may claim. */
  private static final String SMUGGLED =
      "POST /v1/sessions HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Authenticated-Tenant: evil\r\n"
          + "X-Authenticated-Subject: smuggler\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

  private static final Header SMUGGLER =
      HeaderValues.create(TrustedProxyAuthenticator.DEFAULT_SUBJECT_HEADER, "smuggler");

  private static final Header CRASH = HeaderValues.create("X-Crash", "yes");

  private static final Header REFUSE = HeaderValues.create("X-Refuse", "yes");

  private final AtomicInteger smuggledSeen = new AtomicInteger();
  private final List<RawConnection> heldUploads = new ArrayList<>();
  private final RuntimeServer server = server();
  private final Map<String, String> sessions =
      Map.of("alice", create("alice"), "bob", create("bob"), "carol", create("carol"));

  @AfterEach
  void close() throws IOException {
    for (var held : heldUploads) {
      held.close();
    }
    server.close();
  }

  static Stream<Arguments> unreadBodies() {
    return Stream.of(
            refusal(
                "401 without an identity",
                "HTTP/1.1 401 Unauthorized",
                "{\"error\":\"unauthenticated\"}",
                t -> Upload.declared("POST", messages(t.sessions.get("alice")), "")),
            refusal(
                "404 for an unknown session",
                "HTTP/1.1 404 Not Found",
                "{\"error\":\"session not found\"}",
                t -> Upload.declared("POST", messages("missing"), identity("alice"))),
            refusal(
                "404 for a path no route matches",
                "HTTP/1.1 404 Not Found",
                "Endpoint not found",
                t -> Upload.declared("POST", "/elsewhere", identity("alice"))),
            refusal(
                "413 for a declared length over the bound",
                "HTTP/1.1 413 Request Entity Too Large",
                "{\"error\":\"request body too large\"}",
                t -> Upload.declared("POST", messages(t.sessions.get("alice")), identity("alice"))),
            refusal(
                "413 for a chunked body one byte past the bound",
                "HTTP/1.1 413 Request Entity Too Large",
                "{\"error\":\"request body too large\"}",
                t -> Upload.chunkedPastBound(messages(t.sessions.get("alice")), identity("alice"))),
            refusal(
                "429 for an upload over the principal's bound",
                "HTTP/1.1 429 Too Many Requests",
                "{\"error\":\"too many message uploads in progress\"}",
                t -> t.holdingEveryUpload(messages(t.sessions.get("alice")), "alice")),
            refusal(
                "503 for an upload over the service's bound",
                "HTTP/1.1 503 Service Unavailable",
                "{\"error\":\"server at message upload capacity\"}",
                t -> t.holdingEveryUpload(messages(t.sessions.get("carol")), "carol")),
            answer(
                "201 for a create that ignores its body",
                "HTTP/1.1 201 Created",
                Pattern.compile("\\{.*\"sessionId\":\"[^\"]+\".*}"),
                t -> Upload.declared("POST", "/v1/sessions", identity("alice"))),
            refusal(
                "406 raised by Helidon",
                "HTTP/1.1 406 Not Acceptable",
                "Unable to find sink provider for request",
                t ->
                    Upload.declared(
                        "GET",
                        "/v1/sessions/" + t.sessions.get("alice") + "/events",
                        identity("alice") + "Accept: application/json\r\n")),
            refusal(
                "400 without a body for an Accept header Helidon cannot parse",
                "HTTP/1.1 400 Bad Request",
                "",
                t -> Upload.declared("POST", "/v1/sessions", "Accept: a;b;c\r\n")),
            refusal(
                "500 for an Error a route lets escape",
                "HTTP/1.1 500 Internal Server Error",
                "Internal Server Error",
                t -> Upload.declared("POST", "/v1/sessions", "X-Crash: yes\r\n")))
        .flatMap(
            exchange ->
                Stream.of(
                    Arguments.of(exchange.name(), exchange, false),
                    Arguments.of(exchange.name(), exchange, true)));
  }

  @ParameterizedTest(name = "{0}, Expect: 100-continue {2}")
  @MethodSource("unreadBodies")
  void anAnswerLeavingTheBodyUnreadIsDeliveredWholeThenEndsTheConnection(
      String name, Exchange exchange, boolean expectContinue) throws IOException {
    var upload = exchange.upload().apply(this);
    try (var connection = RawConnection.open(server.port())) {
      var answer = send(connection, upload, expectContinue);

      assertEquals(exchange.statusLine(), answer.statusLine());
      assertTrue(exchange.body().matcher(answer.body()).matches(), answer::body);
      assertTrue(answer.closesConnection(), () -> "headers: " + answer.headers());
      assertEquals(-1, connection.next());
    }
    assertEquals(0, smuggledSeen.get());
  }

  @Test
  void aRequestSmuggledInTheBodyOfARefusedOneIsNeverRun() throws IOException {
    try (var connection = RawConnection.open(server.port())) {
      connection.write(
          "POST /v1/sessions/missing/messages HTTP/1.1\r\nHost: 127.0.0.1\r\n"
              + "Content-Type: application/json\r\nExpect: 100-continue\r\nContent-Length: "
              + SMUGGLED.length()
              + "\r\n\r\n");
      var refused = connection.response();
      connection.write(SMUGGLED);

      assertEquals("HTTP/1.1 401 Unauthorized", refused.statusLine());
      assertEquals(-1, connection.next());
    }
    assertEquals(0, smuggledSeen.get());
    assertEquals(3, server.registry().size());
  }

  /**
   * A feature weighted above the default, as Helidon's CORS (850) is, sets up its filters before
   * those of a default-weighted one; an answer its filter sends without proceeding is covered too.
   */
  @Test
  void anAnswerFromAnotherFeaturesFilterEndsTheConnection() throws IOException {
    var routed = new AtomicInteger();
    var gated =
        WebServer.builder()
            .host("127.0.0.1")
            .port(0)
            .featuresDiscoverServices(false)
            .routing(
                (HttpRouting.Builder routing) ->
                    routing
                        .addFeature(new UnreadBodyGuard())
                        .addFeature(new RefusingFeature())
                        .any((req, resp) -> resp.send(String.valueOf(routed.incrementAndGet()))))
            .build()
            .start();
    try (var connection = RawConnection.open(gated.port())) {
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
    } finally {
      gated.stop();
    }
    assertEquals(0, routed.get());
  }

  @Test
  void requestsWhoseBodiesAreReadOrAbsentKeepTheirConnection() throws IOException {
    var alice = sessions.get("alice");
    try (var connection = RawConnection.open(server.port())) {
      var answers = new ArrayList<RawConnection.Response>();
      for (var text : List.of("one", "two")) {
        var body = "{\"text\":\"" + text + "\"}";
        connection.write(
            "POST "
                + messages(alice)
                + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + identity("alice")
                + "Content-Type: application/json\r\nContent-Length: "
                + body.length()
                + "\r\n\r\n"
                + body);
        answers.add(connection.response());
      }
      for (var accept : List.of("application/json", "application/json", "*/*")) {
        connection.write(
            "GET /v1/sessions/"
                + alice
                + "/result?timeout=0 HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + identity("alice")
                + "Accept: "
                + accept
                + "\r\n\r\n");
        answers.add(connection.response());
      }

      assertEquals(
          List.of(
              "HTTP/1.1 202 Accepted",
              "HTTP/1.1 202 Accepted",
              "HTTP/1.1 204 No Content",
              "HTTP/1.1 204 No Content",
              "HTTP/1.1 204 No Content"),
          answers.stream().map(RawConnection.Response::statusLine).toList());
      assertTrue(answers.stream().noneMatch(RawConnection.Response::closesConnection));
    }
  }

  @Test
  void helidonsOwnAnswersWithoutABodyKeepTheConnectionUnlessTheirExceptionEndsIt()
      throws IOException {
    try (var connection = RawConnection.open(server.port())) {
      var answers = new ArrayList<RawConnection.Response>();
      for (var accept : List.of("*/*", "*/*", "application/json")) {
        connection.write(
            "GET /v1/sessions/"
                + sessions.get("alice")
                + "/events/elsewhere HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + identity("alice")
                + "Accept: "
                + accept
                + "\r\n\r\n");
        answers.add(connection.response());
      }
      connection.write(
          "GET /v1/sessions/"
              + sessions.get("alice")
              + "/events HTTP/1.1\r\nHost: 127.0.0.1\r\n"
              + identity("alice")
              + "Accept: application/json\r\n\r\n");
      var notAcceptable = connection.response();

      assertEquals(
          List.of("HTTP/1.1 404 Not Found", "HTTP/1.1 404 Not Found", "HTTP/1.1 404 Not Found"),
          answers.stream().map(RawConnection.Response::statusLine).toList());
      assertTrue(answers.stream().noneMatch(RawConnection.Response::closesConnection));
      assertEquals("HTTP/1.1 406 Not Acceptable", notAcceptable.statusLine());
      assertTrue(notAcceptable.closesConnection());
      assertEquals(-1, connection.next());
    }
  }

  /**
   * The server's end of the connection reads the head and, in the same read, at most one 8 KiB
   * socket buffer of the body, counted from the JDK's {@code jdk.SocketRead} events for the socket
   * whose peer is the client; a server that drained would read all 64 MiB.
   */
  @Test
  void aRefusedBodyLargerThanTheSocketBuffersIsNotRead() throws IOException {
    var head =
        "POST "
            + messages(sessions.get("alice"))
            + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
            + identity("alice")
            + "Content-Type: application/json\r\nContent-Length: "
            + 64L * 1024 * 1024
            + "\r\n\r\n";
    var reads = new ConcurrentLinkedQueue<SocketRead>();
    int clientPort;
    try (var recording = new RecordingStream()) {
      recording.enable(SOCKET_READ).withoutThreshold();
      recording.onEvent(
          SOCKET_READ,
          event -> reads.add(new SocketRead(event.getInt("port"), event.getLong("bytesRead"))));
      recording.startAsync();
      try (var connection = RawConnection.open(server.port())) {
        clientPort = connection.localPort();
        connection.write(head);
        var writing = CompletableFuture.runAsync(() -> writeUntilRefused(connection));

        var refused = connection.response();

        assertEquals("HTTP/1.1 413 Request Entity Too Large", refused.statusLine());
        assertTrue(refused.closesConnection());
        assertEquals(-1, connection.next());
        assertInstanceOf(
            UncheckedIOException.class, Await.failure("the body's writer to be refused", writing));
      }
      recording.stop();
    }
    var serverRead =
        reads.stream()
            .filter(read -> read.peerPort() == clientPort)
            .mapToLong(SocketRead::bytes)
            .sum();
    assertTrue(
        serverRead <= head.length() + SERVER_READ_BUFFER, () -> serverRead + " bytes were read");
  }

  private static void writeUntilRefused(RawConnection connection) {
    var chunk = new byte[SERVER_READ_BUFFER];
    while (true) {
      try {
        connection.write(chunk);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private RawConnection.Response send(
      RawConnection connection, Upload upload, boolean expectContinue) throws IOException {
    var head = upload.head() + (expectContinue ? "Expect: 100-continue\r\n" : "") + "\r\n";
    if (!expectContinue) {
      connection.write(head + upload.readable() + upload.rest());
      return connection.response();
    }
    connection.write(head);
    if (!upload.readable().isEmpty()) {
      assertEquals("HTTP/1.1 100 Continue", connection.response().statusLine());
      connection.write(upload.readable());
    }
    var answer = connection.response();
    connection.write(upload.rest());
    return answer;
  }

  /** Hold one upload each of alice and bob open, every one the service admits, then upload. */
  private Upload holdingEveryUpload(String path, String principal) {
    for (var holder : List.of("alice", "bob")) {
      try {
        var held = RawConnection.open(server.port());
        heldUploads.add(held);
        held.write(
            "POST "
                + messages(sessions.get(holder))
                + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + identity(holder)
                + "Content-Type: application/json\r\nContent-Length: 2\r\n"
                + "Expect: 100-continue\r\n\r\n");
        assertEquals("HTTP/1.1 100 Continue", held.response().statusLine());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return Upload.declared("POST", path, identity(principal));
  }

  private RuntimeServer server() {
    var trusted =
        TrustedProxyAuthenticator.newBuilder()
            .withTrustedProxy(InetAddress.getLoopbackAddress())
            .build();
    return RuntimeServer.builder()
        .withRegistry(
            SessionRegistry.withFactory(options -> new StubSession(options.sessionId(), 8)))
        .withOptionsFactory(
            sessionId ->
                SessionOptions.newBuilder()
                    .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
                    .withSessionId(sessionId)
                    .build())
        .withRequestLimits(
            RequestLimits.newBuilder()
                .withMaxRequestBytes(MAX_BODY)
                .withMaxMessageUploads(2)
                .withMaxMessageUploadsPerPrincipal(1)
                .build())
        .withAuthenticator(
            request -> {
              if (request.headers().contains(SMUGGLER)) {
                smuggledSeen.incrementAndGet();
              }
              if (request.headers().contains(CRASH)) {
                throw new AssertionError("an Error escaping a route");
              }
              return trusted.authenticate(request);
            })
        .build();
  }

  private String create(String principal) {
    try (var connection = RawConnection.open(server.port())) {
      connection.write(
          "POST /v1/sessions HTTP/1.1\r\nHost: 127.0.0.1\r\n" + identity(principal) + "\r\n");
      var created = connection.response();
      assertEquals("HTTP/1.1 201 Created", created.statusLine(), created::body);
      return (String)
          JsonMapper.builder().build().readValue(created.body(), Map.class).get("sessionId");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String identity(String principal) {
    return "X-Authenticated-Tenant: acme\r\nX-Authenticated-Subject: " + principal + "\r\n";
  }

  private static String messages(String sessionId) {
    return "/v1/sessions/" + sessionId + "/messages";
  }

  /**
   * One socket read, as the JDK records it.
   *
   * @param peerPort the port of the socket's peer
   * @param bytes the bytes the read returned
   */
  record SocketRead(int peerPort, long bytes) {}

  private static Exchange refusal(
      String name, String statusLine, String body, Function<UnreadBodyGuardTest, Upload> upload) {
    return new Exchange(name, statusLine, Pattern.compile(Pattern.quote(body)), upload);
  }

  private static Exchange answer(
      String name, String statusLine, Pattern body, Function<UnreadBodyGuardTest, Upload> upload) {
    return new Exchange(name, statusLine, body, upload);
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

  /**
   * What a case sends and the answer it expects.
   *
   * @param name the case, as the test report shows it
   * @param statusLine the answer's status line
   * @param body what the answer's body matches
   * @param upload the request, built once the case's server is up
   */
  record Exchange(
      String name, String statusLine, Pattern body, Function<UnreadBodyGuardTest, Upload> upload) {

    @Override
    public String toString() {
      return name;
    }
  }

  /**
   * A request with a body.
   *
   * @param head request line and headers, without the blank line that ends them
   * @param readable the part of the body a route may read before it answers
   * @param rest the rest of the body: a complete request of its own
   */
  record Upload(String head, String readable, String rest) {

    /** A body of {@code Content-Length} bytes that is the smuggled request. */
    static Upload declared(String method, String path, String headers) {
      return new Upload(
          method
              + " "
              + path
              + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
              + headers
              + "Content-Type: application/json\r\nContent-Length: "
              + SMUGGLED.length()
              + "\r\n",
          "",
          SMUGGLED);
    }

    /**
     * A chunked body whose first chunk is one byte past the bound; the smuggled request follows.
     */
    static Upload chunkedPastBound(String path, String headers) {
      var overBound = "x".repeat(MAX_BODY + 1);
      return new Upload(
          "POST "
              + path
              + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
              + headers
              + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n",
          Integer.toHexString(overBound.length()) + "\r\n" + overBound + "\r\n",
          "0\r\n\r\n" + SMUGGLED);
    }
  }
}
