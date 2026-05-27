/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.anthropic.api.ContentBlock;
import ai.singlr.anthropic.api.MessagesRequest;
import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.InlineFile;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.model.ToolChoice;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredOutputParseException;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicModelTest {

  private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

  @Test
  void constructorRequiresModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertThrows(
        IllegalArgumentException.class, () -> new AnthropicModel((AnthropicModelId) null, config));
  }

  @Test
  void constructorRequiresConfig() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, null));
  }

  @Test
  void constructorRequiresApiKey() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config));
  }

  @Test
  void constructorRequiresNonBlankApiKey() {
    var config = ModelConfig.newBuilder().withApiKey("   ").build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config));
  }

  @Test
  void idReturnsModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    assertEquals(AnthropicModelId.CLAUDE_SONNET_4_6.id(), model.id());
  }

  @Test
  void providerReturnsAnthropic() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    assertEquals("anthropic", model.provider());
  }

  @Test
  void contextWindowReturnsModelValue() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    assertEquals(1_000_000, model.contextWindow());
  }

  @Test
  void userMessageWithImageAttachmentEmitsImageBlock() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    var userMessage = Message.user("look at this", List.of(InlineFile.of(pngBytes, "image/png")));

    var request = model.buildRequest(List.of(userMessage), List.of(), null);

    var entry = request.messages().getFirst();
    assertEquals("user", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    assertEquals(2, blocks.size());
    var imageBlock = blocks.get(0);
    assertTrue(imageBlock.hasTypeImage());
    assertEquals("image/png", imageBlock.source().mediaType());
    assertEquals("base64", imageBlock.source().type());
    assertEquals(
        java.util.Base64.getEncoder().encodeToString(pngBytes), imageBlock.source().data());
    var textBlock = blocks.get(1);
    assertTrue(textBlock.hasTypeText());
    assertEquals("look at this", textBlock.text());
  }

  @Test
  void userMessageWithPdfAttachmentEmitsDocumentBlock() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var pdfBytes = "%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var userMessage =
        Message.user("summarize this", List.of(InlineFile.of(pdfBytes, "application/pdf")));

    var request = model.buildRequest(List.of(userMessage), List.of(), null);

    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) request.messages().getFirst().content();
    assertTrue(blocks.get(0).hasTypeDocument());
    assertEquals("application/pdf", blocks.get(0).source().mediaType());
  }

  @Test
  void userMessageWithTextAttachmentInlinesAsTextBlock() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var csvBytes = "a,b\n1,2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var userMessage = Message.user("", List.of(InlineFile.of(csvBytes, "text/csv")));

    var request = model.buildRequest(List.of(userMessage), List.of(), null);

    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) request.messages().getFirst().content();
    assertEquals(1, blocks.size());
    assertTrue(blocks.get(0).hasTypeText());
    assertTrue(blocks.get(0).text().contains("[attachment text/csv]"), blocks.get(0).text());
    assertTrue(blocks.get(0).text().contains("a,b"), blocks.get(0).text());
  }

  @Test
  void buildRequestExtractsSystemMessage() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var messages = List.of(Message.system("You are helpful"), Message.user("Hello"));

    var request = model.buildRequest(messages, List.of(), null);

    assertEquals("You are helpful", request.systemAsText());
    assertEquals(1, request.messages().size());
    assertEquals("user", request.messages().getFirst().role());
  }

  @Test
  void buildRequestCoalescesToolMessages() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var toolCalls =
        List.of(
            ToolCall.newBuilder().withId("call_1").withName("tool1").build(),
            ToolCall.newBuilder().withId("call_2").withName("tool2").build());
    var messages =
        List.of(
            Message.user("Do something"),
            Message.assistant("Sure", toolCalls),
            Message.tool("call_1", "tool1", "result1"),
            Message.tool("call_2", "tool2", "result2"));

    var request = model.buildRequest(messages, List.of(), null);

    assertEquals(3, request.messages().size());
    assertEquals("user", request.messages().get(0).role());
    assertEquals("assistant", request.messages().get(1).role());
    assertEquals("user", request.messages().get(2).role());

    @SuppressWarnings("unchecked")
    var toolResults = (List<ContentBlock>) request.messages().get(2).content();
    assertEquals(2, toolResults.size());
    assertTrue(toolResults.get(0).hasTypeToolResult());
    assertEquals("call_1", toolResults.get(0).toolUseId());
    assertEquals("result1", toolResults.get(0).content());
    assertTrue(toolResults.get(1).hasTypeToolResult());
    assertEquals("call_2", toolResults.get(1).toolUseId());
    assertEquals("result2", toolResults.get(1).content());
  }

  @Test
  void buildRequestWithTools() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("get_weather")
            .withDescription("Get weather")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("city")
                    .withType(ParameterType.STRING)
                    .withDescription("City name")
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("sunny"))
            .build();

    var messages = List.of(Message.user("Weather?"));
    var request = model.buildRequest(messages, List.of(tool), null);

    assertNotNull(request.tools());
    assertEquals(1, request.tools().size());
    assertEquals("get_weather", request.tools().getFirst().name());
    assertEquals("Get weather", request.tools().getFirst().description());
    assertNotNull(request.tools().getFirst().inputSchema());
  }

  @Test
  void buildRequestDefaultMaxTokensFallsBackToModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(AnthropicModelId.CLAUDE_SONNET_4_6.maxOutputTokens(), request.maxTokens());
  }

  @Test
  void buildRequestPerModelDefaultDiffersByModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var sonnetModel = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var opus47Model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var sonnetReq = sonnetModel.buildRequest(List.of(Message.user("Hi")), List.of(), null);
    var opusReq = opus47Model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(64_000, sonnetReq.maxTokens());
    assertEquals(32_000, opusReq.maxTokens());
  }

  @Test
  void modelExposesMaxOutputTokensFromModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    assertEquals(AnthropicModelId.CLAUDE_OPUS_4_7.maxOutputTokens(), model.maxOutputTokens());
  }

  @Test
  void buildRequestCustomMaxTokens() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withMaxOutputTokens(8192).build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(8192, request.maxTokens());
  }

  @Test
  void buildRequestWithThinking() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.thinking());
    assertEquals("enabled", request.thinking().type());
    assertEquals(10000, request.thinking().budgetTokens());
    assertNull(request.temperature());
    assertTrue(request.maxTokens() >= 10000 + 1024);
  }

  @Test
  void opus47UsesAdaptiveThinkingShape() {
    // 1.1.5 bug #2: Opus 4.7 rejects "thinking.type=enabled" — must use adaptive + output_config.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.thinking());
    assertEquals("adaptive", request.thinking().type());
    assertNull(
        request.thinking().budgetTokens(),
        "adaptive shape must NOT include budget_tokens — Opus 4.7 rejects it");
    assertNotNull(request.outputConfig(), "adaptive shape requires output_config sibling");
    assertEquals("medium", request.outputConfig().effort());
    assertNull(request.temperature(), "thinking forces null temperature for adaptive too");
  }

  @Test
  void opus47AdaptiveMapsAllThinkingLevels() {
    var levels =
        java.util.Map.of(
            ThinkingLevel.MINIMAL, "low",
            ThinkingLevel.LOW, "low",
            ThinkingLevel.MEDIUM, "medium",
            ThinkingLevel.HIGH, "high");
    for (var entry : levels.entrySet()) {
      var config =
          ModelConfig.newBuilder().withApiKey("test-key").withThinkingLevel(entry.getKey()).build();
      var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
      var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);
      assertEquals(
          entry.getValue(),
          request.outputConfig().effort(),
          "ThinkingLevel." + entry.getKey() + " must map to effort=" + entry.getValue());
    }
  }

  @Test
  void opus47AdaptiveMapsXhighToWireString() {
    // XHIGH is the second-highest effort tier per Anthropic's adaptive-thinking docs and is
    // available on Opus 4.7 only. The doc specifies the literal wire string "xhigh".
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals(
        "xhigh",
        request.outputConfig().effort(),
        "XHIGH must produce the literal 'xhigh' wire string Anthropic's API expects");
  }

  @Test
  void opus47AdaptiveMapsMaxToWireString() {
    // MAX is the unbounded effort tier — "always thinks with no constraints on thinking depth".
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals("max", request.outputConfig().effort());
  }

  @Test
  void opus47AdaptiveDefaultsDisplayToSummarized() {
    // Anthropic's docs say `thinking.display` silently defaults to "omitted" on Opus 4.7, which
    // would zero out our ModelChunk.ThinkingDelta event stream — silently breaking every caller
    // that watches AssistantThinking events. Helios pins the default to "summarized" so the event
    // contract is preserved; callers who want the omitted-mode latency win opt in explicitly.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(
        "summarized",
        request.thinking().display(),
        "Opus 4.7 adaptive default must be summarized so ThinkingDelta events keep flowing");
  }

  @Test
  void opus46RejectsXhighEffort() {
    // XHIGH is Opus 4.7-only per Anthropic's docs. On older models we refuse at build time
    // rather than silently downgrade or wait for the API's 400 — typed exceptions belong at the
    // caller-controlled layer.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> model.buildRequest(List.of(Message.user("Hi")), List.of(), null));
    assertTrue(
        ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("xhigh"),
        () -> "exception must name the rejected effort: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("Opus 4.7") || ex.getMessage().contains("opus-4-7"),
        () -> "exception must name the supported model: " + ex.getMessage());
  }

  @Test
  void opus46RejectsMaxEffort() {
    // MAX has no equivalent in legacy enabled+budget_tokens. Reject rather than silently degrade
    // to HIGH (a 32k budget) — callers who asked for MAX deserve to know it isn't honoured.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> model.buildRequest(List.of(Message.user("Hi")), List.of(), null));
    assertTrue(
        ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("max"),
        () -> "exception must name the rejected effort: " + ex.getMessage());
  }

  @Test
  void opus46KeepsLegacyEnabledShape() {
    // Older models still use enabled+budget_tokens. Don't break them with the adaptive change.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertEquals("enabled", request.thinking().type(), "Opus 4.6 stays on legacy shape");
    assertEquals(10000, request.thinking().budgetTokens());
    assertNull(request.outputConfig(), "legacy shape must NOT carry output_config");
  }

  @Test
  void buildRequestThinkingNoneOmitsConfig() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.thinking());
  }

  @Test
  void buildRequestThinkingMinimal() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MINIMAL)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(1024, request.thinking().budgetTokens());
  }

  @Test
  void buildRequestThinkingLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.LOW)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(4096, request.thinking().budgetTokens());
  }

  @Test
  void buildRequestThinkingHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(32000, request.thinking().budgetTokens());
  }

  @Test
  void buildRequestWithOutputSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());
    var request = model.buildRequest(List.of(Message.user("Extract")), List.of(), schema);

    assertNotNull(request.system());
    assertTrue(request.systemAsText().contains("JSON"));
    assertTrue(request.systemAsText().contains("schema"));
  }

  @Test
  void buildRequestToolChoiceAuto() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.auto()).build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertNotNull(request.toolChoice());
    assertEquals("auto", request.toolChoice().type());
  }

  @Test
  void buildRequestToolChoiceAny() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.any()).build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertNotNull(request.toolChoice());
    assertEquals("any", request.toolChoice().type());
  }

  @Test
  void buildRequestToolChoiceNoneReturnsNull() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.none()).build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertNull(request.toolChoice());
  }

  @Test
  void buildRequestToolChoiceRequiredSingle() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withToolChoice(ToolChoice.required("my_tool"))
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("my_tool")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

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
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("tool1")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    assertThrows(
        IllegalStateException.class,
        () -> model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null));
  }

  @Test
  void buildRequestWithGenerationParams() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withTemperature(0.7)
            .withTopP(0.9)
            .withStopSequences(List.of("END"))
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(0.7, request.temperature());
    assertEquals(0.9, request.topP());
    assertEquals(List.of("END"), request.stopSequences());
  }

  @Test
  void buildRequestStreamsAlways() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertTrue(request.stream());
  }

  @Test
  void convertAssistantMessageSimpleText() {
    var message = Message.assistant("Hello");

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    assertEquals("Hello", entry.content());
  }

  @Test
  void convertAssistantMessageWithToolCalls() {
    var tc =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("search")
            .withArguments(Map.of("q", "test"))
            .build();
    var message = Message.assistant("", List.of(tc));

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    assertEquals(1, blocks.size());
    assertTrue(blocks.getFirst().hasTypeToolUse());
    assertEquals("call_1", blocks.getFirst().id());
    assertEquals("search", blocks.getFirst().name());
  }

  @Test
  void convertAssistantMessageWithThinkingSignature() {
    var metadata =
        Map.of(
            AnthropicModel.THINKING_KEY, "I need to think about this",
            AnthropicModel.THINKING_SIGNATURE_KEY, "sig123");
    var message = Message.assistant("Answer", List.of(), metadata);

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    assertEquals(2, blocks.size());
    assertTrue(blocks.get(0).hasTypeThinking());
    assertEquals("I need to think about this", blocks.get(0).thinking());
    assertEquals("sig123", blocks.get(0).signature());
    assertTrue(blocks.get(1).hasTypeText());
    assertEquals("Answer", blocks.get(1).text());
  }

  @Test
  void convertAssistantMessageWithEmptyThinkingSignatureUsesString() {
    var metadata = Map.of(AnthropicModel.THINKING_SIGNATURE_KEY, "");
    var message = Message.assistant("Answer", List.of(), metadata);

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    assertEquals("Answer", entry.content());
  }

  @Test
  void mapStopReasonEndTurn() {
    assertEquals(FinishReason.STOP, AnthropicModel.mapStopReason("end_turn"));
  }

  @Test
  void mapStopReasonStopSequence() {
    assertEquals(FinishReason.STOP, AnthropicModel.mapStopReason("stop_sequence"));
  }

  @Test
  void mapStopReasonToolUse() {
    assertEquals(FinishReason.TOOL_CALLS, AnthropicModel.mapStopReason("tool_use"));
  }

  @Test
  void mapStopReasonMaxTokens() {
    assertEquals(FinishReason.LENGTH, AnthropicModel.mapStopReason("max_tokens"));
  }

  @Test
  void mapStopReasonNull() {
    assertEquals(FinishReason.STOP, AnthropicModel.mapStopReason(null));
  }

  @Test
  void mapStopReasonUnknown() {
    assertEquals(FinishReason.STOP, AnthropicModel.mapStopReason("unknown"));
  }

  @Test
  void buildRequestNoToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestNullToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), null, null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestSystemAndOutputSchemaAppended() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var schema = Map.<String, Object>of("type", "object");
    var messages = List.of(Message.system("Be helpful"), Message.user("Extract"));
    var request = model.buildRequest(messages, List.of(), schema);

    assertTrue(request.systemAsText().startsWith("Be helpful"));
    assertTrue(request.systemAsText().contains("JSON"));
  }

  @Test
  void convertAssistantMessageNullContentBecomesEmpty() {
    var message =
        new Message(
            ai.singlr.core.model.Role.ASSISTANT, null, List.of(), null, null, Map.of(), List.of());

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    assertEquals("", entry.content());
  }

  @Test
  void buildRequestModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("claude-opus-4-6", request.model());
  }

  @Test
  void closeReleasesHttpClientResources() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    model.close();
  }

  @Test
  void closeIsIdempotent() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    model.close();
    model.close();
    model.close();
  }

  @Test
  void modelUsableInTryWithResources() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    try (var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config)) {
      assertEquals(AnthropicModelId.CLAUDE_OPUS_4_6.id(), model.id());
    }
  }

  public record TestPerson(String name, int age) {}

  @Test
  void parseStructuredContentPlainSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    var result =
        model.parseStructuredContent(
            "{\"name\":\"Alice\",\"age\":30}", OutputSchema.of(TestPerson.class));
    assertEquals("Alice", result.name());
    assertEquals(30, result.age());
  }

  @Test
  void parseStructuredContentProvenancedReconstructsTypedOutput() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    var json =
        "{\"output\":{\"name\":\"Alice\",\"age\":30},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[{\"url\":\"https://x.com\",\"excerpts\":[\"a\"]}],"
            + "\"reasoning\":\"named in source\",\"confidence\":\"HIGH\"},"
            + "{\"field\":\"age\",\"sources\":[],\"reasoning\":\"guess\",\"confidence\":\"LOW\"}]}";

    var result = model.parseStructuredContent(json, schema);

    assertNotNull(result);
    assertEquals("Alice", result.output().name());
    assertEquals(30, result.output().age());
    assertEquals(2, result.provenance().size());
    assertEquals("HIGH", result.forField("name").confidence().wireValue());
  }

  @Test
  void parseStructuredContentNullReturnsNull() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    assertNull(model.parseStructuredContent(null, OutputSchema.of(TestPerson.class)));
  }

  @Test
  void parseStructuredContentSchemaMismatchSurfacesFieldLevelDiff() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    var schema = OutputSchema.of(TestPerson.class);
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> model.parseStructuredContent("{\"name\":\"Alice\"}", schema));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.contains("age") && e.contains("required")),
        "diff must name the missing 'age' field as required: " + ex.errors());
    assertEquals("{\"name\":\"Alice\"}", ex.rawContent());
  }

  @Test
  void parseStructuredContentSchemaMismatchInProvenancedEnvelopeReportsNestedPath() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    // Source object missing the required 'url' field — surfaces as a deep path under provenance.
    var json =
        "{\"output\":{\"name\":\"Alice\",\"age\":30},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[{\"excerpts\":[\"a\"]}],"
            + "\"reasoning\":\"named in source\",\"confidence\":\"HIGH\"}]}";
    var ex =
        assertThrows(
            StructuredOutputParseException.class, () -> model.parseStructuredContent(json, schema));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.contains("provenance[0].sources[0].url")),
        "diff must include the deep path 'provenance[0].sources[0].url': " + ex.errors());
  }

  @Test
  void parseStructuredContentSyntaxErrorThrowsStructuredOutputParseException() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                model.parseStructuredContent(
                    "{\"name\":\"Alice\",unterminated", OutputSchema.of(TestPerson.class)));
    assertTrue(ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")));
  }

  @Test
  void readBoundedErrorBodyCapsAtLimitAndMarksTruncation() throws Exception {
    var oversized = new byte[MAX_ERROR_BODY_BYTES + 1024];
    java.util.Arrays.fill(oversized, (byte) 'x');
    var result =
        HttpClientFactory.readBoundedErrorBody(new java.io.ByteArrayInputStream(oversized));
    assertTrue(result.contains("[truncated"));
    assertTrue(
        result.length() <= MAX_ERROR_BODY_BYTES + 100,
        "result must be capped at MAX_ERROR_BODY_BYTES + a short truncation marker");
  }

  @Test
  void readBoundedErrorBodyReturnsExactBytesWhenUnderLimit() throws Exception {
    var msg = "compact error payload";
    var result =
        HttpClientFactory.readBoundedErrorBody(
            new java.io.ByteArrayInputStream(
                msg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(msg, result);
  }

  // --- Multi-thinking-block round-trip (audit H2) ----------------------------------------------

  @Test
  void decodeThinkingBlocksFallsBackToLegacySingleBlockKeys() {
    var msg =
        Message.newBuilder()
            .withRole(ai.singlr.core.model.Role.ASSISTANT)
            .withContent("response")
            .withMetadata(
                Map.of(
                    AnthropicModel.THINKING_KEY, "I am thinking",
                    AnthropicModel.THINKING_SIGNATURE_KEY, "sig-1"))
            .build();

    var blocks = AnthropicModel.decodeThinkingBlocks(msg);

    assertEquals(1, blocks.size());
    assertEquals("I am thinking", blocks.getFirst().text());
    assertEquals("sig-1", blocks.getFirst().signature());
  }

  @Test
  void decodeThinkingBlocksReadsMultiBlockJson() {
    var json =
        "[{\"text\":\"first thought\",\"signature\":\"sig-1\"},"
            + "{\"text\":\"second thought\",\"signature\":\"sig-2\"}]";
    var msg =
        Message.newBuilder()
            .withRole(ai.singlr.core.model.Role.ASSISTANT)
            .withContent("response")
            .withMetadata(Map.of(AnthropicModel.THINKING_BLOCKS_KEY, json))
            .build();

    var blocks = AnthropicModel.decodeThinkingBlocks(msg);

    assertEquals(2, blocks.size());
    assertEquals("first thought", blocks.get(0).text());
    assertEquals("sig-1", blocks.get(0).signature());
    assertEquals("second thought", blocks.get(1).text());
    assertEquals("sig-2", blocks.get(1).signature());
  }

  @Test
  void decodeThinkingBlocksPrefersJsonOverLegacyKeys() {
    var json = "[{\"text\":\"new format\",\"signature\":\"sig-new\"}]";
    var msg =
        Message.newBuilder()
            .withRole(ai.singlr.core.model.Role.ASSISTANT)
            .withContent("r")
            .withMetadata(
                Map.of(
                    AnthropicModel.THINKING_BLOCKS_KEY, json,
                    AnthropicModel.THINKING_KEY, "old text",
                    AnthropicModel.THINKING_SIGNATURE_KEY, "old-sig"))
            .build();

    var blocks = AnthropicModel.decodeThinkingBlocks(msg);

    assertEquals(1, blocks.size());
    assertEquals("new format", blocks.getFirst().text());
    assertEquals("sig-new", blocks.getFirst().signature());
  }

  @Test
  void decodeThinkingBlocksMalformedJsonFallsBackToLegacy() {
    var msg =
        Message.newBuilder()
            .withRole(ai.singlr.core.model.Role.ASSISTANT)
            .withContent("r")
            .withMetadata(
                Map.of(
                    AnthropicModel.THINKING_BLOCKS_KEY, "not-json",
                    AnthropicModel.THINKING_KEY, "legacy text",
                    AnthropicModel.THINKING_SIGNATURE_KEY, "legacy-sig"))
            .build();

    var blocks = AnthropicModel.decodeThinkingBlocks(msg);

    assertEquals(1, blocks.size());
    assertEquals("legacy text", blocks.getFirst().text());
    assertEquals("legacy-sig", blocks.getFirst().signature());
  }

  @Test
  void decodeThinkingBlocksReturnsEmptyWhenNoMetadata() {
    var msg =
        Message.newBuilder().withRole(ai.singlr.core.model.Role.ASSISTANT).withContent("r").build();
    assertTrue(AnthropicModel.decodeThinkingBlocks(msg).isEmpty());
  }

  @Test
  void convertAssistantMessageEmitsMultipleThinkingBlocksFromJsonMetadata() {
    var json =
        "[{\"text\":\"first thought\",\"signature\":\"sig-1\"},"
            + "{\"text\":\"second thought\",\"signature\":\"sig-2\"}]";
    var msg =
        Message.newBuilder()
            .withRole(ai.singlr.core.model.Role.ASSISTANT)
            .withContent("final answer")
            .withMetadata(Map.of(AnthropicModel.THINKING_BLOCKS_KEY, json))
            .build();

    var entry = AnthropicModel.convertAssistantMessage(msg);

    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    var thinkingCount = blocks.stream().filter(ContentBlock::hasTypeThinking).count();
    assertEquals(
        2, thinkingCount, "two thinking blocks must round-trip into separate ContentBlocks");
    // Per-block signatures must be preserved (the bug we fixed was concatenation).
    assertEquals("sig-1", blocks.get(0).signature());
    assertEquals("sig-2", blocks.get(1).signature());
  }

  @Test
  void buildHttpRequestUsesDefaultsWhenBaseUrlAndHeadersUnset() {
    var config = ModelConfig.newBuilder().withApiKey("sk-ant-test").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals(java.net.URI.create("https://api.anthropic.com/v1/messages"), httpRequest.uri());
    assertEquals("sk-ant-test", httpRequest.headers().firstValue("x-api-key").orElseThrow());
  }

  @Test
  void buildHttpRequestUsesConfiguredBaseUrl() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("sk-ant-test")
            .withBaseUrl("https://bedrock-anthropic.example/v1/messages")
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals(
        java.net.URI.create("https://bedrock-anthropic.example/v1/messages"), httpRequest.uri());
  }

  @Test
  void buildHttpRequestUserHeaderReplacesBuiltinByCaseInsensitiveName() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("default-key")
            .withHeader("X-API-KEY", "override-key")
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals("override-key", httpRequest.headers().firstValue("x-api-key").orElseThrow());
    assertEquals(
        1,
        httpRequest.headers().allValues("x-api-key").size(),
        "name match must replace the default rather than append a second header line");
  }

  @Test
  void buildHttpRequestExtraHeaderIsAppended() {
    var config =
        ModelConfig.newBuilder().withApiKey("sk-ant-test").withHeader("x-trace", "t1").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals("t1", httpRequest.headers().firstValue("x-trace").orElseThrow());
    assertEquals("sk-ant-test", httpRequest.headers().firstValue("x-api-key").orElseThrow());
  }

  // ── prompt caching (hv2-bug2 Issue 1) ─────────────────────────────────────

  @Test
  void promptCachingDefaultsOn() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    assertTrue(
        model.promptCachingEnabled(),
        "Helios bills Anthropic via prompt caching by default — opt-out is explicit");
  }

  @Test
  void promptCachingDisabledViaCachePolicy() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model =
        new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config, CachePolicy.disabled());
    var request =
        model.buildRequest(
            List.of(Message.system("Be helpful"), Message.user("Hi")), List.of(), null);

    // System emits the legacy plain-string shape — no cache_control, no array wrapping.
    assertEquals("Be helpful", request.system());
    // Last message stays as a String — caching annotation requires the array shape.
    assertEquals("Hi", request.messages().getFirst().content());
    assertFalse(model.promptCachingEnabled());
    assertInstanceOf(CachePolicy.Disabled.class, model.cachePolicy());
  }

  @Test
  void cachePolicyLongLivedAnnotatesWithOneHourTtl() {
    // 1h TTL is opt-in; cache write at 2x base, read still at 0.10x. Verify the breakpoint
    // payload reaches the system block intact so Anthropic bills the correct rate.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model =
        new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config, CachePolicy.longLived());
    var request =
        model.buildRequest(
            List.of(Message.system("Be helpful"), Message.user("Hi")), List.of(), null);

    @SuppressWarnings("unchecked")
    var systemBlocks = (List<ai.singlr.anthropic.api.SystemContent>) request.system();
    var cc = systemBlocks.getFirst().cacheControl();
    assertNotNull(cc);
    assertEquals(ai.singlr.anthropic.api.CacheControl.TYPE_EPHEMERAL, cc.type());
    assertEquals(
        ai.singlr.anthropic.api.CacheControl.TTL_1_HOUR,
        cc.ttl(),
        "long-lived CachePolicy must propagate ttl='1h' to every cache breakpoint");
    assertInstanceOf(CachePolicy.LongLived.class, model.cachePolicy());
  }

  @Test
  void cachePolicyShortLivedHasNoExplicitTtl() {
    // 5m is Anthropic's implicit default; sending ttl='5m' would still work but adds wire bloat.
    // Verify the short-lived policy emits a breakpoint with no ttl field set.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model =
        new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config, CachePolicy.shortLived());
    var request =
        model.buildRequest(
            List.of(Message.system("Be helpful"), Message.user("Hi")), List.of(), null);

    @SuppressWarnings("unchecked")
    var systemBlocks = (List<ai.singlr.anthropic.api.SystemContent>) request.system();
    var cc = systemBlocks.getFirst().cacheControl();
    assertNotNull(cc);
    assertNull(cc.ttl(), "short-lived policy must omit ttl so the wire stays minimal");
  }

  @Test
  void constructorRejectsNullCachePolicy() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config, (CachePolicy) null));
    assertEquals("cachePolicy is required", ex.getMessage());
  }

  @Test
  void promptCachingAnnotatesSystemPromptWithCacheControl() {
    // hv2-bug2 Issue 1 regression: without cache_control on the system prefix the Anthropic
    // server bills every input token at the base rate, producing the Light Grid matchmaking
    // baseline's flat $235.54 across 24 viewers. Annotated requests get the cache discount.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request =
        model.buildRequest(
            List.of(Message.system("You are a careful assistant."), Message.user("Hi")),
            List.of(),
            null);

    @SuppressWarnings("unchecked")
    var systemBlocks = (List<ai.singlr.anthropic.api.SystemContent>) request.system();
    assertEquals(1, systemBlocks.size());
    var block = systemBlocks.getFirst();
    assertEquals("text", block.type());
    assertEquals("You are a careful assistant.", block.text());
    assertNotNull(
        block.cacheControl(),
        "system prefix must carry a cache_control breakpoint for the default agent-loop pattern");
    assertEquals(ai.singlr.anthropic.api.CacheControl.TYPE_EPHEMERAL, block.cacheControl().type());
  }

  @Test
  void promptCachingOmitsSystemBlockWhenSystemPromptIsBlank() {
    // No system prompt set; caching ON. The model must not synthesize an empty SystemContent
    // block — Anthropic rejects empty system arrays and an empty text block wastes a breakpoint.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);
    assertNull(request.system(), "blank system must serialize as omitted, not as an empty array");
  }

  @Test
  void promptCachingAnnotatesLastToolWithCacheControl() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var tool1 =
        Tool.newBuilder()
            .withName("search")
            .withDescription("search the web")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("q")
                    .withType(ParameterType.STRING)
                    .withDescription("query")
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var tool2 =
        Tool.newBuilder()
            .withName("calculator")
            .withDescription("compute arithmetic")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("expr")
                    .withType(ParameterType.STRING)
                    .withDescription("the expression")
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool1, tool2), null);

    assertEquals(2, request.tools().size());
    assertNull(
        request.tools().get(0).cacheControl(),
        "non-tail tool blocks must NOT carry cache_control — wastes a breakpoint");
    assertNotNull(
        request.tools().get(1).cacheControl(),
        "the last tool anchors the cache breakpoint covering the entire tools array");
    assertEquals(
        ai.singlr.anthropic.api.CacheControl.TYPE_EPHEMERAL,
        request.tools().get(1).cacheControl().type());
  }

  @Test
  void promptCachingSingleToolGetsCacheControl() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var tool =
        Tool.newBuilder()
            .withName("solo")
            .withDescription("a lone tool")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);
    assertNotNull(request.tools().getFirst().cacheControl());
  }

  @Test
  void promptCachingOmitsToolBreakpointWhenToolsEmpty() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);
    assertNull(request.tools(), "empty tools list serializes as omitted, no breakpoint");
  }

  @Test
  void promptCachingPromotesLastMessageStringToBlockWithCacheControl() {
    // String content cannot carry cache_control on the wire — must be promoted to a single-text
    // block. This test guards the promotion path: a single string-content user message becomes a
    // single-block list with the block annotated.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = model.buildRequest(List.of(Message.user("Latest turn")), List.of(), null);

    var last = request.messages().getLast();
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) last.content();
    assertEquals(1, blocks.size());
    assertTrue(blocks.getFirst().hasTypeText());
    assertEquals("Latest turn", blocks.getFirst().text());
    assertNotNull(
        blocks.getFirst().cacheControl(),
        "the last message must anchor a cache breakpoint so the next turn reuses the prefix");
  }

  @Test
  void promptCachingAnnotatesOnlyTailBlockOfMultiBlockMessage() {
    // A multi-block message (e.g. text + image) must annotate the LAST block. Anthropic accepts a
    // single cache_control per request slot; annotating multiple blocks of the same message would
    // burn breakpoints with no incremental cacheability.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    var msg = Message.user("Look at this", List.of(InlineFile.of(pngBytes, "image/png")));
    var request = model.buildRequest(List.of(msg), List.of(), null);

    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) request.messages().getFirst().content();
    assertEquals(2, blocks.size());
    assertNull(blocks.get(0).cacheControl(), "non-tail block must not carry cache_control");
    assertNotNull(blocks.get(1).cacheControl(), "tail block must carry cache_control");
  }

  @Test
  void promptCachingAnnotatesLastTwoMessagesForRollingLookback() {
    // Multiple user/assistant turns: BOTH the last and second-to-last messages get cache_control
    // breakpoints. The pair gives the next turn's lookback a stable rolling write to find within
    // Anthropic's 20-block lookback window, which would otherwise blind out the system+tools
    // cache after roughly five tool-heavy agent turns.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request =
        model.buildRequest(
            List.of(Message.user("hello"), Message.assistant("hi"), Message.user("how are you")),
            List.of(),
            null);

    assertEquals(3, request.messages().size());
    // First user (anchor of conversation) — plain string, no annotation.
    assertEquals("hello", request.messages().get(0).content());
    // Penultimate (assistant) — promoted to block list with cache_control on the tail block.
    @SuppressWarnings("unchecked")
    var penultimateBlocks = (List<ContentBlock>) request.messages().get(1).content();
    assertEquals(1, penultimateBlocks.size());
    assertNotNull(
        penultimateBlocks.getFirst().cacheControl(),
        "rolling lookback requires the penultimate message to carry a cache breakpoint");
    // Last user — promoted to block list with cache_control on the tail block.
    @SuppressWarnings("unchecked")
    var lastBlocks = (List<ContentBlock>) request.messages().get(2).content();
    assertEquals(1, lastBlocks.size());
    assertNotNull(lastBlocks.getFirst().cacheControl());
  }

  @Test
  void promptCachingSingleMessageOmitsPenultimateBreakpoint() {
    // Single-message request: there's no second-to-last to annotate. Only the last gets a
    // breakpoint — no synthetic empty breakpoint, no off-by-one.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = model.buildRequest(List.of(Message.user("solo")), List.of(), null);

    assertEquals(1, request.messages().size());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) request.messages().getFirst().content();
    assertNotNull(blocks.getFirst().cacheControl());
  }

  @Test
  void promptCachingAnnotatesPenultimateOnPriorAssistantMessage() {
    // Verify the rolling breakpoint lands on an ASSISTANT message when that's the penultimate
    // entry — Anthropic accepts cache_control on assistant content blocks too.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request =
        model.buildRequest(
            List.of(Message.user("hi"), Message.assistant("hello!")), List.of(), null);

    @SuppressWarnings("unchecked")
    var penultimate = (List<ContentBlock>) request.messages().get(0).content();
    @SuppressWarnings("unchecked")
    var last = (List<ContentBlock>) request.messages().get(1).content();
    assertEquals("user", request.messages().get(0).role());
    assertEquals("assistant", request.messages().get(1).role());
    assertNotNull(penultimate.getFirst().cacheControl());
    assertNotNull(last.getFirst().cacheControl());
  }

  @Test
  void promptCachingSkipsMessageBreakpointForEmptyContent() {
    // Pathological: a user message with an empty string content (nothing to cache). The
    // annotator must skip rather than synthesize an empty block. The other breakpoints (system,
    // tools) still apply.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request =
        model.buildRequest(List.of(Message.system("S"), Message.user("")), List.of(), null);
    // Last message stays as an empty string — no promotion to an empty block.
    assertEquals("", request.messages().getFirst().content());
    // System still cached.
    @SuppressWarnings("unchecked")
    var systemBlocks = (List<ai.singlr.anthropic.api.SystemContent>) request.system();
    assertNotNull(systemBlocks.getFirst().cacheControl());
  }

  @Test
  void promptCachingProducesFullBreakpointBudgetForMultiTurnAgentLoop() {
    // The canonical multi-turn agent placement: system + tools + penultimate-message +
    // last-message = 4 breakpoints. The penultimate breakpoint maintains rolling cache lookup
    // within Anthropic's 20-block lookback window for long conversations.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var tool =
        Tool.newBuilder()
            .withName("only")
            .withDescription("only tool")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var request =
        model.buildRequest(
            List.of(
                Message.system("be helpful"),
                Message.user("hello"),
                Message.assistant("hi"),
                Message.user("how are you")),
            List.of(tool),
            null);

    var breakpointCount = 0;
    @SuppressWarnings("unchecked")
    var systemBlocks = (List<ai.singlr.anthropic.api.SystemContent>) request.system();
    if (systemBlocks.getLast().cacheControl() != null) breakpointCount++;
    if (request.tools().getLast().cacheControl() != null) breakpointCount++;
    @SuppressWarnings("unchecked")
    var penultimateBlocks =
        (List<ContentBlock>) request.messages().get(request.messages().size() - 2).content();
    if (penultimateBlocks.getLast().cacheControl() != null) breakpointCount++;
    @SuppressWarnings("unchecked")
    var lastBlocks = (List<ContentBlock>) request.messages().getLast().content();
    if (lastBlocks.getLast().cacheControl() != null) breakpointCount++;

    assertEquals(
        4,
        breakpointCount,
        "multi-turn agent loop uses Anthropic's full 4-breakpoint cache budget for rolling"
            + " lookback");
  }

  @Test
  void promptCachingSingleTurnUsesThreeBreakpoints() {
    // First-turn shape (no prior conversation): system + tools + last-message = 3 breakpoints.
    // No penultimate to annotate, so we stay one under the 4-breakpoint budget.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var tool =
        Tool.newBuilder()
            .withName("only")
            .withDescription("only tool")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var request =
        model.buildRequest(
            List.of(Message.system("be helpful"), Message.user("hi")), List.of(tool), null);

    var breakpointCount = 0;
    @SuppressWarnings("unchecked")
    var systemBlocks = (List<ai.singlr.anthropic.api.SystemContent>) request.system();
    if (systemBlocks.getLast().cacheControl() != null) breakpointCount++;
    if (request.tools().getLast().cacheControl() != null) breakpointCount++;
    @SuppressWarnings("unchecked")
    var lastBlocks = (List<ContentBlock>) request.messages().getLast().content();
    if (lastBlocks.getLast().cacheControl() != null) breakpointCount++;

    assertEquals(
        3, breakpointCount, "single-turn first-call uses 3 of 4 breakpoints — no penultimate");
  }

  @Test
  void messagesRequestSystemAsTextHandlesPlainStringShape() {
    var request = MessagesRequest.newBuilder().withSystem("hello system").build();
    assertEquals("hello system", request.systemAsText());
  }

  @Test
  void messagesRequestSystemAsTextHandlesBlockArrayShape() {
    var request =
        MessagesRequest.newBuilder()
            .withSystem(
                List.of(
                    ai.singlr.anthropic.api.SystemContent.text("first"),
                    ai.singlr.anthropic.api.SystemContent.text("second")))
            .build();
    assertEquals("first\n\nsecond", request.systemAsText());
  }

  @Test
  void messagesRequestSystemAsTextHandlesNull() {
    var request = MessagesRequest.newBuilder().build();
    assertNull(request.systemAsText());
  }

  // ── usage capture: cache tokens ───────────────────────────────────────────

  @Test
  void streamingIteratorCapturesCacheCreationAndReadTokensFromMessageStart() throws Exception {
    var json =
        "data: {\"type\":\"message_start\","
            + "\"message\":{\"usage\":{\"input_tokens\":100,\"cache_creation_input_tokens\":900,"
            + "\"cache_read_input_tokens\":500000,\"output_tokens\":0}}}\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"text\"}}\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n"
            + "data: {\"type\":\"content_block_stop\",\"index\":0}\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":50}}\n"
            + "data: {\"type\":\"message_stop\"}\n";
    var done = drainSseFixture(json);
    assertNotNull(done);
    var usage = done.response().usage();
    assertEquals(100, usage.inputTokens());
    assertEquals(50, usage.outputTokens());
    assertEquals(
        900,
        usage.cacheCreationInputTokens(),
        "cache_creation_input_tokens from message_start surfaces in Response.Usage");
    assertEquals(
        500000,
        usage.cacheReadInputTokens(),
        "cache_read_input_tokens from message_start surfaces in Response.Usage");
    assertEquals(
        100 + 50 + 900 + 500000,
        usage.totalTokens(),
        "totalTokens sums every billable token class");
  }

  @Test
  void streamingIteratorEmitsUsageWhenOnlyCacheTokensAreReported() throws Exception {
    // Degenerate but legal: pure cache read, no uncached input. Anthropic still bills the cache
    // read tokens; the Usage must surface so cost tracking accounts for it.
    var json =
        "data: {\"type\":\"message_start\","
            + "\"message\":{\"usage\":{\"input_tokens\":0,\"cache_read_input_tokens\":1234,"
            + "\"output_tokens\":0}}}\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":0}}\n"
            + "data: {\"type\":\"message_stop\"}\n";
    var done = drainSseFixture(json);
    assertNotNull(done);
    assertNotNull(
        done.response().usage(),
        "any non-zero token class must surface a Usage record so cost tracking is not lost");
    assertEquals(1234, done.response().usage().cacheReadInputTokens());
  }

  /**
   * Drive a {@link AnthropicModel.StreamingIterator} against a canned SSE body and return its
   * terminal {@code Done} event. Local to this test file so we don't bleed implementation-detail
   * helpers across test classes.
   */
  private static ai.singlr.core.model.StreamEvent.Done drainSseFixture(String sseBody)
      throws Exception {
    var inputStream =
        new java.io.ByteArrayInputStream(sseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    java.net.http.HttpResponse<java.io.InputStream> response =
        new java.net.http.HttpResponse<>() {
          @Override
          public int statusCode() {
            return 200;
          }

          @Override
          public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
          }

          @Override
          public java.io.InputStream body() {
            return inputStream;
          }

          @Override
          public java.util.Optional<java.net.http.HttpResponse<java.io.InputStream>>
              previousResponse() {
            return java.util.Optional.empty();
          }

          @Override
          public java.net.http.HttpRequest request() {
            return null;
          }

          @Override
          public java.net.URI uri() {
            return java.net.URI.create("https://test");
          }

          @Override
          public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_2;
          }

          @Override
          public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
          }
        };
    try (var iterator =
        new AnthropicModel.StreamingIterator(
            response,
            tools.jackson.databind.json.JsonMapper.builder().build(),
            java.time.Duration.ofSeconds(5))) {
      ai.singlr.core.model.StreamEvent.Done done = null;
      while (iterator.hasNext()) {
        var next = iterator.next();
        if (next instanceof ai.singlr.core.model.StreamEvent.Done d) {
          done = d;
        }
      }
      return done;
    }
  }
}
