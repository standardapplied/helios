/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * One conversation every provider's request snapshots share: a system prompt, a user turn with an
 * inline image, an assistant turn that thought and called two tools, and both tool results.
 */
public final class ConversationFixture {

  /** The structured answer the snapshot schema describes. */
  public record Answer(String summary) {}

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

  private ConversationFixture() {}

  /**
   * The conversation, with {@code assistantMetadata} on the assistant turn: the provider's record
   * of the thinking that preceded its tool calls.
   */
  public static List<Message> history(Map<String, String> assistantMetadata) {
    return List.of(
        Message.system("You are a precise assistant."),
        Message.user(
            "What is in this picture, and what is the weather in Paris and Rome?",
            List.of(InlineFile.of(PNG, "image/png"))),
        Message.assistant(
            "Let me check both cities.",
            List.of(weatherCall("call_1", "Paris"), weatherCall("call_2", "Rome")),
            assistantMetadata),
        Message.tool("call_1", "weather", "18C and sunny"),
        Message.tool("call_2", "weather", "22C and cloudy"));
  }

  /** The one tool the conversation offers. */
  public static List<Tool> tools() {
    return List.of(
        Tool.newBuilder()
            .withName("weather")
            .withDescription("Current weather for a city")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("city")
                    .withType(ParameterType.STRING)
                    .withDescription("The city name")
                    .withRequired(true)
                    .build())
            .withExecutor((arguments, context) -> ToolResult.success("ok"))
            .build());
  }

  /** The output schema of the structured snapshots. */
  public static OutputSchema<Answer> schema() {
    return OutputSchema.of(Answer.class);
  }

  private static ToolCall weatherCall(String id, String city) {
    return ToolCall.newBuilder()
        .withId(id)
        .withName("weather")
        .withArguments(Map.of("city", city))
        .build();
  }
}
