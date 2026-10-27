/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.EventStream;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import io.helidon.webserver.http.ErrorHandlers;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a route that fails is answered: with a fixed {@code 500} that quotes nothing, unless Helidon
 * raised the failure with its own status, or the response is already under way and only Helidon can
 * end it.
 */
final class AgentHttpServiceFailureTest {

  private static final String BROKEN = "event stream broke: password=hunter22";

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
  private final JsonMapper mapper = JsonMapper.builder().build();

  @AfterEach
  void shutDownHttpClient() {
    http.shutdownNow();
  }

  @Test
  void aRouteThatFailsAnswers500WithoutTheFailuresMessageAndLogsTheFailure() throws Exception {
    var failure = new IllegalStateException("cannot open store: password=hunter22");
    try (var log = LogCapture.of(AgentHttpService.class);
        var server =
            server(
                options -> {
                  throw failure;
                })) {
      var resp = send(HttpRequest.newBuilder(uri(server, "/sessions")).POST(json()));

      assertEquals(500, resp.statusCode());
      assertEquals("{\"error\":\"internal server error\"}", resp.body());
      var logged = log.records().stream().filter(r -> r.getThrown() == failure).toList();
      assertEquals(1, logged.size(), () -> "logged: " + log.records());
      assertEquals(Level.WARNING, logged.getFirst().getLevel());
      assertEquals("request failed", logged.getFirst().getMessage());
    }
  }

  @Test
  void aFailureRaisedByHelidonKeepsItsStatus() throws Exception {
    try (var server = server(AgentSession::open)) {
      var sessionId = create(server);

      var resp =
          send(
              HttpRequest.newBuilder(uri(server, "/sessions/" + sessionId + "/events"))
                  .header("Accept", "application/json"));

      assertEquals(406, resp.statusCode());
    }
  }

  @Test
  void aStreamThatFailsAfterItOpenedIsLeftToHelidonWithItsOwnFailure() throws Exception {
    try (var helidonErrors = LogCapture.of(ErrorHandlers.class);
        var server = server(options -> withBrokenEvents(AgentSession.open(options)))) {
      var sessionId = create(server);
      var stream =
          CompletableFuture.runAsync(
              () ->
                  sendIgnoringDisconnect(
                      HttpRequest.newBuilder(uri(server, "/sessions/" + sessionId + "/events"))));
      Await.value("the broken stream to end", stream);

      Await.until(
          "Helidon to report the stream's own failure",
          () ->
              helidonErrors.records().stream()
                  .map(LogRecord::getThrown)
                  .anyMatch(AgentHttpServiceFailureTest::isTheBrokenStream));
    }
  }

  private static boolean isTheBrokenStream(Throwable reported) {
    for (var cause = reported; cause != null; cause = cause.getCause()) {
      if (BROKEN.equals(cause.getMessage())) {
        return true;
      }
    }
    return false;
  }

  private static AgentSession withBrokenEvents(AgentSession session) {
    return (AgentSession)
        Proxy.newProxyInstance(
            AgentSession.class.getClassLoader(),
            new Class<?>[] {AgentSession.class},
            (proxy, method, args) -> {
              if (method.getName().equals("events")) {
                return new EventStream() {
                  @Override
                  public Iterator<QueryEvent> iterator() {
                    throw new IllegalStateException(BROKEN);
                  }

                  @Override
                  public void close() {}
                };
              }
              try {
                return method.invoke(session, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static RuntimeServer server(Function<SessionOptions, AgentSession> sessions) {
    return RuntimeServer.builder()
        .withRegistry(SessionRegistry.newBuilder().withFactory(sessions).build())
        .withOptionsFactory(
            request ->
                SessionOptions.newBuilder()
                    .withModel(ScriptedModel.newBuilder().withTextTurn("done").build())
                    .withSessionId(request.sessionId())
                    .build())
        .withoutAuthentication()
        .build();
  }

  private String create(RuntimeServer server) throws Exception {
    var resp = send(HttpRequest.newBuilder(uri(server, "/sessions")).POST(json()));
    assertEquals(201, resp.statusCode(), resp::body);
    return (String) mapper.readValue(resp.body(), Map.class).get("sessionId");
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return http.send(request.timeout(Await.HANG_GUARD).build(), BodyHandlers.ofString());
  }

  private void sendIgnoringDisconnect(HttpRequest.Builder request) {
    try {
      send(request);
    } catch (IOException e) {
      return;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpRequest.BodyPublisher json() {
    return HttpRequest.BodyPublishers.ofString("{}");
  }

  private static URI uri(RuntimeServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.port() + "/v1" + path);
  }
}
