/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that the options factory is given the principal creating each session, and that a factory
 * configuring a session by its owner's tenant shapes what that session sends its model.
 */
final class SessionRequestTest {

  private static final Principal ALICE = new Principal("tenant-alice", "alice");
  private static final Principal BOB = new Principal("tenant-bob", "bob");

  private final List<SessionRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, ScriptedModel> models = new ConcurrentHashMap<>();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
  private RuntimeServer server;

  @BeforeEach
  void startServer() {
    server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(this::options)
            .withAuthenticator(new RevocableTokens())
            .build();
  }

  @AfterEach
  void stopServer() {
    server.close();
    http.shutdownNow();
  }

  @Test
  void theFactoryIsGivenEachSessionsOwnerAndConfiguresTheSessionForItsTenant() throws Exception {
    var alices = create(ALICE);
    var bobs = create(BOB);

    assertEquals(
        List.of(new SessionRequest(alices, ALICE), new SessionRequest(bobs, BOB)), requests);
    assertEquals(Message.system("You serve tenant-alice"), firstModelMessage(alices, ALICE));
    assertEquals(Message.system("You serve tenant-bob"), firstModelMessage(bobs, BOB));
  }

  @Test
  void aSessionRequestRefusesNullComponents() {
    assertEquals(
        "sessionId must not be null",
        assertThrows(NullPointerException.class, () -> new SessionRequest(null, ALICE))
            .getMessage());
    assertEquals(
        "owner must not be null",
        assertThrows(NullPointerException.class, () -> new SessionRequest("sess-1", null))
            .getMessage());
  }

  private SessionOptions options(SessionRequest request) {
    requests.add(request);
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    models.put(request.sessionId(), model);
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(request.sessionId())
        .withSystemPrompt("You serve " + request.owner().tenant())
        .build();
  }

  private String create(Principal owner) throws Exception {
    var created = post("/sessions", owner, "{}");
    return (String) JsonMapper.builder().build().readValue(created, Map.class).get("sessionId");
  }

  /** Run one turn of {@code owner}'s session and return the first message its model was sent. */
  private Message firstModelMessage(String sessionId, Principal owner) throws Exception {
    post("/sessions/" + sessionId + "/messages", owner, "{\"text\":\"go\"}");
    Await.value(
        "the session's result", server.registry().find(sessionId, owner).orElseThrow().result());
    return models.get(sessionId).calls().getFirst().getFirst();
  }

  private String post(String path, Principal owner, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1" + path))
            .timeout(Await.HANG_GUARD)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + owner.subject())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var resp = http.send(request, BodyHandlers.ofString());
    assertEquals(path.endsWith("/messages") ? 202 : 201, resp.statusCode(), resp::body);
    return resp.body();
  }
}
