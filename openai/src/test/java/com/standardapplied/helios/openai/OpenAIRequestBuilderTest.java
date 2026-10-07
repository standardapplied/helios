/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static com.standardapplied.helios.openai.OpenAIFixture.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.openai.api.ContentPart;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAIRequestBuilderTest {

  @Test
  void buildRequestWithTools() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var messages = List.of(Message.user("Weather?"));
    var request = requests.build(messages, ConversationFixture.tools(), null);

    assertNotNull(request.tools());
    assertEquals(1, request.tools().size());
    assertEquals("function", request.tools().getFirst().type());
    assertEquals("weather", request.tools().getFirst().name());
    assertEquals("Current weather for a city", request.tools().getFirst().description());
    assertNotNull(request.tools().getFirst().parameters());
  }

  @Test
  void buildRequestExtractsSystemMessage() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var messages = List.of(Message.system("You are helpful"), Message.user("Hello"));

    var request = requests.build(messages, List.of(), null);

    assertEquals("You are helpful", request.instructions());
    assertEquals(1, request.input().size());
    assertEquals("user", request.input().getFirst().role());
  }

  @Test
  void buildRequestUserMessage() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var messages = List.of(Message.user("Hello"));
    var request = requests.build(messages, List.of(), null);

    assertEquals(1, request.input().size());
    assertEquals("message", request.input().getFirst().type());
    assertEquals("user", request.input().getFirst().role());
    assertEquals("Hello", request.input().getFirst().content());
  }

  @Test
  void userMessageWithImageAttachmentEmitsInputImagePart() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);
    var pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2};
    var msg = Message.user("see this", List.of(InlineFile.of(pngBytes, "image/png")));

    var request = requests.build(List.of(msg), List.of(), null);

    var item = request.input().getFirst();
    assertEquals("message", item.type());
    assertEquals("user", item.role());
    @SuppressWarnings("unchecked")
    var parts = (List<ContentPart>) item.content();
    assertEquals(2, parts.size());
    assertEquals("input_image", parts.get(0).type());
    var expected =
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(pngBytes);
    assertEquals(expected, parts.get(0).imageUrl());
    assertEquals("input_text", parts.get(1).type());
    assertEquals("see this", parts.get(1).text());
  }

  @Test
  void userMessageWithPdfAttachmentEmitsInputFilePart() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);
    var pdfBytes = "%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var msg = Message.user("summarize", List.of(InlineFile.of(pdfBytes, "application/pdf")));

    var request = requests.build(List.of(msg), List.of(), null);

    @SuppressWarnings("unchecked")
    var parts = (List<ContentPart>) request.input().getFirst().content();
    assertEquals("input_file", parts.get(0).type());
    assertTrue(parts.get(0).fileData().startsWith("data:application/pdf;base64,"));
  }

  @Test
  void buildRequestToolMessages() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var toolCalls = List.of(ToolCall.newBuilder().withId("call_1").withName("tool1").build());
    var messages =
        List.of(
            Message.user("Do something"),
            Message.assistant("Sure", toolCalls),
            Message.tool("call_1", "tool1", "result1"));

    var request = requests.build(messages, List.of(), null);

    assertEquals(4, request.input().size());
    assertEquals("message", request.input().get(0).type());
    assertEquals("message", request.input().get(1).type());
    assertEquals("function_call", request.input().get(2).type());
    assertEquals("function_call_output", request.input().get(3).type());
    assertEquals("call_1", request.input().get(3).callId());
    assertEquals("result1", request.input().get(3).output());
  }

  @Test
  void buildRequestDefaultMaxTokensFallsBackToModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(OpenAIModelId.GPT_4O.maxOutputTokens(), request.maxOutputTokens());
  }

  @Test
  void buildRequestPerModelDefaultDiffersByModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var gpt4oRequests = requests(OpenAIModelId.GPT_4O, config);
    var o3Requests = requests(OpenAIModelId.O3, config);

    var gpt4oReq = gpt4oRequests.build(List.of(Message.user("Hi")), List.of(), null);
    var o3Req = o3Requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(16_384, gpt4oReq.maxOutputTokens());
    assertEquals(100_000, o3Req.maxOutputTokens());
  }

  @Test
  void buildRequestCustomMaxTokens() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withMaxOutputTokens(8192).build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(8192, request.maxOutputTokens());
  }

  @Test
  void buildRequestWithOutputSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());
    var request = requests.build(List.of(Message.user("Extract")), List.of(), schema);

    assertNotNull(request.text());
    assertNotNull(request.text().format());
    assertEquals("json_schema", request.text().format().type());
    assertEquals("output", request.text().format().name());
    assertTrue(request.text().format().strict());
  }

  @Test
  void buildRequestDisablesStrictModeWhenSchemaHasOpenMap() {
    // record Out(Map<String, List<String>> targetToSources) — strict mode rejects with HTTP 400
    // ("'required' is required to be supplied"); the schema must ship with strict=false.
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var openMapSchema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of(
                "targetToSources",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    Map.of("type", "array", "items", Map.of("type", "string")))),
            "required",
            List.of("targetToSources"));

    var request = requests.build(List.of(Message.user("Map it")), List.of(), openMapSchema);

    assertNotNull(request.text());
    var format = request.text().format();
    assertEquals("json_schema", format.type());
    assertFalse(
        format.strict(),
        "Schemas containing Map<String, X> must ship with strict=false to avoid the OpenAI"
            + " strict-mode validator rejecting open-keyed objects");
    // additionalProperties on the inner Map must remain the value schema, not get rewritten to
    // false (which would close the map and prevent the model from emitting any key/value pairs).
    @SuppressWarnings("unchecked")
    var props = (Map<String, Object>) format.schema().get("properties");
    @SuppressWarnings("unchecked")
    var inner = (Map<String, Object>) props.get("targetToSources");
    assertTrue(
        inner.get("additionalProperties") instanceof Map,
        "Open Map's additionalProperties must remain a value schema in non-strict mode");
  }

  @Test
  void buildRequestToolChoiceAuto() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.auto()).build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);

    assertEquals("auto", request.toolChoice());
  }

  @Test
  void buildRequestToolChoiceAny() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.any()).build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);

    assertEquals("required", request.toolChoice());
  }

  @Test
  void buildRequestToolChoiceNone() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.none()).build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);

    assertEquals("none", request.toolChoice());
  }

  @Test
  @SuppressWarnings("unchecked")
  void buildRequestToolChoiceRequired() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withToolChoice(ToolChoice.required("my_tool"))
            .build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var tool =
        Tool.newBuilder()
            .withName("my_tool")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);

    var choice = (Map<String, String>) request.toolChoice();
    assertEquals("function", choice.get("type"));
    assertEquals("my_tool", choice.get("name"));
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
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(0.7, request.temperature());
    assertEquals(0.9, request.topP());
    assertEquals(List.of("END"), request.stop());
  }

  @Test
  void buildRequestStreamsAlways() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertTrue(request.stream());
  }

  @Test
  void buildRequestNoToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestNullToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var request = requests.build(List.of(Message.user("Hi")), null, null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_5_4, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("gpt-5.4", request.model());
  }

  @Test
  void promptCacheKeyRidesOnRequest() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withPromptCacheKey("tenant:acme:support-v1")
            .build();
    var requests = requests(OpenAIModelId.GPT_5_6, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("tenant:acme:support-v1", request.promptCacheKey());
  }

  @Test
  void promptCacheKeyAbsentByDefault() {
    var request = requests().build(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.promptCacheKey());
  }

  @Test
  void buildRequestMultipleSystemMessages() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var messages =
        List.of(Message.system("Be helpful"), Message.system("Be concise"), Message.user("Hello"));

    var request = requests.build(messages, List.of(), null);

    assertTrue(request.instructions().contains("Be helpful"));
    assertTrue(request.instructions().contains("Be concise"));
    assertEquals(1, request.input().size());
  }

  @Test
  void buildRequestNoToolChoiceReturnsNull() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.toolChoice());
  }

  @Test
  void buildRequestMultipleToolCallsInAssistant() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var tc1 = ToolCall.newBuilder().withId("call_1").withName("tool1").build();
    var tc2 = ToolCall.newBuilder().withId("call_2").withName("tool2").build();
    var messages =
        List.of(
            Message.user("Do stuff"),
            Message.assistant("Sure", List.of(tc1, tc2)),
            Message.tool("call_1", "tool1", "r1"),
            Message.tool("call_2", "tool2", "r2"));

    var request = requests.build(messages, List.of(), null);

    long functionCalls =
        request.input().stream().filter(item -> "function_call".equals(item.type())).count();
    long functionOutputs =
        request.input().stream().filter(item -> "function_call_output".equals(item.type())).count();
    assertEquals(2, functionCalls);
    assertEquals(2, functionOutputs);
  }

  @Test
  void buildRequestNoReasoningPreservesTemperature() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withTemperature(0.5).build();
    var requests = requests(OpenAIModelId.GPT_4O, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(0.5, request.temperature());
    assertNull(request.reasoning());
  }

  @Test
  void buildRequestUserMessageNullContent() {
    var message = new Message(Role.USER, null, List.of(), null, null, Map.of(), List.of());

    var request = requests().build(List.of(message), List.of(), null);

    assertEquals(1, request.input().size());
    assertEquals("", request.input().getFirst().content());
  }

  @Test
  void buildRequestWithOutputSchemaAddsAdditionalProperties() {
    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());
    var request = requests().build(List.of(Message.user("Extract")), List.of(), schema);

    assertNotNull(request.text());
    var format = request.text().format();
    @SuppressWarnings("unchecked")
    var schemaMap = (Map<String, Object>) format.schema();
    assertEquals(false, schemaMap.get("additionalProperties"));
  }
}
