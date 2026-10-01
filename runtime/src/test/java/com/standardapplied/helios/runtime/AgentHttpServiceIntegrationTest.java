/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.SessionOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class AgentHttpServiceIntegrationTest {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
  private final JsonMapper mapper = JsonMapper.builder().build();

  /** Default model used by tests — returns a fixed response on every chat call. */
  private static Model textModel(String reply) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(reply)
            .withFinishReason(FinishReason.STOP)
            .withUsage(Usage.of(3, 2))
            .build();
      }

      @Override
      public String id() {
        return "test";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private RuntimeServer startServer(Model model) {
    return RuntimeServer.builder()
        .withRegistry(SessionRegistry.inMemory())
        .withOptionsFactory(
            sessionId ->
                SessionOptions.newBuilder().withModel(model).withSessionId(sessionId).build())
        .withPort(0)
        .withHost("127.0.0.1")
        .build();
  }

  private HttpClient httpClient() {
    return HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
  }

  private String baseUrl(RuntimeServer server) {
    return "http://127.0.0.1:" + server.port() + "/v1";
  }

  // ── happy path / Phase 1 acceptance ───────────────────────────────────────

  @Test
  void createSendReadStreamProducesSuccessTerminal() throws Exception {
    try (var server = startServer(textModel("hello back"))) {
      var http = httpClient();
      var base = baseUrl(server);

      var sessionId = createSession(http, base);
      var events = startEventReader(http, base, sessionId);
      sendMessage(http, base, sessionId, "hi");

      var collected = events.awaitTerminal();
      assertTrue(
          collected.stream().anyMatch(e -> e.event.equals("UserMessageReceived")),
          "stream must include UserMessageReceived");
      assertTrue(
          collected.stream().anyMatch(e -> e.event.equals("AssistantText")),
          "stream must include AssistantText");
      var loopEnded =
          collected.stream().filter(e -> e.event.equals("LoopEnded")).findFirst().orElseThrow();
      assertTrue(loopEnded.data.contains("hello back"));
    }
  }

  @Test
  void midRunInterruptIsObservedAsUserMessageReceived() throws Exception {
    // Model whose first call enqueues steering pressure (does NOT terminate), then succeeds.
    var calls = new AtomicInteger();
    var modelLatch = new CountDownLatch(1);
    Model adaptive =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var call = calls.incrementAndGet();
            if (call == 1) {
              // Block long enough for the test to issue the interrupt
              try {
                modelLatch.await(2, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return Response.newBuilder()
                  .withContent("partial")
                  .withFinishReason(FinishReason.STOP)
                  .withUsage(Usage.of(1, 1))
                  .build();
            }
            return Response.newBuilder()
                .withContent("after-interrupt")
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(2, 1))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    try (var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(
                sessionId ->
                    SessionOptions.newBuilder()
                        .withModel(adaptive)
                        .withSessionId(sessionId)
                        .build())
            .withPort(0)
            .withHost("127.0.0.1")
            .build()) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var events = startEventReader(http, base, sessionId);
      sendMessage(http, base, sessionId, "first");
      // Allow the first chat() to start blocking.
      Thread.sleep(100);
      interruptSession(http, base, sessionId, "rethink");
      // Release the first turn.
      modelLatch.countDown();
      var collected = events.awaitTerminal();
      assertTrue(
          collected.stream()
              .filter(e -> e.event.equals("UserMessageReceived"))
              .anyMatch(e -> e.data.contains("interrupted by user: rethink")),
          "stream must include synthetic interrupt UserMessageReceived");
      var loopEnded =
          collected.stream().filter(e -> e.event.equals("LoopEnded")).findFirst().orElseThrow();
      assertTrue(loopEnded.data.contains("after-interrupt"));
    }
  }

  // ── 404 paths ────────────────────────────────────────────────────────────

  @Test
  void sendToUnknownSessionReturns404() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var resp =
          httpClient()
              .send(
                  postJson(baseUrl(server) + "/sessions/missing/messages", Map.of("text", "hi")),
                  BodyHandlers.ofString());
      assertEquals(404, resp.statusCode());
    }
  }

  @Test
  void interruptUnknownSessionReturns404() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var resp =
          httpClient()
              .send(
                  postJson(baseUrl(server) + "/sessions/missing/interrupt", Map.of("reason", "x")),
                  BodyHandlers.ofString());
      assertEquals(404, resp.statusCode());
    }
  }

  @Test
  void eventsForUnknownSessionReturns404() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var resp =
          httpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(baseUrl(server) + "/sessions/missing/events"))
                      .timeout(HTTP_TIMEOUT)
                      .GET()
                      .build(),
                  BodyHandlers.ofString());
      assertEquals(404, resp.statusCode());
    }
  }

  @Test
  void deleteUnknownSessionReturns404() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var resp =
          httpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(baseUrl(server) + "/sessions/missing"))
                      .timeout(HTTP_TIMEOUT)
                      .DELETE()
                      .build(),
                  BodyHandlers.ofString());
      assertEquals(404, resp.statusCode());
    }
  }

  @Test
  void sendOnTerminatedSessionReturns409() throws Exception {
    try (var server = startServer(textModel("done"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      // Drive to terminal
      sendMessage(http, base, sessionId, "go");
      // Wait for terminal by reading the stream
      startEventReader(http, base, sessionId).awaitTerminal();
      // Now send: session is terminal → 409
      var resp =
          http.send(
              postJson(base + "/sessions/" + sessionId + "/messages", Map.of("text", "again")),
              BodyHandlers.ofString());
      assertEquals(409, resp.statusCode());
    }
  }

  @Test
  void interruptOnTerminatedSessionReturns409() throws Exception {
    try (var server = startServer(textModel("done"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      sendMessage(http, base, sessionId, "go");
      startEventReader(http, base, sessionId).awaitTerminal();
      var resp =
          http.send(
              postJson(base + "/sessions/" + sessionId + "/interrupt", Map.of("reason", "late")),
              BodyHandlers.ofString());
      assertEquals(409, resp.statusCode());
    }
  }

  @Test
  void deleteExistingSessionReturns204() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var resp =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId))
                  .timeout(HTTP_TIMEOUT)
                  .DELETE()
                  .build(),
              BodyHandlers.ofString());
      assertEquals(204, resp.statusCode());
    }
  }

  // ── 400 paths ────────────────────────────────────────────────────────────

  @Test
  void sendWithoutTextFieldReturns400() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var resp =
          http.send(
              postJson(base + "/sessions/" + sessionId + "/messages", Map.of("oops", "x")),
              BodyHandlers.ofString());
      assertEquals(400, resp.statusCode());
    }
  }

  @Test
  void sendWithBlankTextReturns400() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var resp =
          http.send(
              postJson(base + "/sessions/" + sessionId + "/messages", Map.of("text", "  ")),
              BodyHandlers.ofString());
      assertEquals(400, resp.statusCode());
    }
  }

  @Test
  void sendInvalidJsonReturns400() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var resp =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/messages"))
                  .timeout(HTTP_TIMEOUT)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{not json"))
                  .build(),
              BodyHandlers.ofString());
      assertEquals(400, resp.statusCode());
    }
  }

  @Test
  void interruptWithoutReasonReturns400() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var resp =
          http.send(
              postJson(base + "/sessions/" + sessionId + "/interrupt", Map.of()),
              BodyHandlers.ofString());
      assertEquals(400, resp.statusCode());
    }
  }

  @Test
  void interruptWithBlankReasonReturns400() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var resp =
          http.send(
              postJson(base + "/sessions/" + sessionId + "/interrupt", Map.of("reason", " ")),
              BodyHandlers.ofString());
      assertEquals(400, resp.statusCode());
    }
  }

  @Test
  void interruptInvalidJsonReturns400() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var resp =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/interrupt"))
                  .timeout(HTTP_TIMEOUT)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{broken"))
                  .build(),
              BodyHandlers.ofString());
      assertEquals(400, resp.statusCode());
    }
  }

  // ── 500 path ─────────────────────────────────────────────────────────────

  @Test
  void factoryThatThrowsReturns500() throws Exception {
    try (var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(
                sessionId -> {
                  throw new RuntimeException("nope");
                })
            .withPort(0)
            .withHost("127.0.0.1")
            .build()) {
      var resp =
          httpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(baseUrl(server) + "/sessions"))
                      .timeout(HTTP_TIMEOUT)
                      .header("Content-Type", "application/json")
                      .POST(HttpRequest.BodyPublishers.ofString("{}"))
                      .build(),
                  BodyHandlers.ofString());
      assertEquals(500, resp.statusCode());
    }
  }

  @Test
  void factoryReturningDifferentSessionIdLogsButProceeds() throws Exception {
    try (var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(
                sessionId ->
                    SessionOptions.newBuilder()
                        .withModel(textModel("x"))
                        .withSessionId("override-" + sessionId)
                        .build())
            .withPort(0)
            .withHost("127.0.0.1")
            .build()) {
      var resp =
          httpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(baseUrl(server) + "/sessions"))
                      .timeout(HTTP_TIMEOUT)
                      .header("Content-Type", "application/json")
                      .POST(HttpRequest.BodyPublishers.ofString("{}"))
                      .build(),
                  BodyHandlers.ofString());
      assertEquals(201, resp.statusCode());
      var body = mapper.readValue(resp.body(), Map.class);
      var returnedId = (String) body.get("sessionId");
      assertTrue(returnedId.startsWith("override-sess-"), "factory's override must surface");
    }
  }

  // ── GET /sessions/{id}/result long-poll (Phase D #23) ────────────────────

  @Test
  void resultLongPollReturnsTerminalAfterCompletion() throws Exception {
    try (var server = startServer(textModel("the answer"))) {
      var http = httpClient();
      var base = baseUrl(server);

      var sessionId = createSession(http, base);
      sendMessage(http, base, sessionId, "go");

      // Default 60s timeout; the test session completes in well under a second.
      var resp =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/result"))
                  .timeout(HTTP_TIMEOUT)
                  .GET()
                  .build(),
              BodyHandlers.ofString());
      assertEquals(200, resp.statusCode(), "result should 200; body=" + resp.body());
      @SuppressWarnings("unchecked")
      Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
      assertEquals("Success", body.get("type"));
      @SuppressWarnings("unchecked")
      var result = (Map<String, Object>) body.get("result");
      assertEquals("the answer", result.get("result"));
    }
  }

  @Test
  void resultLongPollOnLiveSessionTimesOutWith204() throws Exception {
    // Model that blocks forever — session never terminates within the poll window.
    var blockLatch = new CountDownLatch(1);
    Model blocking =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            try {
              blockLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return Response.newBuilder()
                .withContent("done")
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(1, 1))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    try (var server = startServer(blocking)) {
      var http = httpClient();
      var base = baseUrl(server);

      var sessionId = createSession(http, base);
      sendMessage(http, base, sessionId, "go");

      // 1 s long-poll on a session that will not terminate for 10 s.
      var resp =
          http.send(
              HttpRequest.newBuilder(
                      URI.create(base + "/sessions/" + sessionId + "/result?timeout=1"))
                  .timeout(HTTP_TIMEOUT)
                  .GET()
                  .build(),
              BodyHandlers.ofString());
      assertEquals(204, resp.statusCode(), "timeout should 204 (no body)");
      assertTrue(resp.body().isEmpty(), "204 must carry no body, got: " + resp.body());
    } finally {
      blockLatch.countDown();
    }
  }

  @Test
  void resultLongPollOnUnknownSessionIs404() throws Exception {
    try (var server = startServer(textModel("hi"))) {
      var http = httpClient();
      var base = baseUrl(server);

      var resp =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/does-not-exist/result"))
                  .timeout(HTTP_TIMEOUT)
                  .GET()
                  .build(),
              BodyHandlers.ofString());
      assertEquals(404, resp.statusCode());
    }
  }

  @Test
  void resultLongPollMalformedTimeoutFallsBackToDefault() throws Exception {
    try (var server = startServer(textModel("done"))) {
      var http = httpClient();
      var base = baseUrl(server);

      var sessionId = createSession(http, base);
      sendMessage(http, base, sessionId, "go");

      // "abc" is not a number → server falls back to default 60s instead of 400ing.
      var resp =
          http.send(
              HttpRequest.newBuilder(
                      URI.create(base + "/sessions/" + sessionId + "/result?timeout=abc"))
                  .timeout(HTTP_TIMEOUT)
                  .GET()
                  .build(),
              BodyHandlers.ofString());
      assertEquals(200, resp.statusCode(), "malformed timeout must not 400");
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private HttpRequest postJson(String url, Object body) throws Exception {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(HTTP_TIMEOUT)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build();
  }

  private String createSession(HttpClient http, String base) throws Exception {
    var resp =
        http.send(
            HttpRequest.newBuilder(URI.create(base + "/sessions"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            BodyHandlers.ofString());
    assertEquals(201, resp.statusCode(), "create-session should 201; body=" + resp.body());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    var id = (String) body.get("sessionId");
    assertTrue(id != null && !id.isBlank());
    return id;
  }

  private void sendMessage(HttpClient http, String base, String sessionId, String text)
      throws Exception {
    var resp =
        http.send(
            postJson(base + "/sessions/" + sessionId + "/messages", Map.of("text", text)),
            BodyHandlers.ofString());
    assertEquals(202, resp.statusCode(), "send should 202; body=" + resp.body());
  }

  private void interruptSession(HttpClient http, String base, String sessionId, String reason)
      throws Exception {
    var resp =
        http.send(
            postJson(base + "/sessions/" + sessionId + "/interrupt", Map.of("reason", reason)),
            BodyHandlers.ofString());
    assertEquals(202, resp.statusCode(), "interrupt should 202; body=" + resp.body());
  }

  /** Captured SSE event {name, data}. */
  private record SseLog(String event, String data) {}

  /** Async SSE reader that collects events until the stream closes. */
  private static final class SseEventReader {

    private final List<SseLog> collected = new ArrayList<>();
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread worker;

    SseEventReader(HttpClient http, URI uri) {
      this.worker =
          Thread.ofVirtual()
              .name("sse-reader")
              .start(
                  () -> {
                    try {
                      var resp =
                          http.send(
                              HttpRequest.newBuilder(uri)
                                  .timeout(Duration.ofSeconds(15))
                                  .header("Accept", "text/event-stream")
                                  .GET()
                                  .build(),
                              BodyHandlers.ofInputStream());
                      if (resp.statusCode() != 200) {
                        ready.countDown();
                        terminal.countDown();
                        return;
                      }
                      try (var reader =
                          new java.io.BufferedReader(
                              new java.io.InputStreamReader(
                                  resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        String currentEvent = null;
                        var currentData = new StringBuilder();
                        while ((line = reader.readLine()) != null) {
                          if (line.isEmpty()) {
                            if (currentEvent != null) {
                              var log = new SseLog(currentEvent, currentData.toString());
                              synchronized (collected) {
                                collected.add(log);
                              }
                              if ("Ready".equals(currentEvent)) {
                                ready.countDown();
                              }
                              if ("LoopEnded".equals(currentEvent)) {
                                terminal.countDown();
                              }
                            }
                            currentEvent = null;
                            currentData.setLength(0);
                          } else if (line.startsWith("event:")) {
                            currentEvent = line.substring("event:".length()).trim();
                          } else if (line.startsWith("data:")) {
                            currentData.append(line.substring("data:".length()).trim());
                          }
                        }
                      }
                    } catch (Exception ignored) {
                      // network failures end the stream; terminal latch may already have fired
                    } finally {
                      ready.countDown();
                      terminal.countDown();
                    }
                  });
    }

    /**
     * Block until the server confirms subscription is live by emitting the synthetic {@code Ready}
     * SSE event. Closes the race between Helidon writing 200 OK and the subscriber actually
     * registering on the publisher — without this, events submitted in the window get lost.
     */
    void awaitReady() throws InterruptedException {
      assertTrue(ready.await(5, TimeUnit.SECONDS), "SSE subscription Ready event not received");
    }

    List<SseLog> awaitTerminal() throws InterruptedException {
      assertTrue(terminal.await(10, TimeUnit.SECONDS), "SSE stream did not terminate in 10s");
      worker.join(Duration.ofSeconds(2));
      synchronized (collected) {
        return List.copyOf(collected);
      }
    }
  }

  private SseEventReader startEventReader(HttpClient http, String base, String sessionId)
      throws InterruptedException {
    var reader = new SseEventReader(http, URI.create(base + "/sessions/" + sessionId + "/events"));
    reader.awaitReady();
    return reader;
  }
}
