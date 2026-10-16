/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Proves that a result whose serialization outlasts the caller's identity is not sent: the
 * configured mapper revokes the identity while it writes the result.
 */
final class ResultLongPollRevocationTest {

  private static final Principal CAROL = new Principal("acme", "carol");

  private final CompletableFuture<Void> revoked = new CompletableFuture<>();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
  private final JsonMapper mapper =
      JsonMapper.builder()
          .addModule(
              new SimpleModule("revoke-while-serializing")
                  .addSerializer(ResultMessage.Success.class, new RevokingSerializer()))
          .build();
  private RuntimeServer server;

  @BeforeEach
  void startServer() {
    server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(
                request ->
                    SessionOptions.newBuilder()
                        .withModel(ScriptedModel.newBuilder().withTextTurn("confidential").build())
                        .withSessionId(request.sessionId())
                        .build())
            .withAuthenticator(
                new TestAuthenticator(
                    request -> Optional.of(new Authentication(CAROL, Duration.ofDays(1), revoked))))
            .withObjectMapper(mapper)
            .build();
  }

  @AfterEach
  void stopServer() {
    server.close();
    http.shutdownNow();
  }

  @Test
  void revokingTheIdentityWhileTheResultSerializesDisclosesNoResult() throws Exception {
    var created = send("POST", "/sessions", "{}");
    assertEquals(201, created.statusCode(), created::body);
    var sessionId = (String) mapper.readValue(created.body(), Map.class).get("sessionId");
    assertEquals(
        202,
        send("POST", "/sessions/" + sessionId + "/messages", "{\"text\":\"go\"}").statusCode());
    var session = server.registry().find(sessionId, CAROL).orElseThrow();
    Await.value("the session's result", session.result());

    var resp = send("GET", "/sessions/" + sessionId + "/result?timeout=0", null);

    assertTrue(revoked.isDone());
    assertEquals(401, resp.statusCode());
    assertEquals("{\"error\":\"unauthenticated\"}", resp.body());
    assertEquals(
        Optional.of(TestAuthenticator.CHALLENGE), resp.headers().firstValue("WWW-Authenticate"));
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1" + path))
            .timeout(Await.HANG_GUARD)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  private final class RevokingSerializer extends StdSerializer<ResultMessage.Success> {

    RevokingSerializer() {
      super(ResultMessage.Success.class);
    }

    @Override
    public void serialize(
        ResultMessage.Success success, JsonGenerator gen, SerializationContext context) {
      revoked.complete(null);
      gen.writeStartObject();
      gen.writeStringProperty("result", success.result());
      gen.writeEndObject();
    }
  }
}
