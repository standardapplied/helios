/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Integration test verifying system instruction persistence across multi-turn tool-calling
 * interactions against the real Gemini Interactions API.
 *
 * <p>Reproduces the bug fixed in 2.6.1: the continuation path (requests using {@code
 * previous_interaction_id}) was not re-sending {@code system_instruction}, causing the model to
 * lose its persona and behavioral constraints after the first tool call.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiContinuationIntegrationTest {

  private static GeminiModel model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
  }

  @AfterAll
  static void tearDown() {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void systemInstructionSurvivesContinuation() {
    var codeWord = "PINEAPPLE";
    var systemPrompt =
        "You are a helpful assistant. CRITICAL RULE: you must always include the exact word \""
            + codeWord
            + "\" somewhere in every response you produce, no exceptions.";

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

    // Turn 1: user asks a question that requires tool use
    var messages = new ArrayList<Message>();
    messages.add(Message.system(systemPrompt));
    messages.add(Message.user("What is the temperature in Paris?"));

    Response<Void> response1 = model.chat(messages, tools);

    // Model should call the tool
    assertEquals(FinishReason.TOOL_CALLS, response1.finishReason());
    assertFalse(response1.toolCalls().isEmpty());
    var tc = response1.toolCalls().getFirst();
    assertEquals("get_temperature", tc.name());

    // Verify interactionId was captured (needed for continuation)
    assertNotNull(
        response1.metadata().get(GeminiModel.INTERACTION_ID_KEY),
        "response must carry interactionId for continuation");

    // Turn 2: send tool results back — this exercises the continuation path
    messages.add(Message.assistant(null, response1.toolCalls(), response1.metadata()));
    messages.add(Message.tool(tc.id(), tc.name(), "22°C and sunny in Paris"));

    Response<Void> response2 = model.chat(messages, tools);

    assertEquals(FinishReason.STOP, response2.finishReason());
    assertFalse(response2.content().isBlank(), "model must produce a text response");

    // The critical assertion: the model's response on the continuation turn must contain the
    // code word from the system instruction. If system_instruction was dropped on the
    // continuation request, the model wouldn't know about this rule.
    assertTrue(
        response2.content().toUpperCase().contains(codeWord),
        "system instruction must survive continuation — expected \""
            + codeWord
            + "\" in response but got: "
            + response2.content());
  }
}
