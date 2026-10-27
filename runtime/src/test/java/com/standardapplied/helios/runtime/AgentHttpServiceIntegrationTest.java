/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class AgentHttpServiceIntegrationTest {

  private static final Duration HTTP_TIMEOUT = Await.HANG_GUARD;
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
  private final SessionRegistry registry = SessionRegistry.inMemory();

  /** Default model used by tests — returns a fixed response on every chat call. */
  private static Model textModel(String reply) {
    return new BlockingModel() {
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
            request ->
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withSessionId(request.sessionId())
                    .build())
        .withPort(0)
        .withoutAuthentication()
        .withHost("127.0.0.1")
        .build();
  }

  private HttpClient httpClient() {
    return http;
  }

  @AfterEach
  void shutDownHttpClient() {
    http.shutdownNow();
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

      var collected = events.awaitRunEnded();
      assertTrue(
          collected.stream().anyMatch(e -> e.event.equals("UserMessageReceived")),
          "stream must include UserMessageReceived");
      assertTrue(
          collected.stream().anyMatch(e -> e.event.equals("AssistantText")),
          "stream must include AssistantText");
      var turnEnded =
          collected.stream().filter(e -> e.event.equals("TurnEnded")).findFirst().orElseThrow();
      assertTrue(turnEnded.data.contains("\"reason\":\"STOP\""), turnEnded.data);
      var runEnded =
          collected.stream().filter(e -> e.event.equals("RunEnded")).findFirst().orElseThrow();
      assertTrue(runEnded.data.contains("hello back"));
      assertEquals("RunStarted", collected.get(1).event());
    }
  }

  @Test
  void aToolOutcomeStreamsAsAnObjectAndTheResultUsesCamelCaseComponentNames() throws Exception {
    var slow =
        Tool.newBuilder()
            .withName("slow")
            .withExecutor((args, ctx) -> ToolResult.timedOut("slow", Duration.ofMinutes(1)))
            .build();
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(
                request ->
                    SessionOptions.newBuilder()
                        .withModel(callingOnce(new ToolCall("c1", "slow", Map.of())))
                        .withTools(
                            List.of(
                                ToolBinding.newBuilder(slow)
                                    .withCategory(ToolCategory.READ)
                                    .build()))
                        .withSessionId(request.sessionId())
                        .build())
            .withPort(0)
            .withoutAuthentication()
            .withHost("127.0.0.1")
            .build();
    try (server) {
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var events = startEventReader(http, base, sessionId);
      sendMessage(http, base, sessionId, "go");

      var toolResult =
          events.awaitRunEnded().stream()
              .filter(e -> e.event.equals("ToolResult"))
              .findFirst()
              .orElseThrow();
      assertTrue(
          toolResult.data.contains(
              "\"result\":{\"outcome\":{\"kind\":\"TimedOut\",\"limit\":\"PT1M\"},"
                  + "\"output\":\"tool 'slow' timed out after PT1M\""),
          toolResult.data);
      var result =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/result"))
                  .timeout(HTTP_TIMEOUT)
                  .build(),
              BodyHandlers.ofString());
      assertTrue(result.body().startsWith("{\"type\":\"Success\",\"runId\":\"run-"), result.body());
      assertTrue(
          result.body().contains("\"result\":{\"sessionId\":\"" + sessionId + "\",\"runId\":"),
          result.body());
      assertTrue(result.body().contains("\"usage\":{\"inputTokens\":"), result.body());
    }
  }

  /** A model that makes {@code call} on its first turn and answers "done" on every later one. */
  private static Model callingOnce(ToolCall call) {
    var turns = new AtomicInteger();
    return new BlockingModel() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        var builder = Response.newBuilder().withUsage(Usage.of(1, 1));
        if (turns.getAndIncrement() == 0) {
          return builder
              .withToolCalls(List.of(call))
              .withFinishReason(FinishReason.TOOL_CALLS)
              .build();
        }
        return builder.withContent("done").withFinishReason(FinishReason.STOP).build();
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

  @Test
  void twoMessagesARunApartProduceTwoResultsOnOneEventStream() throws Exception {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    try (var server = startServer(model)) {
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var events = startEventReader(http, base, sessionId);

      sendMessage(http, base, sessionId, "first");
      var first = result(base, sessionId);
      events.awaitRunEnded();
      sendMessage(http, base, sessionId, "second");
      var second = result(base, sessionId);
      var collected = events.awaitRunEnded();

      assertEquals(List.of("Success", "Success"), List.of(first.get("type"), second.get("type")));
      assertNotEquals(first.get("runId"), second.get("runId"));
      assertEquals(
          List.of(first.get("runId"), second.get("runId")),
          collected.stream()
              .filter(e -> e.event().equals("RunEnded"))
              .map(e -> mapper.readValue(e.data(), Map.class).get("runId"))
              .toList());
    }
  }

  @Test
  void anInterruptWithNoBodyCancelsTheStalledRunAndTheSessionRunsAgain() throws Exception {
    var entered = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(ModelStreams.stalled(entered::countDown))
            .withTextTurn("again")
            .build();
    try (var server = startServer(model)) {
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      sendMessage(http, base, sessionId, "go");
      Await.latch("the run to stall in its model stream", entered);

      var interrupt =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/interrupt"))
                  .timeout(HTTP_TIMEOUT)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              BodyHandlers.ofString());
      var cancelled = result(base, sessionId);
      sendMessage(http, base, sessionId, "next");

      assertEquals(202, interrupt.statusCode());
      assertEquals("Cancelled", cancelled.get("type"));
      assertEquals("interrupted", ((Map<?, ?>) cancelled.get("result")).get("reason"));
      assertEquals("Success", result(base, sessionId).get("type"));
    }
  }

  @Test
  void aSessionWhoseExecutionProviderRefusesIsAnswered503() throws Exception {
    try (var server =
        serverWithFactory(
            request ->
                SessionOptions.newBuilder()
                    .withModel(textModel("x"))
                    .withSessionId(request.sessionId())
                    .withExecutionProvider(new RefusingProvider())
                    .build())) {
      var resp =
          http.send(postJson(baseUrl(server) + "/sessions", Map.of()), BodyHandlers.ofString());

      assertEquals(503, resp.statusCode());
      assertEquals("{\"error\":\"session unavailable\"}", resp.body());
      assertEquals(0, registry.size());
    }
  }

  @Test
  void eventsOpenedBetweenRunsAreReadyThenNothingUntilTheSessionIsDeleted() throws Exception {
    try (var server = startServer(textModel("the answer"))) {
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      sendMessage(http, base, sessionId, "go");
      assertEquals("Success", result(base, sessionId).get("type"));
      var events = startEventReader(http, base, sessionId);

      var delete =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId))
                  .timeout(HTTP_TIMEOUT)
                  .DELETE()
                  .build(),
              BodyHandlers.ofString());

      assertEquals(204, delete.statusCode());
      assertEquals(List.of("Ready"), events.awaitTerminal().stream().map(SseLog::event).toList());
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
  void anInterruptWithNoRunInProgressIsAccepted() throws Exception {
    try (var server = startServer(textModel("done"))) {
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      sendMessage(http, base, sessionId, "go");
      assertEquals("Success", result(base, sessionId).get("type"));

      var resp =
          http.send(
              postJson(base + "/sessions/" + sessionId + "/interrupt", Map.of("reason", "late")),
              BodyHandlers.ofString());

      assertEquals(202, resp.statusCode());
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
  void sendWithAJsonNullBodyReturns400() throws Exception {
    try (var server = startServer(textModel("x"))) {
      var http = httpClient();
      var base = baseUrl(server);
      var sessionId = createSession(http, base);
      var resp =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/messages"))
                  .timeout(HTTP_TIMEOUT)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("null"))
                  .build(),
              BodyHandlers.ofString());
      assertEquals(400, resp.statusCode());
      assertEquals("{\"error\":\"invalid JSON body\"}", resp.body());
    }
  }

  // ── 500 path ─────────────────────────────────────────────────────────────

  @Test
  void factoryThatThrowsReturns500WithoutItsMessage() throws Exception {
    try (var server =
        serverWithFactory(
            request -> {
              throw new RuntimeException("nope: password=hunter22");
            })) {
      var resp =
          httpClient()
              .send(postJson(baseUrl(server) + "/sessions", Map.of()), BodyHandlers.ofString());
      assertEquals(500, resp.statusCode());
      assertEquals("{\"error\":\"session options factory failed\"}", resp.body());
      assertEquals(0, registry.size());
    }
  }

  @Test
  void factoryReturningOptionsForAnotherSessionIdIsRefused() throws Exception {
    try (var server =
        serverWithFactory(
            request ->
                SessionOptions.newBuilder()
                    .withModel(textModel("x"))
                    .withSessionId("override-" + request.sessionId())
                    .build())) {
      var resp =
          httpClient()
              .send(postJson(baseUrl(server) + "/sessions", Map.of()), BodyHandlers.ofString());
      assertEquals(500, resp.statusCode());
      assertEquals("{\"error\":\"session options factory failed\"}", resp.body());
      assertEquals(0, registry.size());
    }
  }

  @Test
  void factoryReturningNullIsRefused() throws Exception {
    try (var server = serverWithFactory(request -> null)) {
      var resp =
          httpClient()
              .send(postJson(baseUrl(server) + "/sessions", Map.of()), BodyHandlers.ofString());
      assertEquals(500, resp.statusCode());
      assertEquals("{\"error\":\"session options factory failed\"}", resp.body());
      assertEquals(0, registry.size());
    }
  }

  private RuntimeServer serverWithFactory(Function<SessionRequest, SessionOptions> factory) {
    return RuntimeServer.builder()
        .withRegistry(registry)
        .withOptionsFactory(factory)
        .withPort(0)
        .withoutAuthentication()
        .build();
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
    var blockLatch = new CountDownLatch(1);
    Model blocking =
        new BlockingModel() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            try {
              blockLatch.await();
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
    assertEquals(
        "{\"sessionId\":\"" + id + "\",\"eventsUrl\":\"/v1/sessions/" + id + "/events\"}",
        resp.body());
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

  /** The body of the result long poll of {@code sessionId}, which answers {@code 200}. */
  private Map<?, ?> result(String base, String sessionId) throws Exception {
    var resp =
        http.send(
            HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/result"))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build(),
            BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), resp::body);
    return mapper.readValue(resp.body(), Map.class);
  }

  /** An execution provider that refuses every session. */
  private static final class RefusingProvider implements ExecutionProvider {

    @Override
    public Set<Runtime> supportedRuntimes() {
      return Set.of();
    }

    @Override
    public SessionStartOutcome onSessionStart(SessionContext ctx) {
      return SessionStartOutcome.refuse("pool full");
    }

    @Override
    public void onSessionEnd(SessionContext ctx) {
      throw new AssertionError("a refused session is never ended");
    }

    @Override
    public ExecutionResult execute(
        SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
      throw new AssertionError("not used");
    }
  }

  /** Captured SSE event {name, data}. */
  private record SseLog(String event, String data) {}

  /**
   * Reads one SSE stream on its own thread and collects every event until the server ends the
   * stream, which it does once the session is closed, handing each {@code RunEnded} to {@link
   * #awaitRunEnded()}. A failed read fails the test that waits on the reader.
   */
  private static final class SseEventReader {

    private final List<SseLog> collected = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private final LinkedBlockingQueue<SseLog> runsEnded = new LinkedBlockingQueue<>();

    SseEventReader(HttpClient http, URI uri) {
      Thread.ofVirtual()
          .name("sse-reader")
          .start(
              () -> {
                try {
                  read(http, uri);
                  ended.complete(null);
                } catch (Exception e) {
                  ended.completeExceptionally(e);
                  ready.completeExceptionally(e);
                }
              });
    }

    private void read(HttpClient http, URI uri) throws IOException, InterruptedException {
      var resp =
          http.send(
              HttpRequest.newBuilder(uri)
                  .timeout(HTTP_TIMEOUT)
                  .header("Accept", "text/event-stream")
                  .GET()
                  .build(),
              BodyHandlers.ofInputStream());
      if (resp.statusCode() != 200) {
        throw new IOException("SSE subscription answered " + resp.statusCode());
      }
      try (var reader =
          new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
        String line;
        String currentEvent = null;
        var currentData = new StringBuilder();
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("event:")) {
            currentEvent = line.substring("event:".length()).trim();
          } else if (line.startsWith("data:")) {
            currentData.append(line.substring("data:".length()).trim());
          } else if (line.isEmpty() && currentEvent != null) {
            record(new SseLog(currentEvent, currentData.toString()));
            currentEvent = null;
            currentData.setLength(0);
          }
        }
      }
      ready.completeExceptionally(new IOException("The SSE stream ended without a Ready event"));
    }

    private void record(SseLog log) {
      collected.add(log);
      if ("Ready".equals(log.event())) {
        ready.complete(null);
      }
      if ("RunEnded".equals(log.event())) {
        runsEnded.add(log);
      }
    }

    /**
     * Block until the server confirms subscription is live by emitting the synthetic {@code Ready}
     * SSE event. Closes the race between Helidon writing 200 OK and the subscriber actually
     * registering on the publisher — without this, events submitted in the window get lost.
     */
    void awaitReady() {
      Await.value("the SSE Ready event", ready);
    }

    /** Wait for the next run's {@code RunEnded}, then return every event read so far. */
    List<SseLog> awaitRunEnded() {
      Await.next("the next RunEnded", runsEnded);
      return List.copyOf(collected);
    }

    List<SseLog> awaitTerminal() {
      Await.value("the server to end the SSE stream", ended);
      return List.copyOf(collected);
    }
  }

  private SseEventReader startEventReader(HttpClient http, String base, String sessionId) {
    var reader = new SseEventReader(http, URI.create(base + "/sessions/" + sessionId + "/events"));
    reader.awaitReady();
    return reader;
  }
}
