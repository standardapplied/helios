/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.core.test.ConversationRequestContract;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.test.SseReplies;
import com.standardapplied.helios.core.test.StubHttpServer;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ConversationRequestContract} for the Interactions API: {@code system_instruction} and
 * {@code input}. A continuation request, which names the previous interaction instead of resending
 * the turns, still carries the system instruction: the API does not keep it across interactions.
 */
class GeminiConversationRequestTest extends ConversationRequestContract {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final StubHttpServer.Reply GONE =
      new StubHttpServer.Reply(
          404,
          Map.of("Content-Type", "application/json"),
          "{\"error\":{\"code\":\"not_found\",\"message\":\"Interaction not found\"}}");

  @Override
  protected Function<URI, Model> modelAt() {
    return uri ->
        new GeminiProvider()
            .create(
                GeminiModelId.GEMINI_3_5_FLASH.id(),
                ModelConfig.newBuilder()
                    .withApiKey("test-key")
                    .withBaseUrl(uri + "/v1beta")
                    .build());
  }

  @Override
  protected String reply() {
    return "gemini/streams/text.sse";
  }

  @Override
  protected String toolCallReply() {
    return "gemini/streams/null-argument.sse";
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Map<String, Object> replayedArguments(Map<String, Object> request) {
    return objects(request.get("input")).stream()
        .filter(step -> "function_call".equals(step.get("type")))
        .map(step -> (Map<String, Object>) step.get("arguments"))
        .findFirst()
        .orElseThrow();
  }

  @Override
  protected String systemPrompt(Map<String, Object> request) {
    return (String) request.get("system_instruction");
  }

  @Override
  protected List<String> turnTexts(Map<String, Object> request) {
    return objects(request.get("input")).stream()
        .flatMap(item -> texts(item.get("content")))
        .toList();
  }

  @Test
  void theSystemInstructionSurvivesAContinuation() {
    var system = "You are a helpful assistant. Always include the word PINEAPPLE.";
    var history =
        new ArrayList<>(List.of(Message.system(system), Message.user("Weather in Paris?")));
    var replies = List.of(Golden.read("gemini/streams/tool-calls.sse"), Golden.read(reply()));

    var requests =
        ModelHarness.exchange(
            replies,
            modelAt(),
            model -> {
              var first = model.chat(history, ConversationFixture.tools());
              history.add(first.toMessage());
              for (var call : first.toolCalls()) {
                history.add(Message.tool(call.id(), call.name(), "22C and sunny"));
              }
              model.chat(history, ConversationFixture.tools());
            });

    var continuation = parse(requests.getLast().body());
    assertEquals(2, requests.size());
    assertNotNull(continuation.get("previous_interaction_id"));
    assertEquals(system, continuation.get("system_instruction"));
  }

  /** The history {@code SummarizingCompactor} leaves: no assistant turn, so no interaction id. */
  @Test
  void aCompactedHistoryIsSentWholeWithoutAPreviousInteraction() {
    var history =
        new ArrayList<>(List.of(Message.system("sys"), Message.user("Weather in Paris?")));
    var replies = List.of(Golden.read("gemini/streams/tool-calls.sse"), Golden.read(reply()));

    var requests =
        ModelHarness.exchange(
            replies,
            modelAt(),
            model -> {
              var first = model.chat(history, ConversationFixture.tools());
              history.add(first.toMessage());
              var compacted =
                  List.of(
                      Message.system("sys"),
                      Message.user("[Earlier context summary]\nParis is 22C and sunny."));
              assertNull(
                  ContinuationPoint.find(
                      compacted, PromptStamps.of(compacted, ConversationFixture.tools())));
              model.chat(compacted, ConversationFixture.tools());
            });

    var compactedRequest = parse(requests.getLast().body());
    assertNull(compactedRequest.get("previous_interaction_id"));
    assertEquals(
        List.of("[Earlier context summary]\nParis is 22C and sunny."), turnTexts(compactedRequest));
  }

  @Test
  void aRequestsStampFollowsItsSystemInstructionAndToolsOnly() {
    var tools = List.of(tool("weather", "Current weather"));
    var stamp = stamp(GeminiModelId.GEMINI_3_5_FLASH, "sys", tools);

    assertEquals(stamp, stamp(GeminiModelId.GEMINI_3_5_FLASH, "sys", tools));
    assertEquals(stamp, stamp(GeminiModelId.GEMINI_3_8_FLASH, "sys", tools));
    assertNotEquals(stamp, stamp(GeminiModelId.GEMINI_3_5_FLASH, "other", tools));
    assertNotEquals(
        stamp,
        stamp(
            GeminiModelId.GEMINI_3_5_FLASH,
            "sys",
            List.of(tool("weather", "Current weather"), tool("time", "Current time"))));
    assertNotEquals(
        stamp, stamp(GeminiModelId.GEMINI_3_5_FLASH, "sys", List.of(tool("weather", "Forecast"))));
    assertEquals(64, stamp.length());
  }

  @Test
  void theAssistantTurnCarriesTheStampOfTheRequestThatProducedIt() {
    var history = List.of(Message.system("sys"), Message.user("Hi"));
    var stamps = new ArrayList<String>();

    ModelHarness.exchange(
        List.of(Golden.read(reply())),
        modelAt(),
        model ->
            stamps.add(
                model
                    .chat(history, ConversationFixture.tools())
                    .metadata()
                    .get(ContinuationPoint.PROMPT_STAMP_KEY)));

    assertEquals(List.of(PromptStamps.of(history, ConversationFixture.tools())), stamps);
  }

  @Test
  void aTurnProducedUnderAnotherPromptIsSentWholeWithoutAPreviousInteraction() {
    var history = new ArrayList<>(List.of(Message.system("before"), Message.user("Hi")));

    var requests =
        ModelHarness.exchange(
            List.of(Golden.read(reply())),
            modelAt(),
            model -> {
              history.add(model.chat(history, List.of()).toMessage());
              history.set(0, Message.system("after"));
              history.add(Message.user("Again"));
              model.chat(history, List.of());
            });

    var second = parse(requests.getLast().body());
    assertNull(second.get("previous_interaction_id"));
    assertEquals("after", second.get("system_instruction"));
    assertEquals(List.of("Hi", "Hello, world.", "Again"), turnTexts(second));
  }

  @Test
  void aGoneInteractionIsResentOnceWithTheWholeConversation() throws IOException {
    var history = continuedHistory();
    try (var server = StubHttpServer.start(InetAddress.getLoopbackAddress(), 0, goneThenText());
        var model = modelAt().apply(server.uri())) {
      var response = model.chat(history, List.of());

      assertEquals("Hello, world.", response.content());
      var requests = server.requests();
      assertEquals(2, requests.size());
      assertEquals("int_gone", parse(requests.get(0).body()).get("previous_interaction_id"));
      var resend = parse(requests.get(1).body());
      assertNull(resend.get("previous_interaction_id"));
      assertEquals(List.of("Hi", "Earlier answer", "Again"), turnTexts(resend));
    }
  }

  @Test
  void anInteractionGoneOnTheResendFailsTheTurn() throws IOException {
    var history = continuedHistory();
    try (var server = StubHttpServer.start(InetAddress.getLoopbackAddress(), 0, request -> GONE);
        var model = modelAt().apply(server.uri())) {
      var error = assertThrows(GeminiException.class, () -> model.chat(history, List.of()));

      assertEquals(404, error.statusCode());
      assertEquals(2, server.requests().size());
    }
  }

  @Test
  void aWholeConversationAnswered404IsNotResent() throws IOException {
    var history = List.of(Message.user("Hi"));
    try (var server = StubHttpServer.start(InetAddress.getLoopbackAddress(), 0, request -> GONE);
        var model = modelAt().apply(server.uri())) {
      var error = assertThrows(GeminiException.class, () -> model.chat(history, List.of()));

      assertEquals(404, error.statusCode());
      assertEquals(1, server.requests().size());
    }
  }

  private Function<StubHttpServer.Request, StubHttpServer.Reply> goneThenText() {
    var text = SseReplies.stream(Golden.read(reply()));
    return request -> request.body().contains("previous_interaction_id") ? GONE : text;
  }

  /** A conversation whose assistant turn continues interaction {@code int_gone}. */
  private static List<Message> continuedHistory() {
    var asked = List.of(Message.user("Hi"));
    var metadata =
        Map.of(
            ContinuationPoint.INTERACTION_ID_KEY,
            "int_gone",
            ContinuationPoint.PROMPT_STAMP_KEY,
            PromptStamps.of(asked, List.of()));
    return List.of(
        Message.user("Hi"),
        Message.assistant("Earlier answer", List.of(), metadata),
        Message.user("Again"));
  }

  private static String stamp(GeminiModelId model, String system, List<Tool> tools) {
    return new GeminiRequestBuilder(model, ModelConfig.of("k"))
        .build(List.of(Message.system(system), Message.user("Hi")), tools, null)
        .promptStamp();
  }

  private static Tool tool(String name, String description) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription(description)
        .withExecutor((arguments, context) -> ToolResult.success("ok"))
        .build();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Map<String, Object> parse(String body) {
    return JSON.readValue(body, Map.class);
  }
}
