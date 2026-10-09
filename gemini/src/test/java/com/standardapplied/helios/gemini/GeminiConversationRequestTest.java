/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.core.test.ConversationRequestContract;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
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

  @Override
  @SuppressWarnings("unchecked")
  protected Map<String, Object> parse(String body) {
    return JSON.readValue(body, Map.class);
  }
}
