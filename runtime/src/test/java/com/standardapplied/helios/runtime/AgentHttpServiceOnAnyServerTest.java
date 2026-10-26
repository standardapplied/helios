/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link AgentHttpService} answers byte for byte the same on a {@link RuntimeServer}, on a plain
 * Helidon server with no media support at all, and on one built with Helidon's defaults: Helios
 * writes its own JSON, so no media support needs to be registered.
 */
final class AgentHttpServiceOnAnyServerTest {

  private static final Principal ALICE = new Principal("acme", "alice");

  private static final int REQUESTS_PER_PRINCIPAL = 2;

  private static final RequestLimits LIMITS =
      new RequestLimits(RequestLimits.defaults().maxRequestBytes(), REQUESTS_PER_PRINCIPAL);

  private static final Pattern CREATED =
      Pattern.compile(
          "\\{\"sessionId\":\"(sess-[^\"]+)\",\"eventsUrl\":\"/v1/sessions/\\1/events\"}");

  private final Authenticator authenticator =
      TrustedProxyAuthenticator.newBuilder()
          .withTrustedProxy(InetAddress.getLoopbackAddress())
          .build();
  private final List<StubSession> sessions = new CopyOnWriteArrayList<>();
  private final SessionRegistry registry =
      SessionRegistry.newBuilder()
          .withFactory(
              options -> {
                var session = new StubSession(options.sessionId());
                sessions.add(session);
                return session;
              })
          .build();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
  private final List<InputStream> streams = new ArrayList<>();
  private Runnable stopServer = () -> {};
  private int port;

  /** Where the service is mounted. */
  enum Target {
    RUNTIME_SERVER,
    PLAIN_SERVER_WITHOUT_MEDIA_SUPPORT,
    PLAIN_SERVER_WITH_DEFAULTS
  }

  @AfterEach
  void stop() throws IOException {
    sessions.forEach(StubSession::completeEvents);
    for (var stream : streams) {
      stream.close();
    }
    registry.closeAll();
    stopServer.run();
    http.shutdownNow();
  }

  @ParameterizedTest
  @EnumSource(Target.class)
  void everyAnswerIsTheSameJsonOnAnyServer(Target target) {
    start(target);

    var failed = send("POST", "/sessions", "broken");
    assertJson(500, "{\"error\":\"session options factory failed\"}", failed);
    assertEquals(0, registry.size());

    var created = send("POST", "/sessions", "alice");
    assertEquals(201, created.statusCode());
    assertEquals(Optional.of("application/json"), created.headers().firstValue("Content-Type"));
    var matched = CREATED.matcher(created.body());
    assertTrue(matched.matches(), created.body());
    var sessionId = matched.group(1);
    assertEquals(1, registry.size());
    var session = (StubSession) registry.find(sessionId, ALICE).orElseThrow();

    var denied = send("GET", "/sessions/" + sessionId + "/result?timeout=0", null);
    assertJson(401, "{\"error\":\"unauthenticated\"}", denied);
    assertEquals(
        Optional.of(authenticator.challenge()), denied.headers().firstValue("WWW-Authenticate"));

    assertJson(
        404,
        "{\"error\":\"session not found\"}",
        send("GET", "/sessions/missing/result?timeout=0", "alice"));

    var events = openEvents(sessionId, "alice");
    session
        .streams
        .getFirst()
        .push(new QueryEvent.AssistantText(sessionId, 0L, Instant.EPOCH, "hello"));
    assertEquals(
        List.of(
            "event:AssistantText",
            "data:{\"sessionId\":\""
                + sessionId
                + "\",\"turnIndex\":0,\"timestamp\":\"1970-01-01T00:00:00Z\",\"text\":\"hello\"}",
            ""),
        lines(events, 3));

    session.terminate();
    var result = send("GET", "/sessions/" + sessionId + "/result?timeout=0", "alice");
    assertJson(
        200,
        "{\"type\":\"Success\",\"result\":{\"sessionId\":\""
            + sessionId
            + "\",\"result\":\"ok\",\"usage\":{\"inputTokens\":0,\"outputTokens\":0,"
            + "\"cacheCreationInputTokens\":0,\"cacheReadInputTokens\":0,\"totalTokens\":0},"
            + "\"cost\":{\"microUsd\":0},\"duration\":\"PT0S\",\"citations\":[]}}",
        result);

    var refused = refusedWithEveryRequestOfDaveHeld();
    assertJson(429, "{\"error\":\"too many requests in progress\"}", refused);
    assertEquals(Optional.of("5"), refused.headers().firstValue("Retry-After"));
  }

  /** Hold every request dave's bound admits with event streams, then send him one more. */
  private HttpResponse<String> refusedWithEveryRequestOfDaveHeld() {
    var created = CREATED.matcher(send("POST", "/sessions", "dave").body());
    assertTrue(created.matches());
    var sessionId = created.group(1);
    for (var i = 0; i < REQUESTS_PER_PRINCIPAL; i++) {
      Await.until("an event stream of dave to be admitted", () -> heldStream(sessionId));
    }
    return send("GET", "/sessions/" + sessionId + "/result?timeout=0", "dave");
  }

  /**
   * Open an event stream of dave and keep it once admitted. A request gives its slot back just
   * after its answer is sent, so one sent as soon as the earlier answer arrived may still be
   * refused.
   */
  private boolean heldStream(String sessionId) {
    var resp =
        Await.value(
            "the event stream to be answered",
            http.sendAsync(
                request("GET", "/sessions/" + sessionId + "/events", "dave"),
                BodyHandlers.ofInputStream()));
    streams.add(resp.body());
    if (resp.statusCode() == 200) {
      return true;
    }
    assertEquals(429, resp.statusCode());
    return false;
  }

  private BufferedReader openEvents(String sessionId, String subject) {
    var resp =
        Await.value(
            "the event stream to be answered",
            http.sendAsync(
                request("GET", "/sessions/" + sessionId + "/events", subject),
                BodyHandlers.ofInputStream()));
    assertEquals(200, resp.statusCode());
    assertEquals(Optional.of("text/event-stream"), resp.headers().firstValue("Content-Type"));
    streams.add(resp.body());
    var reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
    assertEquals(List.of("event:Ready", "data:{}", ""), lines(reader, 3));
    return reader;
  }

  private static List<String> lines(BufferedReader reader, int count) {
    return Await.value(
        "the stream's next lines",
        CompletableFuture.supplyAsync(
            () -> {
              var lines = new ArrayList<String>();
              for (var i = 0; i < count; i++) {
                lines.add(line(reader));
              }
              return lines;
            }));
  }

  private static String line(BufferedReader reader) {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void assertJson(int status, String body, HttpResponse<String> resp) {
    assertEquals(status, resp.statusCode(), resp::body);
    assertEquals(Optional.of("application/json"), resp.headers().firstValue("Content-Type"));
    assertEquals(body, resp.body());
  }

  private HttpResponse<String> send(String method, String path, String subject) {
    return Await.value(
        "the request to be answered",
        http.sendAsync(request(method, path, subject), BodyHandlers.ofString()));
  }

  private HttpRequest request(String method, String path, String subject) {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1" + path))
            .timeout(Await.HANG_GUARD)
            .method(method, BodyPublishers.noBody());
    if (subject != null) {
      request
          .header(TrustedProxyAuthenticator.DEFAULT_TENANT_HEADER, "acme")
          .header(TrustedProxyAuthenticator.DEFAULT_SUBJECT_HEADER, subject);
    }
    return request.build();
  }

  private void start(Target target) {
    Function<SessionRequest, SessionOptions> options =
        request -> {
          if (request.owner().subject().equals("broken")) {
            throw new IllegalStateException("options factory failed");
          }
          return SessionOptions.newBuilder()
              .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
              .withSessionId(request.sessionId())
              .build();
        };
    if (target == Target.RUNTIME_SERVER) {
      var server =
          RuntimeServer.builder()
              .withRegistry(registry)
              .withOptionsFactory(options)
              .withRequestLimits(LIMITS)
              .withAuthenticator(authenticator)
              .build();
      stopServer = server::close;
      port = server.port();
      return;
    }
    var service = new AgentHttpService(registry, options, authenticator, "/v1", LIMITS);
    var builder =
        WebServer.builder()
            .host("127.0.0.1")
            .port(0)
            .routing((HttpRouting.Builder routing) -> routing.register("/v1", service));
    if (target == Target.PLAIN_SERVER_WITHOUT_MEDIA_SUPPORT) {
      builder
          .featuresDiscoverServices(false)
          .mediaContext(mc -> mc.mediaSupportsDiscoverServices(false));
    }
    var server = builder.build().start();
    stopServer = server::stop;
    port = server.port();
  }
}
