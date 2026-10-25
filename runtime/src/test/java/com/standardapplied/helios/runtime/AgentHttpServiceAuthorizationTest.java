/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that every route of the runtime acts only for an authenticated caller and only on the
 * caller's own sessions. Bearer tokens name identities this test's {@link Authenticator} holds; a
 * token it does not hold is a forged one.
 */
final class AgentHttpServiceAuthorizationTest {

  private static final Principal ALICE = new Principal("acme", "alice");
  private static final Principal CAROL = new Principal("acme", "carol");
  private static final String NOT_FOUND = "{\"error\":\"session not found\"}";
  private static final String UNAUTHENTICATED = "{\"error\":\"unauthenticated\"}";

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
  private final AtomicInteger toolRuns = new AtomicInteger();
  private final ScriptedModel model =
      ScriptedModel.newBuilder()
          .withToolCallsTurn(new ToolCall("c1", "probe", Map.of()))
          .withTextTurn("done")
          .build();
  private final CompletableFuture<Void> carolRevoked = new CompletableFuture<>();
  private final CompletableFuture<Void> uploadRevoked = new CompletableFuture<>();
  private final Map<String, Authentication> identities =
      Map.of(
          "alice",
          Authentication.of(ALICE),
          "bob",
          Authentication.of(new Principal("acme", "bob")),
          "eve",
          Authentication.of(new Principal("globex", "alice")),
          "carol",
          new Authentication(CAROL, Duration.ofDays(1), carolRevoked),
          "carol-upload",
          new Authentication(CAROL, Duration.ofDays(1), uploadRevoked),
          "expired",
          new Authentication(ALICE, Duration.ZERO, new CompletableFuture<>()),
          "revoked",
          new Authentication(ALICE, Duration.ofDays(1), CompletableFuture.completedFuture(null)));
  private final AtomicReference<String> hijackedId = new AtomicReference<>();
  private final AtomicReference<Runnable> duringFactory = new AtomicReference<>(() -> {});
  private final SessionRegistry registry = SessionRegistry.inMemory();
  private RuntimeServer server;

  @BeforeEach
  void startServer() {
    server =
        RuntimeServer.builder()
            .withRegistry(registry)
            .withOptionsFactory(this::options)
            .withAuthenticator(new TestAuthenticator(this::authenticate))
            .build();
  }

  @AfterEach
  void stopServer() {
    server.close();
    http.shutdownNow();
  }

  @Test
  void theOwnerReachesItsSessionOnEveryRoute() throws Exception {
    var sessionId = create("alice");
    var events = openEvents(sessionId, "alice");

    assertEquals(202, send("POST", messages(sessionId), "alice").statusCode());
    var streamed = events.awaitEnd();
    var result = send("GET", "/sessions/" + sessionId + "/result", "alice");
    var interrupt = send("POST", interrupt(sessionId), "alice");
    var delete = send("DELETE", "/sessions/" + sessionId, "alice");

    assertTrue(streamed.endsWith("event:LoopEnded"), () -> "streamed: " + streamed);
    assertEquals(200, result.statusCode());
    assertEquals(Optional.of("application/json"), result.headers().firstValue("Content-Type"));
    assertTrue(result.body().contains("\"result\":\"done\""), result::body);
    assertEquals(409, interrupt.statusCode());
    assertEquals(204, delete.statusCode());
    assertEquals(1, toolRuns.get());
    assertEquals(2, model.calls().size());
  }

  @Test
  void anotherSubjectOrTenantCannotReachTheSessionOnAnyRoute() throws Exception {
    var sessionId = create("alice");

    for (var intruder : List.of("bob", "eve")) {
      for (var route : sessionRoutes(sessionId)) {
        var foreign = send(route.method(), route.path(), intruder);
        var unknown =
            send(route.method(), route.path().replace(sessionId, "sess-unknown"), intruder);
        assertEquals(404, foreign.statusCode(), () -> intruder + " " + route);
        assertEquals(NOT_FOUND, foreign.body(), () -> intruder + " " + route);
        assertEquals(unknown.statusCode(), foreign.statusCode(), () -> intruder + " " + route);
        assertEquals(unknown.body(), foreign.body(), () -> intruder + " " + route);
      }
    }

    assertTrue(model.calls().isEmpty());
    assertEquals(0, toolRuns.get());
    assertTrue(registry.find(sessionId, ALICE).isPresent());
  }

  @Test
  void missingMalformedExpiredRevokedAndForgedCredentialsFailBeforeAnyWork() throws Exception {
    var sessionId = create("alice");
    var credentials =
        new String[] {
          null,
          "alice",
          "Bearer ",
          "Basic YWxpY2U6eA==",
          "Bearer alice2",
          "Bearer expired",
          "Bearer revoked"
        };
    var routes = new ArrayList<>(sessionRoutes(sessionId));
    routes.add(new Route("POST", "/sessions", "{}"));

    for (var credential : credentials) {
      for (var route : routes) {
        assertUnauthenticated(sendAuthorization(route, credential), credential + " " + route);
      }
    }

    assertTrue(model.calls().isEmpty());
    assertEquals(0, toolRuns.get());
    assertEquals(1, registry.size());
    assertTrue(registry.find(sessionId, ALICE).isPresent());
  }

  @Test
  void revokingTheIdentityEndsAnOpenEventStreamWithoutAFurtherEvent() throws Exception {
    var sessionId = create("carol");
    var events = openEvents(sessionId, "carol");

    carolRevoked.complete(null);

    assertEquals("", events.awaitEnd());
    var session = registry.find(sessionId, CAROL).orElseThrow();
    assertFalse(session.result().isDone());
  }

  @Test
  void revokingTheIdentityAnswersAPendingResultLongPollWith401() throws Exception {
    var sessionId = create("carol");
    var result = registry.find(sessionId, CAROL).orElseThrow().result();
    var watchersBeforePoll = result.getNumberOfDependents();
    var poll =
        CompletableFuture.supplyAsync(
            () -> sendUnchecked("GET", "/sessions/" + sessionId + "/result?timeout=300", "carol"));
    Await.until(
        "the long poll to wait on the result",
        () -> result.getNumberOfDependents() > watchersBeforePoll);

    carolRevoked.complete(null);

    assertUnauthenticated(Await.value("the long poll to be answered", poll), "pending long poll");
  }

  @Test
  void answeredRequestsLeaveNothingWatchingTheirCredentialsRevocation() throws Exception {
    var sessionId = create("carol");
    assertEquals(
        204, send("GET", "/sessions/" + sessionId + "/result?timeout=0", "carol").statusCode());
    assertEquals(204, send("DELETE", "/sessions/" + sessionId, "carol").statusCode());

    Await.until(
        "the answered requests to stop watching carol's revocation",
        () -> carolRevoked.getNumberOfDependents() == 0);
  }

  @Test
  void aFactoryReturningAnotherUsersSessionIdCannotTakeItOver() throws Exception {
    var sessionId = create("alice");
    hijackedId.set(sessionId);

    var hijack = send("POST", "/sessions", "bob");

    assertEquals(500, hijack.statusCode());
    assertEquals("{\"error\":\"session options factory failed\"}", hijack.body());
    assertEquals(
        404, send("GET", "/sessions/" + sessionId + "/result?timeout=0", "bob").statusCode());
    assertEquals(
        204, send("GET", "/sessions/" + sessionId + "/result?timeout=0", "alice").statusCode());
    assertEquals(1, registry.size());
  }

  @Test
  void revokingTheIdentityWhileAMessageBodyUploadsQueuesNoWork() throws Exception {
    var sessionId = create("carol");
    var body = "{\"text\":\"go\"}";
    try (var connection = RawConnection.open(server.port())) {
      connection.write(
          "POST /v1/sessions/"
              + sessionId
              + "/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer carol-upload\r\n"
              + "Content-Type: application/json\r\nContent-Length: "
              + body.length()
              + "\r\n\r\n");
      connection.write(body.substring(0, 1));
      Await.until(
          "the request to watch carol's revocation",
          () -> uploadRevoked.getNumberOfDependents() > 0);

      uploadRevoked.complete(null);
      connection.write(body.substring(1));

      var response = connection.response();
      assertEquals("HTTP/1.1 401 Unauthorized", response.statusLine());
      assertEquals(UNAUTHENTICATED, response.body());
      assertEquals(TestAuthenticator.CHALLENGE, response.headers().get("www-authenticate"));
    }
    assertTrue(model.calls().isEmpty());
    assertEquals(0, toolRuns.get());
  }

  @Test
  void revokingTheIdentityWhileTheOptionsFactoryRunsCreatesNoSession() throws Exception {
    duringFactory.set(() -> carolRevoked.complete(null));

    var resp = send("POST", "/sessions", "carol");

    assertUnauthenticated(resp, "create");
    assertTrue(carolRevoked.isDone());
    assertEquals(0, registry.size());
  }

  private static void assertUnauthenticated(HttpResponse<String> resp, String context) {
    assertEquals(401, resp.statusCode(), context);
    assertEquals(UNAUTHENTICATED, resp.body(), context);
    assertEquals(
        List.of(TestAuthenticator.CHALLENGE),
        resp.headers().allValues("WWW-Authenticate"),
        context);
  }

  private Optional<Authentication> authenticate(ServerRequest request) {
    return request
        .headers()
        .first(HeaderNames.AUTHORIZATION)
        .filter(header -> header.startsWith("Bearer "))
        .map(header -> identities.get(header.substring("Bearer ".length())));
  }

  private SessionOptions options(SessionRequest request) {
    duringFactory.get().run();
    var probe =
        Tool.newBuilder()
            .withName("probe")
            .withExecutor(
                (args, ctx) -> {
                  toolRuns.incrementAndGet();
                  return ToolResult.success("probed");
                })
            .build();
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(Optional.ofNullable(hijackedId.get()).orElse(request.sessionId()))
        .withTools(
            new ToolRegistry(
                List.of(ToolBinding.newBuilder(probe).withCategory(ToolCategory.READ).build())))
        .build();
  }

  private static List<Route> sessionRoutes(String sessionId) {
    var path = "/sessions/" + sessionId;
    return List.of(
        new Route("POST", messages(sessionId), "{\"text\":\"hi\"}"),
        new Route("POST", interrupt(sessionId), "{\"reason\":\"stop\"}"),
        new Route("GET", path + "/questions", null),
        new Route("POST", path + "/questions/perm-1", "{\"selectedLabels\":[\"Allow\"]}"),
        new Route("GET", path + "/events", null),
        new Route("GET", path + "/result?timeout=0", null),
        new Route("DELETE", path, null));
  }

  private static String messages(String sessionId) {
    return "/sessions/" + sessionId + "/messages";
  }

  private static String interrupt(String sessionId) {
    return "/sessions/" + sessionId + "/interrupt";
  }

  private String create(String token) throws Exception {
    var resp = send("POST", "/sessions", token);
    assertEquals(201, resp.statusCode(), resp::body);
    return (String) mapper.readValue(resp.body(), Map.class).get("sessionId");
  }

  private HttpResponse<String> send(String method, String path, String token) throws Exception {
    var body =
        switch (path.substring(path.lastIndexOf('/') + 1)) {
          case "messages" -> "{\"text\":\"go\"}";
          case "interrupt" -> "{\"reason\":\"stop\"}";
          case "sessions" -> "{}";
          default -> null;
        };
    return sendAuthorization(new Route(method, path, body), "Bearer " + token);
  }

  private HttpResponse<String> sendUnchecked(String method, String path, String token) {
    try {
      return send(method, path, token);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResponse<String> sendAuthorization(Route route, String authorization)
      throws Exception {
    return http.send(request(route, authorization), BodyHandlers.ofString());
  }

  private HttpRequest request(Route route, String authorization) {
    var builder =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/v1" + route.path()))
            .timeout(Await.HANG_GUARD)
            .method(
                route.method(),
                route.body() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(route.body()));
    if (route.body() != null) {
      builder.header("Content-Type", "application/json");
    }
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return builder.build();
  }

  private EventLines openEvents(String sessionId, String token) throws Exception {
    var resp =
        http.send(
            request(
                new Route("GET", "/sessions/" + sessionId + "/events", null), "Bearer " + token),
            BodyHandlers.ofLines());
    assertEquals(200, resp.statusCode());
    var lines = resp.body().iterator();
    var ready =
        CompletableFuture.supplyAsync(
            () -> {
              while (lines.hasNext()) {
                if (lines.next().equals("event:Ready")) {
                  return true;
                }
              }
              return false;
            });
    assertTrue(Await.value("the SSE Ready event", ready));
    return new EventLines(lines);
  }

  private record Route(String method, String path, String body) {}

  /** The rest of an SSE stream after its {@code Ready} event. */
  private record EventLines(Iterator<String> lines) {

    /** Wait for the server to end the stream; returns the event names it sent after Ready. */
    String awaitEnd() {
      var rest =
          CompletableFuture.supplyAsync(
              () -> {
                var names = new StringBuilder();
                while (lines.hasNext()) {
                  var line = lines.next();
                  if (line.startsWith("event:")) {
                    names.append(line);
                  }
                }
                return names.toString();
              });
      return Await.value("the server to end the SSE stream", rest);
    }
  }
}
