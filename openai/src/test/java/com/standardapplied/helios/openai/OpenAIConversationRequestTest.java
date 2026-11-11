/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.ConversationRequestContract;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ConversationRequestContract} for the Responses API: {@code instructions} and {@code
 * input}.
 */
class OpenAIConversationRequestTest extends ConversationRequestContract {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  protected Function<URI, Model> modelAt() {
    return uri ->
        new OpenAIProvider()
            .create(
                OpenAIModelId.GPT_4_1_MINI.id(),
                ModelConfig.newBuilder()
                    .withApiKey("test-key")
                    .withBaseUrl(uri + "/v1/responses")
                    .build());
  }

  @Override
  protected String reply() {
    return "openai/streams/text.sse";
  }

  @Override
  protected String toolCallReply() {
    return "openai/streams/null-argument.sse";
  }

  @Override
  protected Map<String, Object> replayedArguments(Map<String, Object> request) {
    var call =
        objects(request.get("input")).stream()
            .filter(item -> "function_call".equals(item.get("type")))
            .findFirst()
            .orElseThrow();
    return parse((String) call.get("arguments"));
  }

  @Override
  protected String systemPrompt(Map<String, Object> request) {
    return (String) request.get("instructions");
  }

  @Override
  protected List<String> turnTexts(Map<String, Object> request) {
    return objects(request.get("input")).stream()
        .flatMap(item -> texts(item.get("content")))
        .toList();
  }

  /**
   * The partial turn the session tags is sent as one assistant message holding one {@code
   * output_text} part, between the two user messages, with no other item.
   */
  @Test
  void aPartialTurnIsSentAsOneAssistantMessageOfOutputTextOnly() {
    var partial =
        Message.assistant("Once upon a time", List.of(), Map.of("helios.origin", "partial-turn"));
    var requests =
        ModelHarness.exchange(
            List.of(Golden.read(reply())),
            modelAt(),
            model ->
                model.chat(
                    List.of(Message.user("Tell me a story."), partial, Message.user("Go on.")),
                    List.of()));

    var input = objects(parse(requests.getFirst().body()).get("input"));
    assertEquals(
        List.of("user", "assistant", "user"),
        input.stream().map(item -> item.get("role")).toList());
    assertEquals(
        List.of(Map.of("type", "output_text", "text", "Once upon a time")),
        objects(input.get(1).get("content")));
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Map<String, Object> parse(String body) {
    return JSON.readValue(body, Map.class);
  }
}
