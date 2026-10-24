/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.FramingServers.MAX_BODY;
import static com.standardapplied.helios.runtime.FramingServers.REQUESTS_PER_PRINCIPAL;
import static com.standardapplied.helios.runtime.FramingServers.SMUGGLED;
import static com.standardapplied.helios.runtime.FramingServers.identity;
import static com.standardapplied.helios.runtime.FramingServers.messages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * No body byte is ever parsed as a request. Every answer of a Helios route that leaves a request
 * body unread is delivered complete and then ends the connection; Helidon's own answers within the
 * body bound read the body and keep the connection; a body declared over the bound is refused by
 * Helidon unread. A request whose body was read to its end, or that has none, keeps its connection.
 * Each request reaches a {@link RuntimeServer}, or an {@link AgentHttpService} on a plain server of
 * its own, over a raw socket from the loopback peer that its {@link TrustedProxyAuthenticator}
 * trusts. A body is itself a complete request, from an identity no client may claim, which the
 * authenticator counts if it is ever run.
 */
final class RuntimeServerRequestFramingTest {

  /** The most one read of Helidon's {@code PlainSocket} takes off the connection. */
  private static final int SERVER_READ_BUFFER = 8 * 1024;

  private static final String SOCKET_READ = "jdk.SocketRead";

  private static final String UNMATCHED = "/elsewhere";

  private final FramingServers servers = new FramingServers();
  private final List<RawConnection> heldStreams = new ArrayList<>();

  @AfterEach
  void close() throws IOException {
    for (var held : heldStreams) {
      held.close();
    }
    servers.close();
  }

  /** Where a case's requests go. */
  enum Target {
    RUNTIME_SERVER,
    OWN_SERVER_WITH_THE_SETTINGS;

    int port(FramingServers servers) {
      return this == RUNTIME_SERVER ? servers.runtime.port() : servers.own.port();
    }
  }

  static Stream<Arguments> heliosAnswers() {
    return everyTargetBothWays(
        refusal(
            "401 without an identity",
            "HTTP/1.1 401 Unauthorized",
            "{\"error\":\"unauthenticated\"}",
            t -> Upload.declared("POST", messages(t.servers.sessions.get("alice")), "")),
        refusal(
            "404 for an unknown session",
            "HTTP/1.1 404 Not Found",
            "{\"error\":\"session not found\"}",
            t -> Upload.declared("POST", messages("missing"), identity("alice"))),
        refusal(
            "429 for a request over the principal's bound",
            "HTTP/1.1 429 Too Many Requests",
            "{\"error\":\"too many requests in progress\"}",
            t ->
                Upload.declared(
                    "POST", messages(t.servers.sessions.get("alice")), identity("alice"))),
        answer(
            "201 for a create that ignores its body",
            "HTTP/1.1 201 Created",
            Pattern.compile("\\{\"sessionId\":\"[^\"]+\",\"eventsUrl\":\"[^\"]+\"}"),
            t -> Upload.declared("POST", "/v1/sessions", identity("alice"))),
        refusal(
            "413 for a chunked body one byte past the bound",
            "HTTP/1.1 413 Request Entity Too Large",
            "{\"error\":\"request body too large\"}",
            t ->
                Upload.chunkedPastBound(
                    messages(t.servers.sessions.get("alice")), identity("alice"))));
  }

  @ParameterizedTest(name = "{0} on {2}, Expect: 100-continue {3}")
  @MethodSource("heliosAnswers")
  void aHeliosAnswerLeavingTheBodyUnreadIsDeliveredWholeThenEndsTheConnection(
      String name, Exchange exchange, Target target, boolean expectContinue) throws IOException {
    var port = target.port(servers);
    if (exchange.statusLine().contains("429")) {
      holdEveryRequestOfAlice(port);
    }
    var upload = exchange.upload().apply(this);
    try (var connection = RawConnection.open(port)) {
      var answer = answeredBeforeTheBody(connection, upload, expectContinue);

      assertEquals(exchange.statusLine(), answer.statusLine());
      assertTrue(exchange.body().matcher(answer.body()).matches(), answer::body);
      assertEquals("application/json", answer.headers().get("content-type"));
      assertTrue(answer.closesConnection(), () -> "headers: " + answer.headers());
      assertEquals(-1, connection.next());
    }
    assertEquals(0, servers.smuggledSeen.get());
  }

  static Stream<Arguments> helidonsAnswersThatReadTheBody() {
    return Stream.of(
            Arguments.of(
                refusal(
                    "404 for a path no route matches",
                    "HTTP/1.1 404 Not Found",
                    "Endpoint not found",
                    t -> Upload.declared("POST", UNMATCHED, identity("alice"))),
                Target.RUNTIME_SERVER),
            Arguments.of(
                refusal(
                    "404 for a path no route matches",
                    "HTTP/1.1 404 Not Found",
                    "Endpoint not found",
                    t -> Upload.declared("POST", UNMATCHED, identity("alice"))),
                Target.OWN_SERVER_WITH_THE_SETTINGS),
            Arguments.of(
                refusal(
                    "500 for an Error a route lets escape",
                    "HTTP/1.1 500 Internal Server Error",
                    "Internal Server Error",
                    t -> Upload.declared("POST", "/v1/sessions", "X-Crash: yes\r\n")),
                Target.RUNTIME_SERVER))
        .flatMap(RuntimeServerRequestFramingTest::bothWays);
  }

  @ParameterizedTest(name = "{0} on {2}, Expect: 100-continue {3}")
  @MethodSource("helidonsAnswersThatReadTheBody")
  void helidonsOwnAnswerWithinTheBoundReadsTheBodyAndKeepsTheConnection(
      String name, Exchange exchange, Target target, boolean expectContinue) throws IOException {
    var upload = exchange.upload().apply(this);
    try (var connection = RawConnection.open(target.port(servers))) {
      var answer = answeredAfterTheBody(connection, upload, expectContinue);

      assertEquals(exchange.statusLine(), answer.statusLine());
      assertTrue(exchange.body().matcher(answer.body()).matches(), answer::body);
      assertFalse(answer.closesConnection(), () -> "headers: " + answer.headers());
      assertEquals("HTTP/1.1 204 No Content", pollResult(connection).statusLine());
    }
    assertEquals(0, servers.smuggledSeen.get());
  }

  static Stream<Arguments> helidonsRefusals() {
    return Stream.of(
            refusal(
                "406 raised by Helidon",
                "HTTP/1.1 406 Not Acceptable",
                "Unable to find sink provider for request",
                t ->
                    Upload.declared(
                        "GET",
                        "/v1/sessions/" + t.servers.sessions.get("alice") + "/events",
                        identity("alice") + "Accept: application/json\r\n")),
            refusal(
                "400 without a body for an Accept header Helidon cannot parse",
                "HTTP/1.1 400 Bad Request",
                "",
                t ->
                    Upload.declared(
                        "GET",
                        "/v1/sessions/" + t.servers.sessions.get("alice") + "/events",
                        identity("alice") + "Accept: a;b;c\r\n")))
        .flatMap(exchange -> bothWays(Arguments.of(exchange, Target.RUNTIME_SERVER)));
  }

  @ParameterizedTest(name = "{0}, Expect: 100-continue {3}")
  @MethodSource("helidonsRefusals")
  void helidonsOwnRefusalWithinTheBoundRunsNothingSmuggled(
      String name, Exchange exchange, Target target, boolean expectContinue) throws IOException {
    var upload = exchange.upload().apply(this);
    try (var connection = RawConnection.open(target.port(servers))) {
      var answer = answeredAfterTheBody(connection, upload, expectContinue);

      assertEquals(exchange.statusLine(), answer.statusLine());
      assertTrue(exchange.body().matcher(answer.body()).matches(), answer::body);
      assertTrue(answer.closesConnection(), () -> "headers: " + answer.headers());
      assertEquals(-1, connection.next());
    }
    assertEquals(0, servers.smuggledSeen.get());
  }

  static Stream<Arguments> bodiesDeclaredOverTheBound() {
    return Stream.of(
            Arguments.of("a Helios route", Target.RUNTIME_SERVER),
            Arguments.of("a Helios route", Target.OWN_SERVER_WITH_THE_SETTINGS),
            Arguments.of(UNMATCHED, Target.RUNTIME_SERVER),
            Arguments.of(UNMATCHED, Target.OWN_SERVER_WITH_THE_SETTINGS))
        .flatMap(RuntimeServerRequestFramingTest::bothWays);
  }

  @ParameterizedTest(name = "{0} on {2}, Expect: 100-continue {3}")
  @MethodSource("bodiesDeclaredOverTheBound")
  void aBodyDeclaredOverTheBoundIsRefusedByHelidonUnread(
      String name, String path, Target target, boolean expectContinue) throws IOException {
    var overBound = SMUGGLED + "x".repeat(MAX_BODY + 1 - SMUGGLED.length());
    try (var connection = RawConnection.open(target.port(servers))) {
      connection.write(
          "POST "
              + (path.equals(UNMATCHED) ? path : messages(servers.sessions.get("alice")))
              + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
              + identity("alice")
              + "Content-Type: application/json\r\nContent-Length: "
              + overBound.length()
              + "\r\n"
              + (expectContinue ? "Expect: 100-continue\r\n\r\n" : "\r\n" + overBound));
      var refused = connection.response();
      if (expectContinue) {
        connection.write(overBound);
      }

      assertEquals("HTTP/1.1 413 Request Entity Too Large", refused.statusLine());
      assertTrue(refused.closesConnection(), () -> "headers: " + refused.headers());
      assertEquals(-1, connection.next());
    }
    assertEquals(0, servers.smuggledSeen.get());
  }

  @Test
  void aRequestSmuggledInTheBodyOfAHeliosRefusalIsNeverRun() throws IOException {
    try (var connection = RawConnection.open(servers.runtime.port())) {
      connection.write(expectingContinue("/v1/sessions/missing/messages", ""));
      assertEquals("HTTP/1.1 100 Continue", connection.response().statusLine());
      var refused = connection.response();
      connection.write(SMUGGLED);

      assertEquals("HTTP/1.1 401 Unauthorized", refused.statusLine());
      assertEquals(-1, connection.next());
    }
    assertEquals(0, servers.smuggledSeen.get());
    assertEquals(3, servers.registry.size());
  }

  @Test
  void aRequestSmuggledInTheBodyOfAnUnmatchedPathIsNeverRun() throws IOException {
    try (var connection = RawConnection.open(servers.runtime.port())) {
      connection.write(expectingContinue(UNMATCHED, identity("alice")));
      assertEquals("HTTP/1.1 100 Continue", connection.response().statusLine());
      connection.write(SMUGGLED);
      var refused = connection.response();

      assertEquals("HTTP/1.1 404 Not Found", refused.statusLine());
      assertEquals("HTTP/1.1 204 No Content", pollResult(connection).statusLine());
    }
    assertEquals(0, servers.smuggledSeen.get());
    assertEquals(3, servers.registry.size());
  }

  @Test
  void theServiceOnAServerWithoutTheSettingsStillEndsTheConnectionsItsRoutesLeaveUnread()
      throws IOException {
    var deployed = servers.deploy(FramingServers.registry(), 512, builder -> {});
    try (var connection = RawConnection.open(deployed.port())) {
      connection.write(expectingContinue("/v1/sessions/missing/messages", ""));
      var refused = connection.response();
      connection.write(SMUGGLED);

      assertEquals("HTTP/1.1 401 Unauthorized", refused.statusLine());
      assertTrue(refused.closesConnection(), () -> "headers: " + refused.headers());
      assertEquals(-1, connection.next());
    } finally {
      deployed.stop();
    }
    assertEquals(0, servers.smuggledSeen.get());
  }

  @Test
  void requestsWhoseBodiesAreReadOrAbsentKeepTheirConnection() throws IOException {
    var alice = servers.sessions.get("alice");
    try (var connection = RawConnection.open(servers.runtime.port())) {
      var answers = new ArrayList<RawConnection.Response>();
      for (var body : List.of("{\"text\":\"one\"}", "{\"text\":", "{\"text\":\"two\"}")) {
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
              "HTTP/1.1 400 Bad Request",
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
    try (var connection = RawConnection.open(servers.runtime.port())) {
      var answers = new ArrayList<RawConnection.Response>();
      for (var accept : List.of("*/*", "*/*", "application/json")) {
        connection.write(
            "GET /v1/sessions/"
                + servers.sessions.get("alice")
                + "/events/elsewhere HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + identity("alice")
                + "Accept: "
                + accept
                + "\r\n\r\n");
        answers.add(connection.response());
      }
      connection.write(
          "GET /v1/sessions/"
              + servers.sessions.get("alice")
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

  @Test
  void aServerAtHelidonsRequestCapAnswersOneMore503AndEndsItsConnection() throws IOException {
    var own = FramingServers.registry();
    var alice = new Principal("acme", "alice");
    var session =
        (StubSession) own.create(FramingServers.options(new SessionRequest("held", alice)), alice);
    var deployed = servers.deploy(own, 1, builder -> {});
    try (var stream = RawConnection.open(deployed.port());
        var refused = RawConnection.open(deployed.port())) {
      stream.write(
          "GET /v1/sessions/held/events HTTP/1.1\r\nHost: 127.0.0.1\r\n"
              + identity("alice")
              + "\r\n");
      assertEquals("HTTP/1.1 200 OK", stream.response().statusLine());
      refused.write(
          "GET /v1/sessions/held/result?timeout=0 HTTP/1.1\r\nHost: 127.0.0.1\r\n"
              + identity("alice")
              + "\r\n");

      var answer = refused.response();

      assertEquals("HTTP/1.1 503 Service Unavailable", answer.statusLine());
      assertEquals(-1, refused.next());
      session.completeEvents();
    } finally {
      deployed.stop();
    }
  }

  static Stream<String> pathsOfARefusedLargeBody() {
    return Stream.of("a Helios route", UNMATCHED);
  }

  /**
   * The server's end of the connection reads the head and, in the same read, at most one 8 KiB
   * socket buffer of the body, counted from the JDK's {@code jdk.SocketRead} events for the socket
   * whose peer is the client; a server that drained would read all 64 MiB.
   */
  @ParameterizedTest(name = "to {0}")
  @MethodSource("pathsOfARefusedLargeBody")
  void aRefusedBodyLargerThanTheSocketBuffersIsNotRead(String path) throws IOException {
    var head =
        "POST "
            + (path.equals(UNMATCHED) ? path : messages(servers.sessions.get("alice")))
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
      try (var connection = RawConnection.open(servers.runtime.port())) {
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
        serverRead >= head.length() && serverRead <= head.length() + SERVER_READ_BUFFER,
        () -> serverRead + " bytes were read");
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

  /**
   * Send {@code upload} and read the answer a route gives before it reads the body; with {@code
   * Expect: 100-continue}, the server's {@code 100 Continue} comes first, at once.
   */
  private static RawConnection.Response answeredBeforeTheBody(
      RawConnection connection, Upload upload, boolean expectContinue) throws IOException {
    if (!expectContinue) {
      connection.write(upload.head() + "\r\n" + upload.readable() + upload.rest());
      return connection.response();
    }
    connection.write(upload.head() + "Expect: 100-continue\r\n\r\n");
    assertEquals("HTTP/1.1 100 Continue", connection.response().statusLine());
    connection.write(upload.readable());
    var answer = connection.response();
    connection.write(upload.rest());
    return answer;
  }

  /**
   * Send {@code upload} whole and read the answer; Helidon holds its own answers until the body has
   * arrived.
   */
  private static RawConnection.Response answeredAfterTheBody(
      RawConnection connection, Upload upload, boolean expectContinue) throws IOException {
    if (!expectContinue) {
      connection.write(upload.head() + "\r\n" + upload.readable() + upload.rest());
      return connection.response();
    }
    connection.write(upload.head() + "Expect: 100-continue\r\n\r\n");
    assertEquals("HTTP/1.1 100 Continue", connection.response().statusLine());
    connection.write(upload.readable() + upload.rest());
    return connection.response();
  }

  /** A create's head declaring the smuggled request as its body, sent with {@code Expect}. */
  private static String expectingContinue(String path, String headers) {
    return "POST "
        + path
        + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + headers
        + "Content-Type: application/json\r\nExpect: 100-continue\r\nContent-Length: "
        + SMUGGLED.length()
        + "\r\n\r\n";
  }

  /** Ask for alice's result on {@code connection}, which a live session answers {@code 204}. */
  private RawConnection.Response pollResult(RawConnection connection) throws IOException {
    connection.write(
        "GET /v1/sessions/"
            + servers.sessions.get("alice")
            + "/result?timeout=0 HTTP/1.1\r\nHost: 127.0.0.1\r\n"
            + identity("alice")
            + "\r\n");
    return connection.response();
  }

  /** Hold event streams of alice open on {@code port}, every request her bound admits. */
  private void holdEveryRequestOfAlice(int port) {
    for (var i = 0; i < REQUESTS_PER_PRINCIPAL; i++) {
      Await.until("an event stream to be admitted and held", () -> heldStream(port));
    }
  }

  /**
   * Open an event stream of alice and keep it once admitted. A request gives its slot back just
   * after its answer is sent, so one sent as soon as the earlier answer arrived may still be
   * refused.
   */
  private boolean heldStream(int port) {
    try {
      var held = RawConnection.open(port);
      held.write(
          "GET /v1/sessions/"
              + servers.sessions.get("alice")
              + "/events HTTP/1.1\r\nHost: 127.0.0.1\r\n"
              + identity("alice")
              + "\r\n");
      var answer = held.response();
      if (answer.statusLine().equals("HTTP/1.1 200 OK")) {
        heldStreams.add(held);
        return true;
      }
      held.close();
      assertEquals("HTTP/1.1 429 Too Many Requests", answer.statusLine());
      return false;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Stream<Arguments> everyTargetBothWays(Exchange... exchanges) {
    return Stream.of(exchanges)
        .flatMap(
            exchange -> Stream.of(Target.values()).map(target -> Arguments.of(exchange, target)))
        .flatMap(RuntimeServerRequestFramingTest::bothWays);
  }

  /** {@code arguments} with and without {@code Expect: 100-continue}, its name first. */
  private static Stream<Arguments> bothWays(Arguments arguments) {
    var given = arguments.get();
    return Stream.of(false, true)
        .map(
            expect -> {
              var all = new Object[given.length + 2];
              all[0] = given[0].toString();
              System.arraycopy(given, 0, all, 1, given.length);
              all[all.length - 1] = expect;
              return Arguments.of(all);
            });
  }

  /**
   * One socket read, as the JDK records it.
   *
   * @param peerPort the port of the socket's peer
   * @param bytes the bytes the read returned
   */
  record SocketRead(int peerPort, long bytes) {}

  private static Exchange refusal(
      String name,
      String statusLine,
      String body,
      Function<RuntimeServerRequestFramingTest, Upload> upload) {
    return new Exchange(name, statusLine, Pattern.compile(Pattern.quote(body)), upload);
  }

  private static Exchange answer(
      String name,
      String statusLine,
      Pattern body,
      Function<RuntimeServerRequestFramingTest, Upload> upload) {
    return new Exchange(name, statusLine, body, upload);
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
      String name,
      String statusLine,
      Pattern body,
      Function<RuntimeServerRequestFramingTest, Upload> upload) {

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
