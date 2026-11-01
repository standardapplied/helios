/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.Accepted;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link ToolChoice#required(String...)} against the real Gemini Interactions API: on a prompt that
 * needs no tool, the API itself makes the model call the one tool named of the two offered.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiToolChoiceIntegrationTest {

  private static final String REQUIRED_TOOL = "get_weather";

  @Test
  void requiredForcesACallToTheNamedToolOnAPromptThatNeedsNone() {
    var tools =
        List.of(
            tool(REQUIRED_TOOL, "Returns the weather"), tool("lookup_time", "Returns the time"));
    var messages = List.of(Message.user("Say hello in one short sentence. Do not use any tools."));
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("GEMINI_API_KEY"))
            .withReasoning(new Reasoning.Effort(Level.LOW, Display.HIDDEN))
            .withToolChoice(ToolChoice.required(REQUIRED_TOOL))
            .build();

    try (var forced = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config)) {
      var turn = Accepted.toolTurn(forced.chat(messages, tools));

      assertEquals(FinishReason.TOOL_CALLS, turn.finishReason());
      for (var call : turn.toolCalls()) {
        assertEquals(REQUIRED_TOOL, call.name());
      }
    }
  }

  private static Tool tool(String name, String description) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription(description)
        .withExecutor((arguments, context) -> ToolResult.success("ok"))
        .build();
  }
}
