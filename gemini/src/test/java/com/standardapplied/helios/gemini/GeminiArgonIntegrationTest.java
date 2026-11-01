/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.Accepted;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The one test that calls Gemini 4 Argon: a forced function call, then its result sent back in a
 * stateless request that replays the thought signatures from the history.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiArgonIntegrationTest {

  private static final String TOOL_NAME = "get_temperature";

  @Test
  void aForcedToolCallIsAnsweredThroughItsReplayedThoughtSignatures() {
    var tools = List.of(temperatureTool());
    var messages = new ArrayList<>(List.of(Message.user("What is the temperature in Paris?")));
    var provider = new GeminiProvider();

    try (var forced =
        provider.create(
            GeminiModelId.GEMINI_4_ARGON.id(),
            settings().withToolChoice(ToolChoice.required(TOOL_NAME)).build())) {
      var turn = Accepted.toolTurn(forced.chat(messages, tools));

      assertEquals(FinishReason.TOOL_CALLS, turn.finishReason());
      assertFalse(turn.metadata().get(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY).isEmpty());
      messages.add(turn.toMessage());
      for (var call : turn.toolCalls()) {
        assertEquals(TOOL_NAME, call.name());
        messages.add(Message.tool(call.id(), call.name(), "22°C and sunny in Paris"));
      }
    }

    try (var model = provider.create(GeminiModelId.GEMINI_4_ARGON.id(), settings().build())) {
      Accepted.textReply(model.chat(messages, tools));
    }
  }

  private static ModelConfig.Builder settings() {
    return ModelConfig.newBuilder()
        .withApiKey(System.getenv("GEMINI_API_KEY"))
        .withReasoning(new Reasoning.Effort(Level.LOW, Display.HIDDEN))
        .withProviderContinuation(false);
  }

  private static Tool temperatureTool() {
    return Tool.newBuilder()
        .withName(TOOL_NAME)
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
  }
}
