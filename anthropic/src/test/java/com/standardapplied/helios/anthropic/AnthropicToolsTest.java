/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicToolsTest {

  @Test
  void buildRequestWithTools() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var messages = List.of(Message.user("Weather?"));
    var request = requests.build(messages, ConversationFixture.tools(), null);

    assertNotNull(request.tools());
    assertEquals(1, request.tools().size());
    assertEquals("weather", request.tools().getFirst().name());
    assertEquals("Current weather for a city", request.tools().getFirst().description());
    assertNotNull(request.tools().getFirst().inputSchema());
  }

  @Test
  void buildRequestNoToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestNullToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = requests.build(List.of(Message.user("Hi")), null, null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestToolChoiceAuto() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.auto()).build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);

    assertNotNull(request.toolChoice());
    assertEquals("auto", request.toolChoice().type());
  }

  @Test
  void buildRequestToolChoiceAny() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.any()).build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);

    assertNotNull(request.toolChoice());
    assertEquals("any", request.toolChoice().type());
  }

  @Test
  void buildRequestToolChoiceNoneReturnsNull() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.none()).build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);

    assertNull(request.toolChoice());
  }

  @Test
  void buildRequestToolChoiceRequiredSingle() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withToolChoice(ToolChoice.required("my_tool"))
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("my_tool")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);

    assertNotNull(request.toolChoice());
    assertEquals("tool", request.toolChoice().type());
    assertEquals("my_tool", request.toolChoice().name());
  }

  @Test
  void buildRequestToolChoiceRequiredMultipleThrows() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withToolChoice(ToolChoice.required("tool1", "tool2"))
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("tool1")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    assertThrows(
        IllegalStateException.class,
        () -> requests.build(List.of(Message.user("Hi")), List.of(tool), null));
  }

  @Test
  void fable51AcceptsAutoAndNoneToolChoice() {
    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var auto =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.auto()).build();
    var autoRequest =
        requests(AnthropicModelId.CLAUDE_FABLE_5_1, auto)
            .build(List.of(Message.user("Hi")), List.of(tool), null);
    assertEquals("auto", autoRequest.toolChoice().type());
    assertNull(autoRequest.thinking());
    assertNull(autoRequest.temperature());

    var none =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.none()).build();
    var noneRequest =
        requests(AnthropicModelId.CLAUDE_FABLE_5_1, none)
            .build(List.of(Message.user("Hi")), List.of(tool), null);
    assertNull(noneRequest.toolChoice());
  }

  @Test
  void the55ModelsAcceptAutoToolChoice() {
    for (var modelId :
        List.of(AnthropicModelId.CLAUDE_OPUS_5_5, AnthropicModelId.CLAUDE_SONNET_5_5)) {
      var config =
          ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.auto()).build();

      var request = requests(modelId, config).build(List.of(Message.user("Hi")), List.of(), null);

      assertEquals("auto", request.toolChoice().type(), modelId.name());
    }
  }
}
