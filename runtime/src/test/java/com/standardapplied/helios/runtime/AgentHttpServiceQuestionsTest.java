/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that an HTTP client finds the questions a session asks, a permission approval or the
 * model's own {@code AskUserQuestion}, through {@code GET .../questions}, and answers them through
 * {@code POST .../questions/{questionId}}: only the session's owner, only a question still pending,
 * and only with a valid response. Real runtime and session over localhost with a {@link
 * ScriptedModel}.
 */
final class AgentHttpServiceQuestionsTest {

  private static final int MAX_REQUEST_BYTES = 1024;
  private static final Principal ALICE = new Principal("acme", "alice");
  private static final ToolCall WRITE = new ToolCall("c1", "Write", Map.of("path", "/tmp/a"));
  private static final String ALLOW = "{\"selectedLabels\":[\"Allow\"]}";

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
  private final List<Map<String, Object>> executed = new CopyOnWriteArrayList<>();
  private final CompletableFuture<Void> uploadRevoked = new CompletableFuture<>();
  private final Map<String, Authentication> identities =
      Map.of(
          "alice",
          Authentication.of(ALICE),
          "alice-upload",
          new Authentication(ALICE, Duration.ofDays(1), uploadRevoked),
          "bob",
          Authentication.of(new Principal("acme", "bob")));
  private final SessionRegistry registry = SessionRegistry.inMemory();
  private ScriptedModel model =
      ScriptedModel.newBuilder().withToolCallsTurn(WRITE).withTextTurn("done").build();
  private RuntimeServer server;

  @BeforeEach
  void startServer() {
    server =
        RuntimeServer.builder()
            .withRegistry(registry)
            .withOptionsFactory(this::options)
            .withAuthenticator(new TestAuthenticator(this::authenticate))
            .withRequestLimits(new RequestLimits(MAX_REQUEST_BYTES, 32))
            .build();
  }

  @AfterEach
  void stopServer() {
    server.close();
    http.shutdownNow();
  }

  @Test
  void allowingAnApprovalRunsTheCallOnceAndEndsTheQuestion() throws Exception {
    var sessionId = startAsking();
    var question = onlyQuestion(sessionId);

    var answer = send("POST", answerPath(sessionId, question), "alice", ALLOW);

    assertEquals(202, answer.statusCode());
    assertTrue(((String) question.get("questionId")).startsWith("perm-"), question::toString);
    assertEquals("Permission", question.get("header"));
    assertEquals(
        List.of("Allow", "Deny"),
        ((List<?>) question.get("options"))
            .stream().map(option -> ((Map<?, ?>) option).get("label")).toList());
    assertEquals(false, question.get("multiSelect"));
    assertResult(sessionId, "Success");
    assertEquals(List.of(Map.of("path", "/tmp/a")), executed);
    assertEquals("[]", get(questionsPath(sessionId), "alice").body());
  }

  @Test
  void denyingAnApprovalRefusesTheCall() throws Exception {
    var sessionId = startAsking();
    var question = onlyQuestion(sessionId);

    var answer =
        send("POST", answerPath(sessionId, question), "alice", "{\"selectedLabels\":[\"Deny\"]}");

    assertEquals(202, answer.statusCode());
    assertResult(sessionId, "Success");
    assertEquals(List.of(), executed);
    assertTrue(
        toolResultSentToTheModel().contains("user denied Write(/tmp/a)"),
        this::toolResultSentToTheModel);
  }

  @Test
  void anotherPrincipalCannotListOrAnswerTheQuestions() throws Exception {
    var sessionId = startAsking();
    var question = onlyQuestion(sessionId);

    var list = get(questionsPath(sessionId), "bob");
    var answer = send("POST", answerPath(sessionId, question), "bob", ALLOW);

    assertEquals(404, list.statusCode());
    assertEquals("{\"error\":\"session not found\"}", list.body());
    assertEquals(404, answer.statusCode());
    assertEquals("{\"error\":\"session not found\"}", answer.body());
    assertEquals(question, onlyQuestion(sessionId));
    assertEquals(List.of(), executed);
  }

  @Test
  void anAnswerToAQuestionNotPendingIs404() throws Exception {
    var sessionId = startAsking();
    var question = onlyQuestion(sessionId);
    var path = answerPath(sessionId, question);

    var unknown = send("POST", questionsPath(sessionId) + "/perm-unknown", "alice", ALLOW);
    var first = send("POST", path, "alice", ALLOW);
    var second = send("POST", path, "alice", ALLOW);

    assertEquals(404, unknown.statusCode());
    assertEquals(
        "{\"error\":\"no pending question with id 'perm-unknown' — already answered or unknown\"}",
        unknown.body());
    assertEquals(202, first.statusCode());
    assertEquals(404, second.statusCode());
    assertResult(sessionId, "Success");
    assertEquals(List.of(Map.of("path", "/tmp/a")), executed);
  }

  @Test
  void anInvalidAnswerIs400AndLeavesTheQuestionPending() throws Exception {
    var sessionId = startAsking();
    var question = onlyQuestion(sessionId);
    var fields =
        "{\"error\":\"'selectedLabels' must be an array of strings and 'customText', if set,"
            + " a string\"}";
    var invalid =
        Map.of(
            "{}", fields,
            "{\"selectedLabels\":\"Allow\"}", fields,
            "{\"selectedLabels\":[\"Allow\",7]}", fields,
            "{\"selectedLabels\":[\"Allow\"],\"customText\":7}", fields,
            "{\"selectedLabels\":[]}", "{\"error\":\"selectedLabels must not be empty\"}",
            "{\"selectedLabels\":[\" \"]}",
                "{\"error\":\"selectedLabels must not contain blank entries\"}");

    for (var body : invalid.entrySet()) {
      var answer = send("POST", answerPath(sessionId, question), "alice", body.getKey());
      assertEquals(400, answer.statusCode(), body.getKey());
      assertEquals(body.getValue(), answer.body(), body.getKey());
    }

    assertEquals(question, onlyQuestion(sessionId));
    assertEquals(List.of(), executed);
  }

  @Test
  void aChunkedAnswerBodyOverTheLimitIs413AndLeavesTheQuestionPending() throws Exception {
    var sessionId = startAsking();
    var question = onlyQuestion(sessionId);
    var oversized =
        ("{\"selectedLabels\":[\"Allow\"],\"customText\":\""
                + "x".repeat(MAX_REQUEST_BYTES)
                + "\"}")
            .getBytes(StandardCharsets.UTF_8);

    var answer =
        send(
            "POST",
            answerPath(sessionId, question),
            "alice",
            BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized)));

    assertEquals(413, answer.statusCode());
    assertEquals("{\"error\":\"request body too large\"}", answer.body());
    assertEquals(question, onlyQuestion(sessionId));
    assertEquals(List.of(), executed);
  }

  @Test
  void anAnswerToAClosedSessionIs409() throws Exception {
    var sessionId = startAsking();
    var question = onlyQuestion(sessionId);
    registry.find(sessionId, ALICE).orElseThrow().close();

    var answer = send("POST", answerPath(sessionId, question), "alice", ALLOW);

    assertEquals(409, answer.statusCode());
    assertEquals("{\"error\":\"session is closed\"}", answer.body());
    assertEquals(List.of(), executed);
  }

  @Test
  void revokingTheIdentityWhileAnAnswerUploadsAnswersNothing() throws Exception {
    var sessionId = startAsking();
    var question = onlyQuestion(sessionId);
    try (var connection = RawConnection.open(server.port())) {
      connection.write(
          "POST /v1"
              + answerPath(sessionId, question)
              + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer alice-upload\r\n"
              + "Content-Type: application/json\r\nContent-Length: "
              + ALLOW.length()
              + "\r\n\r\n");
      connection.write(ALLOW.substring(0, 1));
      Await.until(
          "the request to watch alice's revocation",
          () -> uploadRevoked.getNumberOfDependents() > 0);

      uploadRevoked.complete(null);
      connection.write(ALLOW.substring(1));

      assertEquals("HTTP/1.1 401 Unauthorized", connection.response().statusLine());
    }
    assertEquals(question, onlyQuestion(sessionId));
    assertEquals(List.of(), executed);
  }

  @Test
  void answeringTheModelsQuestionReturnsTheSelectedLabelToTheModel() throws Exception {
    model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(
                new ToolCall(
                    "c1",
                    "AskUserQuestion",
                    Map.of(
                        "question",
                        "Which colour?",
                        "options",
                        List.of(Map.of("label", "Red"), Map.of("label", "Blue")))))
            .withTextTurn("done")
            .build();
    var sessionId = create();
    assertEquals(
        202, send("POST", messagesPath(sessionId), "alice", "{\"text\":\"go\"}").statusCode());
    var question = onlyQuestion(sessionId);

    var answer =
        send(
            "POST",
            answerPath(sessionId, question),
            "alice",
            "{\"selectedLabels\":[\"Blue\"],\"customText\":\"navy\"}");

    assertEquals(202, answer.statusCode());
    assertTrue(((String) question.get("questionId")).startsWith("q-"), question::toString);
    assertEquals("Which colour?", question.get("question"));
    assertResult(sessionId, "Success");
    assertEquals("user selected:\n- Blue\ncustom text:\nnavy\n", toolResultSentToTheModel());
  }

  /** Creates alice's session and sends the message on which its model calls {@code Write}. */
  private String startAsking() throws Exception {
    var sessionId = create();
    assertEquals(
        202, send("POST", messagesPath(sessionId), "alice", "{\"text\":\"go\"}").statusCode());
    return sessionId;
  }

  /** Waits until the session lists a question, then returns the only one it lists. */
  private Map<?, ?> onlyQuestion(String sessionId) throws Exception {
    Await.until("a pending question", () -> !"[]".equals(questions(sessionId).body()));
    var listed = questions(sessionId);
    assertEquals(200, listed.statusCode());
    assertEquals(Optional.of("application/json"), listed.headers().firstValue("Content-Type"));
    var questions = mapper.readValue(listed.body(), List.class);
    assertEquals(1, questions.size(), listed::body);
    return (Map<?, ?>) questions.getFirst();
  }

  private HttpResponse<String> questions(String sessionId) {
    try {
      return get(questionsPath(sessionId), "alice");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertResult(String sessionId, String type) throws Exception {
    var result = get("/sessions/" + sessionId + "/result?timeout=300", "alice");
    assertEquals(200, result.statusCode());
    assertEquals(type, mapper.readValue(result.body(), Map.class).get("type"), result::body);
  }

  /** The content of the tool message in the model's second call. */
  private String toolResultSentToTheModel() {
    return model.calls().get(1).getLast().content();
  }

  private String create() throws Exception {
    var resp = send("POST", "/sessions", "alice", "{}");
    assertEquals(201, resp.statusCode(), resp::body);
    return (String) mapper.readValue(resp.body(), Map.class).get("sessionId");
  }

  private static String messagesPath(String sessionId) {
    return "/sessions/" + sessionId + "/messages";
  }

  private static String questionsPath(String sessionId) {
    return "/sessions/" + sessionId + "/questions";
  }

  private static String answerPath(String sessionId, Map<?, ?> question) {
    return questionsPath(sessionId) + "/" + question.get("questionId");
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    return send("GET", path, token, BodyPublishers.noBody());
  }

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    return send(method, path, token, BodyPublishers.ofString(body));
  }

  private HttpResponse<String> send(String method, String path, String token, BodyPublisher body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1" + path))
            .timeout(Await.HANG_GUARD)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  private Optional<Authentication> authenticate(ServerRequest request) {
    return request
        .headers()
        .first(HeaderNames.AUTHORIZATION)
        .filter(header -> header.startsWith("Bearer "))
        .map(header -> identities.get(header.substring("Bearer ".length())));
  }

  private SessionOptions options(SessionRequest request) {
    var write =
        Tool.newBuilder()
            .withName("Write")
            .withDescription("write")
            .withExecutor(
                (args, ctx) -> {
                  executed.add(args);
                  return ToolResult.success("wrote");
                })
            .build();
    var binding =
        ToolBinding.newBuilder(write)
            .withCategory(ToolCategory.WRITE)
            .withPermissionKeyExtractor(
                args -> new ToolPermissionKey("Write", (String) args.get("path")))
            .build();
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(request.sessionId())
        .withTools(new ToolRegistry(List.of(binding)))
        .withPermission(Permission.empty(PermissionMode.DEFAULT))
        .build();
  }
}
