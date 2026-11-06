/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.model;
import static com.standardapplied.helios.anthropic.AnthropicFixture.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.anthropic.api.SystemContent;
import com.standardapplied.helios.anthropic.api.ThinkingConfig;
import com.standardapplied.helios.core.model.FileReference;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicRequestBuilderTest {

  @Test
  void userMessageWithImageAttachmentEmitsImageBlock() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);
    var pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    var userMessage = Message.user("look at this", List.of(InlineFile.of(pngBytes, "image/png")));

    var request = requests.build(List.of(userMessage), List.of(), null).body();

    var entry = request.messages().getFirst();
    assertEquals("user", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    assertEquals(2, blocks.size());
    var imageBlock = blocks.get(0);
    assertEquals("image", imageBlock.type());
    assertEquals("image/png", imageBlock.source().mediaType());
    assertEquals("base64", imageBlock.source().type());
    assertEquals(
        java.util.Base64.getEncoder().encodeToString(pngBytes), imageBlock.source().data());
    var textBlock = blocks.get(1);
    assertEquals("text", textBlock.type());
    assertEquals("look at this", textBlock.text());
  }

  @Test
  void userMessageWithPdfAttachmentEmitsDocumentBlock() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);
    var pdfBytes = "%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var userMessage =
        Message.user("summarize this", List.of(InlineFile.of(pdfBytes, "application/pdf")));

    var request = requests.build(List.of(userMessage), List.of(), null).body();

    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) request.messages().getFirst().content();
    assertEquals("document", blocks.get(0).type());
    assertEquals("application/pdf", blocks.get(0).source().mediaType());
  }

  @Test
  void userMessageWithTextAttachmentInlinesAsTextBlock() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);
    var csvBytes = "a,b\n1,2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var userMessage = Message.user("", List.of(InlineFile.of(csvBytes, "text/csv")));

    var request = requests.build(List.of(userMessage), List.of(), null).body();

    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) request.messages().getFirst().content();
    assertEquals(1, blocks.size());
    assertEquals("text", blocks.get(0).type());
    assertTrue(blocks.get(0).text().contains("[attachment text/csv]"), blocks.get(0).text());
    assertTrue(blocks.get(0).text().contains("a,b"), blocks.get(0).text());
  }

  @Test
  void rejectsProviderFileReferencesInsteadOfSilentlyDroppingThem() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);
    var message =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent("Summarize")
            .withFileReferences(
                List.of(FileReference.of("https://example.com/video.mp4", "video/mp4")))
            .build();

    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> requests.build(List.of(message), List.of(), null).body());

    assertTrue(error.getMessage().contains("file references"));
  }

  @Test
  void buildRequestExtractsSystemMessage() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);

    var messages = List.of(Message.system("You are helpful"), Message.user("Hello"));

    var request = requests.build(messages, List.of(), null).body();

    var system = (List<?>) request.system();
    assertEquals(1, system.size());
    assertEquals("You are helpful", ((SystemContent) system.getFirst()).text());
    assertEquals(1, request.messages().size());
    assertEquals("user", request.messages().getFirst().role());
  }

  @Test
  void buildRequestCoalescesToolMessages() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);

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

    var request = requests.build(messages, List.of(), null).body();

    assertEquals(3, request.messages().size());
    assertEquals("user", request.messages().get(0).role());
    assertEquals("assistant", request.messages().get(1).role());
    assertEquals("user", request.messages().get(2).role());

    @SuppressWarnings("unchecked")
    var toolResults = (List<ContentBlock>) request.messages().get(2).content();
    assertEquals(2, toolResults.size());
    assertEquals("tool_result", toolResults.get(0).type());
    assertEquals("call_1", toolResults.get(0).toolUseId());
    assertEquals("result1", toolResults.get(0).content());
    assertEquals("tool_result", toolResults.get(1).type());
    assertEquals("call_2", toolResults.get(1).toolUseId());
    assertEquals("result2", toolResults.get(1).content());
  }

  @Test
  void buildRequestDefaultMaxTokensFallsBackToModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null).body();

    assertEquals(AnthropicModelId.CLAUDE_SONNET_5_5.maxOutputTokens(), request.maxTokens());
  }

  @Test
  void unknownClaudeModelDefaultsMaxOutputTokens() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = model("claude-some-future-model", config);

    var request =
        requests("claude-some-future-model", config)
            .build(List.of(Message.user("Hi")), List.of(), null)
            .body();

    assertEquals(AnthropicRequestBuilder.DEFAULT_MAX_OUTPUT_TOKENS, model.maxOutputTokens());
    assertEquals(AnthropicRequestBuilder.DEFAULT_MAX_OUTPUT_TOKENS, request.maxTokens());
  }

  @Test
  void buildRequestCustomMaxTokens() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withMaxOutputTokens(8192).build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null).body();

    assertEquals(8192, request.maxTokens());
  }

  @Test
  void buildRequestWithOutputSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);

    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());
    var request = requests.build(List.of(Message.user("Extract")), List.of(), schema).body();

    assertNotNull(request.system());
    var system = (List<?>) request.system();
    assertEquals(1, system.size());
    var systemText = ((SystemContent) system.getFirst()).text();
    assertTrue(systemText.contains("JSON"));
    assertTrue(systemText.contains("schema"));
  }

  @Test
  void buildRequestSystemAndOutputSchemaAppended() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);

    var schema = Map.<String, Object>of("type", "object");
    var messages = List.of(Message.system("Be helpful"), Message.user("Extract"));
    var request = requests.build(messages, List.of(), schema).body();

    var system = (List<?>) request.system();
    assertEquals(1, system.size());
    var systemText = ((SystemContent) system.getFirst()).text();
    assertTrue(systemText.startsWith("Be helpful"));
    assertTrue(systemText.contains("JSON"));
  }

  @Test
  void buildRequestWithGenerationParams() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withTemperature(0.7)
            .withStopSequences(List.of("END"))
            .build();
    var requests = requests("claude-opus-6", config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null).body();

    assertEquals(0.7, request.temperature());
    assertEquals(List.of("END"), request.stopSequences());
  }

  @Test
  void buildRequestStreamsAlways() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null).body();

    assertTrue(request.stream());
  }

  @Test
  void buildRequestModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null).body();

    assertEquals("claude-opus-5-5", request.model());
  }

  @Test
  void datedSnapshotResolvesToFamilyShape() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withReasoning(new Reasoning.Off()).build();
    var requests = requests("claude-sonnet-5-5-20261101", config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null).body();

    assertEquals(
        ThinkingConfig.betweenTools(),
        request.thinking(),
        "dated snapshots resolve to the family shape, which stops thinking with between_tools");
  }
}
