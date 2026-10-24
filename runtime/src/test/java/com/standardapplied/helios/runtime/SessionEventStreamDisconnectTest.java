/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A client that goes away from its event stream frees the stream's admission at the stream's next
 * event, without a log record: Helidon reports the failed write as a disconnect by type, whether
 * the client closed its socket or reset it. Until that event, the stream keeps its admission.
 */
final class SessionEventStreamDisconnectTest {

  private static final Principal ALICE = new Principal("acme", "alice");

  private static final String ALICE_HEADERS =
      "X-Authenticated-Tenant: acme\r\nX-Authenticated-Subject: alice\r\n";

  private final SessionRegistry registry =
      SessionRegistry.newBuilder()
          .withFactory(options -> new StubSession(options.sessionId()))
          .build();

  private final RuntimeServer server =
      RuntimeServer.builder()
          .withRegistry(registry)
          .withOptionsFactory(request -> options(request.sessionId()))
          .withRequestLimits(new RequestLimits(256 * 1024, 1))
          .withAuthenticator(
              TrustedProxyAuthenticator.newBuilder()
                  .withTrustedProxy(InetAddress.getLoopbackAddress())
                  .build())
          .build();

  @AfterEach
  void stop() {
    server.close();
  }

  @ParameterizedTest(name = "client reset {0}")
  @ValueSource(booleans = {false, true})
  void aDisconnectedStreamFreesItsAdmissionAtItsNextEventWithoutALogRecord(boolean reset)
      throws IOException {
    var session = (StubSession) registry.create(options("sess-held"), ALICE);
    var stream = admittedStream(session.sessionId());
    if (reset) {
      stream.reset();
    } else {
      stream.close();
    }

    try (var refused = RawConnection.open(server.port())) {
      refused.write(eventsRequest(session.sessionId()));
      assertEquals("HTTP/1.1 429 Too Many Requests", refused.response().statusLine());
    }

    List<LogRecord> logged;
    try (var log = LogCapture.of(AgentHttpService.class)) {
      var event = new QueryEvent.AssistantText(session.sessionId(), 0L, Instant.EPOCH, "after");
      Await.until(
          "the stream's subscription to be cancelled",
          () -> {
            session.subscribers.getFirst().onNext(event);
            return session.cancels.get() > 0;
          });
      logged = log.records();
    }

    assertEquals(List.of(), logged);
    admittedStream(session.sessionId()).close();
    session.completeEvents();
  }

  /** Open an event stream of alice once her one request slot is free, and read its Ready event. */
  private RawConnection admittedStream(String sessionId) {
    var admitted = new RawConnection[1];
    Await.until(
        "an event stream to be admitted",
        () -> {
          try {
            var connection = RawConnection.open(server.port());
            connection.write(eventsRequest(sessionId));
            var answer = connection.response();
            if (answer.statusLine().equals("HTTP/1.1 200 OK")) {
              connection.readUntil("event:Ready\ndata:{}\n\n");
              admitted[0] = connection;
              return true;
            }
            connection.close();
            assertEquals("HTTP/1.1 429 Too Many Requests", answer.statusLine());
            return false;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    return admitted[0];
  }

  private static String eventsRequest(String sessionId) {
    return "GET /v1/sessions/"
        + sessionId
        + "/events HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + ALICE_HEADERS
        + "\r\n";
  }

  private static SessionOptions options(String sessionId) {
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
        .withSessionId(sessionId)
        .build();
  }
}
