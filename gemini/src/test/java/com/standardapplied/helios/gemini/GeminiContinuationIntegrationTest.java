/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.Accepted;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A multi-turn tool-calling exchange against the real Gemini Interactions API: the first turn,
 * forced to call the tool, returns the interaction id, and the continuation request that names it
 * is accepted. That the continuation carries {@code system_instruction}, the bug fixed in 2.6.1, is
 * asserted on the request itself by {@code GeminiConversationRequestTest}.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiContinuationIntegrationTest {

  private static Model model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config);
  }

  @AfterAll
  static void tearDown() {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void aContinuationAfterAForcedToolCallIsAccepted() {
    var systemPrompt =
        "You are a helpful assistant. Always include the exact word \"PINEAPPLE\" somewhere in"
            + " every response you produce.";

    var tool =
        Tool.newBuilder()
            .withName("get_temperature")
            .withDescription("Returns the current temperature for a city")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("city")
                    .withType(ParameterType.STRING)
                    .withDescription("City name")
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("22°C and sunny"))
            .build();

    var tools = List.of(tool);

    var messages = new ArrayList<Message>();
    messages.add(Message.system(systemPrompt));
    messages.add(Message.user("What is the temperature in Paris?"));

    try (var forced =
        new GeminiProvider()
            .create(
                GeminiModelId.GEMINI_3_8_FLASH.id(),
                ModelConfig.newBuilder()
                    .withApiKey(System.getenv("GEMINI_API_KEY"))
                    .withToolChoice(ToolChoice.required("get_temperature"))
                    .build())) {
      var response1 = Accepted.toolTurn(forced.chat(messages, tools));

      assertEquals(FinishReason.TOOL_CALLS, response1.finishReason());
      assertNotNull(
          response1.metadata().get(ContinuationPoint.INTERACTION_ID_KEY),
          "response must carry interactionId for continuation");

      messages.add(response1.toMessage());
      for (var call : response1.toolCalls()) {
        assertEquals("get_temperature", call.name());
        messages.add(Message.tool(call.id(), call.name(), "22°C and sunny in Paris"));
      }
    }

    Accepted.toolTurn(model.chat(messages, tools));
  }

  /**
   * A continuation whose interaction the server no longer holds is resent with the whole
   * conversation: the test deletes the first turn's interaction through the Interactions API.
   */
  @Test
  void aTurnAfterItsInteractionWasDeletedIsAcceptedThroughTheResend() throws Exception {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    var messages = new ArrayList<>(List.of(Message.user("Name one primary colour.")));
    try (var flash = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config);
        var client = HttpClientFactory.create()) {
      var first = Accepted.textReply(flash.chat(messages, List.of()));
      var interactionId = first.metadata().get(ContinuationPoint.INTERACTION_ID_KEY);
      assertNotNull(interactionId, "response must carry interactionId for continuation");

      var deleted =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          GeminiEndpoint.of(config).baseUrl() + "/interactions/" + interactionId))
                  .header("x-goog-api-key", apiKey)
                  .DELETE()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(200, deleted.statusCode());

      messages.add(first.toMessage());
      messages.add(Message.user("Name another."));
      Accepted.textReply(flash.chat(messages, List.of()));
    }
  }
}
