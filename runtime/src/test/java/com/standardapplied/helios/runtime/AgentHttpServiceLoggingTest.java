/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import io.helidon.http.HeaderNames;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.sse.SseSink;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Characterizes what the HTTP surface logs: every warning goes to {@link AgentHttpService}'s
 * logger, whichever type does the work, so one logging configuration covers every route, and an
 * authenticator's failure is logged by type only, since its message may quote the credential.
 */
final class AgentHttpServiceLoggingTest {

  private static final String SESSION_ID = "sess-logging";
  private static final QueryEvent EVENT =
      new QueryEvent.AssistantText(SESSION_ID, 0L, Instant.EPOCH, "hello");

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
  private final LogCapture log = LogCapture.of(AgentHttpService.class);
  private final SessionRegistry registry = SessionRegistry.inMemory();

  @AfterEach
  void releaseTheLog() {
    log.close();
    http.shutdownNow();
  }

  @Test
  void aFailedEmitIsLoggedAsAWarningWithItsCause() {
    var failure = new IllegalStateException("sink broke");
    var stream = new SessionEventStream(SESSION_ID, failingSink(failure), new ObjectMapper());

    stream.onNext(EVENT);

    var logged = onlyRecordForTheSession();
    assertEquals(Level.WARNING, logged.getLevel());
    assertEquals("SSE emit failed for session " + SESSION_ID, logged.getMessage());
    assertSame(failure, logged.getThrown());
  }

  @Test
  void aDisconnectIsNotLogged() {
    var stream =
        new SessionEventStream(
            SESSION_ID,
            failingSink(new CloseConnectionException("peer closed")),
            new ObjectMapper());

    stream.onNext(EVENT);

    assertTrue(recordsForTheSession().isEmpty(), () -> "logged: " + recordsForTheSession());
  }

  @Test
  void aPublisherErrorIsLoggedAsAWarningWithTheError() {
    var error = new IllegalStateException("publisher broke");
    var stream = new SessionEventStream(SESSION_ID, failingSink(error), new ObjectMapper());

    stream.onError(error);

    var logged = onlyRecordForTheSession();
    assertEquals(Level.WARNING, logged.getLevel());
    assertEquals("events publisher errored for session " + SESSION_ID, logged.getMessage());
    assertSame(error, logged.getThrown());
  }

  @Test
  void aFailedResultIsLoggedAsAWarningWithTheFailure() {
    var failure = new IllegalStateException("backend exploded");
    var future = new CompletableFuture<ResultMessage>();
    future.completeExceptionally(failure);

    ResultLongPoll.awaitResult(future, new CompletableFuture<>(), 5L, SESSION_ID);

    var logged = onlyRecordForTheSession();
    assertEquals(Level.WARNING, logged.getLevel());
    assertEquals(
        "session " + SESSION_ID + " result future failed exceptionally", logged.getMessage());
    assertSame(failure, assertInstanceOf(CompletionException.class, logged.getThrown()).getCause());
  }

  @Test
  void anAuthenticatorThatCannotDecideIsAnswered503AndLoggedByTypeOnly() throws Exception {
    var secret = "s3cr3t-credential";
    var unavailable = "{\"error\":\"authentication unavailable\"}";
    try (var server =
        serverWithFactory(
                request -> {
                  throw new AssertionError("no session may be created");
                })
            .withAuthenticator(
                new TestAuthenticator(
                    request -> {
                      if (request.headers().contains(HeaderNames.AUTHORIZATION)) {
                        throw new IllegalStateException("cannot verify " + secret);
                      }
                      return null;
                    }))
            .build()) {
      var throwing = createSession(server, "Bearer " + secret);
      var answeringNull = createSession(server, null);

      assertEquals(503, throwing.statusCode());
      assertEquals(unavailable, throwing.body());
      assertEquals(503, answeringNull.statusCode());
      assertEquals(unavailable, answeringNull.body());
      assertEquals(0, registry.size());
    }
    var failures = recordsStartingWith("authenticator");
    assertEquals(
        List.of(
            "authenticator failed with java.lang.IllegalStateException",
            "authenticator failed with java.lang.NullPointerException"),
        failures.stream().map(LogRecord::getMessage).toList());
    failures.forEach(r -> assertNull(r.getThrown()));
    assertTrue(
        log.records().stream().noneMatch(r -> String.valueOf(r.getMessage()).contains(secret)));
  }

  @Test
  void aFailedOptionsFactoryIsLoggedAsAWarningWithItsException() throws Exception {
    var failure = new IllegalStateException("factory broke");
    try (var server =
        serverWithFactory(
                request -> {
                  throw failure;
                })
            .withoutAuthentication()
            .build()) {
      assertEquals(500, createSession(server, null).statusCode());
    }
    var logged = onlyRecordStartingWith("optionsFactory failed for session sess-");
    assertEquals(Level.WARNING, logged.getLevel());
    assertSame(failure, logged.getThrown());
  }

  @Test
  void anOptionsFactoryReturningNoOptionsForTheSessionIsLoggedAsAWarning() throws Exception {
    try (var server = serverWithFactory(request -> null).withoutAuthentication().build()) {
      assertEquals(500, createSession(server, null).statusCode());
    }
    var logged = onlyRecordStartingWith("optionsFactory did not return options for session sess-");
    assertEquals(Level.WARNING, logged.getLevel());
  }

  private RuntimeServer.Builder serverWithFactory(
      Function<SessionRequest, SessionOptions> optionsFactory) {
    return RuntimeServer.builder().withRegistry(registry).withOptionsFactory(optionsFactory);
  }

  private HttpResponse<String> createSession(RuntimeServer server, String authorization)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/sessions"))
            .timeout(Await.HANG_GUARD)
            .POST(HttpRequest.BodyPublishers.noBody());
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return http.send(request.build(), BodyHandlers.ofString());
  }

  private List<LogRecord> recordsStartingWith(String prefix) {
    return log.records().stream().filter(r -> r.getMessage().startsWith(prefix)).toList();
  }

  private LogRecord onlyRecordStartingWith(String prefix) {
    var matching = recordsStartingWith(prefix);
    assertEquals(1, matching.size(), () -> "logged: " + matching);
    return matching.getFirst();
  }

  private LogRecord onlyRecordForTheSession() {
    var forTheSession = recordsForTheSession();
    assertEquals(1, forTheSession.size(), () -> "logged: " + forTheSession);
    var logged = forTheSession.getFirst();
    assertEquals(AgentHttpService.class.getName(), logged.getLoggerName());
    return logged;
  }

  private List<LogRecord> recordsForTheSession() {
    return log.records().stream().filter(r -> r.getMessage().contains(SESSION_ID)).toList();
  }

  private static SseSink failingSink(RuntimeException failure) {
    return new SseSink() {
      @Override
      public SseSink emit(SseEvent event) {
        throw failure;
      }

      @Override
      public void close() {}
    };
  }
}
