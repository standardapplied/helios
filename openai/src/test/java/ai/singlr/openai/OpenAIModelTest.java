/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.InlineFile;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Role;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.model.ToolChoice;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredOutputParseException;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.openai.api.ContentPart;
import ai.singlr.openai.api.InputItem;
import ai.singlr.openai.api.ResponsesRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class OpenAIModelTest {

  private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

  private static OpenAIModel createModel() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    return new OpenAIModel(OpenAIModelId.GPT_4O, config);
  }

  @Test
  void constructorRequiresModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertThrows(
        IllegalArgumentException.class, () -> new OpenAIModel((OpenAIModelId) null, config));
  }

  @Test
  void constructorRequiresConfig() {
    assertThrows(IllegalArgumentException.class, () -> new OpenAIModel(OpenAIModelId.GPT_4O, null));
  }

  @Test
  void constructorRequiresApiKey() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(
        IllegalArgumentException.class, () -> new OpenAIModel(OpenAIModelId.GPT_4O, config));
  }

  @Test
  void constructorRequiresNonBlankApiKey() {
    var config = ModelConfig.newBuilder().withApiKey("   ").build();
    assertThrows(
        IllegalArgumentException.class, () -> new OpenAIModel(OpenAIModelId.GPT_4O, config));
  }

  @Test
  void idReturnsModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    assertEquals("gpt-4o", model.id());
  }

  @Test
  void providerReturnsOpenai() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    assertEquals("openai", model.provider());
  }

  @Test
  void contextWindowReturnsModelValue() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    assertEquals(128_000, model.contextWindow());
  }

  @Test
  void contextWindowConfigOverrideWins() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withContextWindow(64_000).build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    assertEquals(64_000, model.contextWindow());
  }

  @Test
  void buildRequestExtractsSystemMessage() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var messages = List.of(Message.system("You are helpful"), Message.user("Hello"));

    var request = model.buildRequest(messages, List.of(), null);

    assertEquals("You are helpful", request.instructions());
    assertEquals(1, request.input().size());
    assertEquals("user", request.input().getFirst().role());
  }

  @Test
  void buildRequestUserMessage() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var messages = List.of(Message.user("Hello"));
    var request = model.buildRequest(messages, List.of(), null);

    assertEquals(1, request.input().size());
    assertTrue(request.input().getFirst().hasTypeMessage());
    assertEquals("user", request.input().getFirst().role());
    assertEquals("Hello", request.input().getFirst().content());
  }

  @Test
  void userMessageWithImageAttachmentEmitsInputImagePart() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    var pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2};
    var msg = Message.user("see this", List.of(InlineFile.of(pngBytes, "image/png")));

    var request = model.buildRequest(List.of(msg), List.of(), null);

    var item = request.input().getFirst();
    assertTrue(item.hasTypeMessage());
    assertEquals("user", item.role());
    @SuppressWarnings("unchecked")
    var parts = (List<ContentPart>) item.content();
    assertEquals(2, parts.size());
    assertTrue(parts.get(0).hasTypeInputImage());
    var expected =
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(pngBytes);
    assertEquals(expected, parts.get(0).imageUrl());
    assertTrue(parts.get(1).hasTypeInputText());
    assertEquals("see this", parts.get(1).text());
  }

  @Test
  void userMessageWithPdfAttachmentEmitsInputFilePart() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    var pdfBytes = "%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var msg = Message.user("summarize", List.of(InlineFile.of(pdfBytes, "application/pdf")));

    var request = model.buildRequest(List.of(msg), List.of(), null);

    @SuppressWarnings("unchecked")
    var parts = (List<ContentPart>) request.input().getFirst().content();
    assertTrue(parts.get(0).hasTypeInputFile());
    assertTrue(parts.get(0).fileData().startsWith("data:application/pdf;base64,"));
  }

  @Test
  void buildRequestToolMessages() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var toolCalls = List.of(ToolCall.newBuilder().withId("call_1").withName("tool1").build());
    var messages =
        List.of(
            Message.user("Do something"),
            Message.assistant("Sure", toolCalls),
            Message.tool("call_1", "tool1", "result1"));

    var request = model.buildRequest(messages, List.of(), null);

    assertEquals(4, request.input().size());
    assertTrue(request.input().get(0).hasTypeMessage());
    assertTrue(request.input().get(1).hasTypeMessage());
    assertTrue(request.input().get(2).hasTypeFunctionCall());
    assertTrue(request.input().get(3).hasTypeFunctionCallOutput());
    assertEquals("call_1", request.input().get(3).callId());
    assertEquals("result1", request.input().get(3).output());
  }

  @Test
  void buildRequestWithTools() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

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
    assertEquals("function", request.tools().getFirst().type());
    assertEquals("get_weather", request.tools().getFirst().name());
    assertEquals("Get weather", request.tools().getFirst().description());
    assertNotNull(request.tools().getFirst().parameters());
  }

  @Test
  void buildRequestDefaultMaxTokensFallsBackToModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(OpenAIModelId.GPT_4O.maxOutputTokens(), request.maxOutputTokens());
  }

  @Test
  void buildRequestPerModelDefaultDiffersByModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var gpt4oModel = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    var o3Model = new OpenAIModel(OpenAIModelId.O3, config);

    var gpt4oReq = gpt4oModel.buildRequest(List.of(Message.user("Hi")), List.of(), null);
    var o3Req = o3Model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(16_384, gpt4oReq.maxOutputTokens());
    assertEquals(100_000, o3Req.maxOutputTokens());
  }

  @Test
  void modelExposesMaxOutputTokensFromModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_5_5, config);
    assertEquals(OpenAIModelId.GPT_5_5.maxOutputTokens(), model.maxOutputTokens());
  }

  @Test
  void buildRequestCustomMaxTokens() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withMaxOutputTokens(8192).build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(8192, request.maxOutputTokens());
  }

  @Test
  void buildRequestWithOutputSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());
    var request = model.buildRequest(List.of(Message.user("Extract")), List.of(), schema);

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
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

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

    var request = model.buildRequest(List.of(Message.user("Map it")), List.of(), openMapSchema);

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
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertEquals("auto", request.toolChoice());
  }

  @Test
  void buildRequestToolChoiceAny() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.any()).build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertEquals("required", request.toolChoice());
  }

  @Test
  void buildRequestToolChoiceNone() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.none()).build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

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
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var tool =
        Tool.newBuilder()
            .withName("my_tool")
            .withDescription("test")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

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
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(0.7, request.temperature());
    assertEquals(0.9, request.topP());
    assertEquals(List.of("END"), request.stop());
  }

  @Test
  void buildRequestStreamsAlways() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertTrue(request.stream());
  }

  @Test
  void buildRequestNoToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestNullToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), null, null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_5_4, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("gpt-5.4", request.model());
  }

  @Test
  void buildRequestWithReasoningMedium() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var model = new OpenAIModel(OpenAIModelId.O3, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("medium", request.reasoning().effort());
  }

  @Test
  void buildRequestWithReasoningLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.LOW)
            .build();
    var model = new OpenAIModel(OpenAIModelId.O3, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("low", request.reasoning().effort());
  }

  @Test
  void buildRequestWithReasoningMinimal() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MINIMAL)
            .build();
    var model = new OpenAIModel(OpenAIModelId.O3, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("low", request.reasoning().effort());
  }

  @Test
  void buildRequestWithReasoningHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var model = new OpenAIModel(OpenAIModelId.O3, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("high", request.reasoning().effort());
  }

  @Test
  void gpt55XhighMapsToXhighWireString() {
    // Per OpenAI's deployment-checklist + gpt-5.5 model page, gpt-5.5 reasoning.effort accepts
    // none/low/medium/high/xhigh. Helios's XHIGH must round-trip to the literal "xhigh", not
    // clamp to "high".
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var model = new OpenAIModel(OpenAIModelId.GPT_5_5, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(
        "xhigh",
        request.reasoning().effort(),
        "gpt-5.5 must receive the literal 'xhigh' wire string");
  }

  @Test
  void gpt54XhighMapsToXhighWireString() {
    // gpt-5.4 model page documents the same five-tier set as gpt-5.5.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var model = new OpenAIModel(OpenAIModelId.GPT_5_4, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("xhigh", request.reasoning().effort());
  }

  @Test
  void gpt55MaxClampsToXhighWireString() {
    // OpenAI has no native "max" tier — Helios's MAX maps to OpenAI's highest available, which
    // is xhigh on gpt-5.5. Distinct from "high" so callers get the strongest reasoning OpenAI
    // exposes.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var model = new OpenAIModel(OpenAIModelId.GPT_5_5, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(
        "xhigh",
        request.reasoning().effort(),
        "MAX must clamp to OpenAI's highest tier (xhigh on gpt-5.5), not stop at 'high'");
  }

  @Test
  void o3XhighClampsToHighWireString() {
    // o-series reasoning models (o3, o4-mini) are not documented to accept "xhigh" — only
    // low/medium/high are confirmed via OpenAI's docs. Helios clamps XHIGH/MAX to "high" here
    // until OpenAI publishes wider support. Conservative dispatch keeps requests valid.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var model = new OpenAIModel(OpenAIModelId.O3, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("high", request.reasoning().effort());
  }

  @Test
  void o4MiniMaxClampsToHighWireString() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var model = new OpenAIModel(OpenAIModelId.O4_MINI, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("high", request.reasoning().effort());
  }

  @Test
  void buildRequestReasoningNoneOmitsConfig() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var model = new OpenAIModel(OpenAIModelId.O3, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.reasoning());
  }

  @Test
  void convertAssistantMessageSimpleText() {
    var model = createModel();
    var message = Message.assistant("Hello");

    var items = model.convertAssistantMessage(message);

    assertEquals(1, items.size());
    assertTrue(items.getFirst().hasTypeMessage());
    assertEquals("assistant", items.getFirst().role());
  }

  @Test
  void convertAssistantMessageWithToolCalls() {
    var model = createModel();
    var tc =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("search")
            .withArguments(Map.of("q", "test"))
            .build();
    var message = Message.assistant("I'll search", List.of(tc));

    var items = model.convertAssistantMessage(message);

    assertEquals(2, items.size());
    assertTrue(items.get(0).hasTypeMessage());
    assertTrue(items.get(1).hasTypeFunctionCall());
    assertEquals("call_1", items.get(1).callId());
    assertEquals("search", items.get(1).name());
  }

  @Test
  void convertAssistantMessageToolCallsOnly() {
    var model = createModel();
    var tc =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("search")
            .withArguments(Map.of("q", "test"))
            .build();
    var message = Message.assistant(List.of(tc));

    var items = model.convertAssistantMessage(message);

    assertEquals(1, items.size());
    assertTrue(items.getFirst().hasTypeFunctionCall());
  }

  @Test
  void convertAssistantMessageNullContentBecomesEmpty() {
    var model = createModel();
    var message = new Message(Role.ASSISTANT, null, List.of(), null, null, Map.of(), List.of());

    var items = model.convertAssistantMessage(message);

    assertEquals(1, items.size());
    assertTrue(items.getFirst().hasTypeMessage());
  }

  @Test
  void convertAssistantMessageEmptyContentBecomesEmpty() {
    var model = createModel();
    var message = new Message(Role.ASSISTANT, "", List.of(), null, null, Map.of(), List.of());

    var items = model.convertAssistantMessage(message);

    assertEquals(1, items.size());
    assertTrue(items.getFirst().hasTypeMessage());
  }

  @Test
  void convertAssistantMessageWithNullArguments() {
    var model = createModel();
    var tc = ToolCall.newBuilder().withId("call_1").withName("fn").build();
    var message = Message.assistant(List.of(tc));

    var items = model.convertAssistantMessage(message);

    assertEquals(1, items.size());
    assertTrue(items.getFirst().hasTypeFunctionCall());
    assertEquals("{}", items.getFirst().arguments());
  }

  @Test
  void mapStatusCompleted() {
    assertEquals(FinishReason.STOP, OpenAIModel.mapStatus("completed"));
  }

  @Test
  void mapStatusIncomplete() {
    assertEquals(FinishReason.LENGTH, OpenAIModel.mapStatus("incomplete"));
  }

  @Test
  void mapStatusFailed() {
    assertEquals(FinishReason.ERROR, OpenAIModel.mapStatus("failed"));
  }

  @Test
  void mapStatusNull() {
    assertEquals(FinishReason.STOP, OpenAIModel.mapStatus(null));
  }

  @Test
  void mapStatusUnknown() {
    assertEquals(FinishReason.STOP, OpenAIModel.mapStatus("unknown"));
  }

  @Test
  void buildRequestMultipleSystemMessages() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var messages =
        List.of(Message.system("Be helpful"), Message.system("Be concise"), Message.user("Hello"));

    var request = model.buildRequest(messages, List.of(), null);

    assertTrue(request.instructions().contains("Be helpful"));
    assertTrue(request.instructions().contains("Be concise"));
    assertEquals(1, request.input().size());
  }

  @Test
  void buildRequestNoToolChoiceReturnsNull() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.toolChoice());
  }

  @Test
  void buildRequestMultipleToolCallsInAssistant() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var tc1 = ToolCall.newBuilder().withId("call_1").withName("tool1").build();
    var tc2 = ToolCall.newBuilder().withId("call_2").withName("tool2").build();
    var messages =
        List.of(
            Message.user("Do stuff"),
            Message.assistant("Sure", List.of(tc1, tc2)),
            Message.tool("call_1", "tool1", "r1"),
            Message.tool("call_2", "tool2", "r2"));

    var request = model.buildRequest(messages, List.of(), null);

    long functionCalls = request.input().stream().filter(InputItem::hasTypeFunctionCall).count();
    long functionOutputs =
        request.input().stream().filter(InputItem::hasTypeFunctionCallOutput).count();
    assertEquals(2, functionCalls);
    assertEquals(2, functionOutputs);
  }

  @Test
  void buildRequestReasoningNullsTemperature() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withTemperature(0.7)
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var model = new OpenAIModel(OpenAIModelId.O3, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertNull(request.temperature());
    assertNotNull(request.reasoning());
  }

  @Test
  void buildRequestNoReasoningPreservesTemperature() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withTemperature(0.5).build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(0.5, request.temperature());
    assertNull(request.reasoning());
  }

  @Test
  void buildRequestUserMessageNullContent() {
    var model = createModel();
    var message = new Message(Role.USER, null, List.of(), null, null, Map.of(), List.of());

    var request = model.buildRequest(List.of(message), List.of(), null);

    assertEquals(1, request.input().size());
    assertEquals("", request.input().getFirst().content());
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalseSimpleObject() {
    var schema =
        Map.<String, Object>of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string")),
            "required", List.of("name"));

    var result = OpenAIModel.addAdditionalPropertiesFalse(schema);

    assertEquals(false, result.get("additionalProperties"));
    var props = (Map<String, Object>) result.get("properties");
    var nameSchema = (Map<String, Object>) props.get("name");
    assertEquals("string", nameSchema.get("type"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalseNestedObject() {
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of(
                "address",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string")))));

    var result = OpenAIModel.addAdditionalPropertiesFalse(schema);

    assertEquals(false, result.get("additionalProperties"));
    var props = (Map<String, Object>) result.get("properties");
    var addressSchema = (Map<String, Object>) props.get("address");
    assertEquals(false, addressSchema.get("additionalProperties"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalseArray() {
    var schema =
        Map.<String, Object>of(
            "type",
            "array",
            "items",
            Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))));

    var result = OpenAIModel.addAdditionalPropertiesFalse(schema);

    var items = (Map<String, Object>) result.get("items");
    assertEquals(false, items.get("additionalProperties"));
  }

  @Test
  void addAdditionalPropertiesFalseLeafType() {
    var schema = Map.<String, Object>of("type", "string");

    var result = OpenAIModel.addAdditionalPropertiesFalse(schema);

    assertEquals("string", result.get("type"));
    assertNull(result.get("additionalProperties"));
  }

  @Test
  void hasOpenMapShapeReturnsFalseForFlatRecord() {
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of("name", Map.of("type", "string"), "count", Map.of("type", "integer")),
            "required",
            List.of("name", "count"));
    assertFalse(OpenAIModel.hasOpenMapShape(schema));
  }

  @Test
  void hasOpenMapShapeDetectsTopLevelOpenMap() {
    var schema =
        Map.<String, Object>of("type", "object", "additionalProperties", Map.of("type", "string"));
    assertTrue(OpenAIModel.hasOpenMapShape(schema));
  }

  @Test
  void hasOpenMapShapeDetectsOpenMapNestedInProperty() {
    // record Out(Map<String, List<String>> targetToSources)
    var schema =
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
    assertTrue(
        OpenAIModel.hasOpenMapShape(schema),
        "Map<String, List<String>> inside a record property must be detected so strict mode is"
            + " disabled — strict mode rejects open Maps with HTTP 400");
  }

  @Test
  void hasOpenMapShapeDetectsOpenMapNestedInArrayItems() {
    // List<Map<String, String>>
    var schema =
        Map.<String, Object>of(
            "type",
            "array",
            "items",
            Map.of("type", "object", "additionalProperties", Map.of("type", "string")));
    assertTrue(OpenAIModel.hasOpenMapShape(schema));
  }

  @Test
  void hasOpenMapShapeIgnoresAdditionalPropertiesFalse() {
    // After addAdditionalPropertiesFalse has been applied, additionalProperties=false should NOT
    // count as an open map.
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of("name", Map.of("type", "string")),
            "additionalProperties",
            false);
    assertFalse(OpenAIModel.hasOpenMapShape(schema));
  }

  @Test
  void hasOpenMapShapeReturnsFalseForNull() {
    assertFalse(OpenAIModel.hasOpenMapShape(null));
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalsePreservesMapValueSchema() {
    // Map<String, List<String>> produces: {type: object, additionalProperties: {type: array, ...}}
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "additionalProperties",
            Map.of("type", "array", "items", Map.of("type", "string")));

    var result = OpenAIModel.addAdditionalPropertiesFalse(schema);

    // additionalProperties should be recursed into, NOT overwritten with false
    var addlProps = (Map<String, Object>) result.get("additionalProperties");
    assertNotNull(addlProps, "Map value schema should be preserved");
    assertEquals("array", addlProps.get("type"));

    var items = (Map<String, Object>) addlProps.get("items");
    assertEquals("string", items.get("type"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalseRecursesNestedMapValueSchema() {
    // Map<String, Record> — additionalProperties is an object that needs its own false
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "additionalProperties",
            Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))));

    var result = OpenAIModel.addAdditionalPropertiesFalse(schema);

    var addlProps = (Map<String, Object>) result.get("additionalProperties");
    assertNotNull(addlProps);
    assertEquals("object", addlProps.get("type"));
    assertEquals(false, addlProps.get("additionalProperties"));
  }

  @Test
  void buildRequestWithOutputSchemaAddsAdditionalProperties() {
    var model = createModel();
    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());
    var request = model.buildRequest(List.of(Message.user("Extract")), List.of(), schema);

    assertNotNull(request.text());
    var format = request.text().format();
    @SuppressWarnings("unchecked")
    var schemaMap = (Map<String, Object>) format.schema();
    assertEquals(false, schemaMap.get("additionalProperties"));
  }

  public record TestPerson(String name, int age) {}

  @Test
  void parseStructuredContentValidJson() {
    var model = createModel();
    var result =
        model.parseStructuredContent(
            "{\"name\":\"Alice\",\"age\":30}", OutputSchema.of(TestPerson.class));
    assertNotNull(result);
    assertEquals("Alice", result.name());
    assertEquals(30, result.age());
  }

  @Test
  void parseStructuredContentNullReturnsNull() {
    var model = createModel();
    assertNull(model.parseStructuredContent(null, OutputSchema.of(TestPerson.class)));
  }

  @Test
  void parseStructuredContentBlankReturnsNull() {
    var model = createModel();
    assertNull(model.parseStructuredContent("   ", OutputSchema.of(TestPerson.class)));
  }

  @Test
  void parseStructuredContentInvalidJsonThrows() {
    var model = createModel();
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                model.parseStructuredContent("not json at all", OutputSchema.of(TestPerson.class)));
    assertTrue(ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")));
  }

  @Test
  void parseStructuredContentMarkdownWrapped() {
    var model = createModel();
    var result =
        model.parseStructuredContent(
            "```json\n{\"name\":\"Bob\",\"age\":25}\n```", OutputSchema.of(TestPerson.class));
    assertNotNull(result);
    assertEquals("Bob", result.name());
  }

  @Test
  void parseStructuredContentMarkdownWrappedInvalidThrows() {
    var model = createModel();
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                model.parseStructuredContent(
                    "```json\nnot valid\n```", OutputSchema.of(TestPerson.class)));
    assertTrue(ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")));
  }

  @Test
  void parseStructuredContentProvenancedReconstructsTypedOutput() {
    var model = createModel();
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
  void parseStructuredContentProvenancedHandlesMarkdownWrapper() {
    var model = createModel();
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    var json =
        "```json\n{\"output\":{\"name\":\"Bob\",\"age\":25},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[],\"reasoning\":\"r\",\"confidence\":\"LOW\"},"
            + "{\"field\":\"age\",\"sources\":[],\"reasoning\":\"r\",\"confidence\":\"LOW\"}]}\n```";

    var result = model.parseStructuredContent(json, schema);
    assertEquals("Bob", result.output().name());
  }

  @Test
  void serializeRequestProducesValidJson() {
    var model = createModel();
    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Hello")))
            .withStream(true)
            .build();

    var json = model.serializeRequest(request);

    assertNotNull(json);
    assertTrue(json.contains("\"model\":\"gpt-4o\""));
    assertTrue(json.contains("\"stream\":true"));
  }

  @Test
  void buildHttpRequestSetsCorrectUri() {
    var config = ModelConfig.newBuilder().withApiKey("sk-test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var httpRequest = model.buildHttpRequest("{\"model\":\"gpt-4o\"}");

    assertEquals("POST", httpRequest.method());
    assertEquals(URI.create("https://api.openai.com/v1/responses"), httpRequest.uri());
    assertEquals("application/json", httpRequest.headers().firstValue("Content-Type").get());
  }

  @Test
  void buildHttpRequestUsesDefaultTimeout() {
    var config = ModelConfig.newBuilder().withApiKey("sk-test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var httpRequest = model.buildHttpRequest("{\"model\":\"gpt-4o\"}");

    assertTrue(httpRequest.timeout().isPresent());
    assertEquals(Duration.ofSeconds(60), httpRequest.timeout().get());
  }

  @Test
  void buildHttpRequestUsesCustomTimeout() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("sk-test-key")
            .withResponseTimeout(Duration.ofSeconds(30))
            .build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var httpRequest = model.buildHttpRequest("{\"model\":\"gpt-4o\"}");

    assertTrue(httpRequest.timeout().isPresent());
    assertEquals(Duration.ofSeconds(30), httpRequest.timeout().get());
  }

  @Test
  void buildHttpRequestSkipsTimeoutWhenNull() {
    // Parity with AnthropicModel / GeminiModel — when ModelConfig.responseTimeout is null we must
    // not pass null to HttpRequest.Builder.timeout (which NPEs).
    var config =
        ModelConfig.newBuilder().withApiKey("sk-test-key").withResponseTimeout(null).build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);

    var httpRequest = model.buildHttpRequest("{\"model\":\"gpt-4o\"}");

    assertTrue(httpRequest.timeout().isEmpty());
  }

  @Test
  void buildHttpRequestSendsDefaultAuthorizationHeader() {
    var config = ModelConfig.newBuilder().withApiKey("sk-test-key").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals(
        "Bearer sk-test-key", httpRequest.headers().firstValue("Authorization").orElseThrow());
  }

  @Test
  void buildHttpRequestUsesConfiguredBaseUrl() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("sk-test-key")
            .withBaseUrl("https://my-llm-proxy.example/v1/responses")
            .build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals(URI.create("https://my-llm-proxy.example/v1/responses"), httpRequest.uri());
  }

  @Test
  void buildHttpRequestAzureModeSkipsDefaultAuthorizationWhenApiKeyBlank() {
    var config =
        ModelConfig.newBuilder()
            .withBaseUrl(
                "https://my-resource.openai.azure.com/openai/deployments/my-dep/responses?api-version=2024-08-01-preview")
            .withHeader("api-key", "azure-secret")
            .build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals(
        URI.create(
            "https://my-resource.openai.azure.com/openai/deployments/my-dep/responses?api-version=2024-08-01-preview"),
        httpRequest.uri());
    assertEquals("azure-secret", httpRequest.headers().firstValue("api-key").orElseThrow());
    assertTrue(
        httpRequest.headers().firstValue("Authorization").isEmpty(),
        "default Authorization is omitted when apiKey is blank — Azure gets a clean api-key only request");
  }

  @Test
  void constructorAllowsBlankApiKeyWhenBaseUrlSet() {
    var config = ModelConfig.newBuilder().withBaseUrl("https://proxy.example/v1/responses").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertTrue(httpRequest.headers().firstValue("Authorization").isEmpty());
  }

  @Test
  void constructorStillRequiresApiKeyWhenBaseUrlIsNull() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(
        IllegalArgumentException.class, () -> new OpenAIModel(OpenAIModelId.GPT_4O, config));
  }

  @Test
  void buildHttpRequestExtraHeadersAreAppended() {
    var config =
        ModelConfig.newBuilder().withApiKey("sk-test-key").withHeader("x-trace-id", "abc").build();
    var model = new OpenAIModel(OpenAIModelId.GPT_4O, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals("abc", httpRequest.headers().firstValue("x-trace-id").orElseThrow());
    assertEquals(
        "Bearer sk-test-key", httpRequest.headers().firstValue("Authorization").orElseThrow());
  }

  @Test
  void drainToResponseExtractsResponse() {
    var sse =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n";

    var objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (var iterator =
        new OpenAIModel.StreamingIterator(fakeResponse(sse), objectMapper, Duration.ofSeconds(5))) {
      var response = OpenAIModel.drainToResponse(iterator);
      assertEquals("Hi", response.content());
      assertEquals(FinishReason.STOP, response.finishReason());
      assertNotNull(response.usage());
    }
  }

  @Test
  void drainToResponseRethrowsOpenAIException() {
    var sse =
        "data: {\"type\":\"response.failed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"failed\"}}\n\n";

    var objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (var iterator =
        new OpenAIModel.StreamingIterator(fakeResponse(sse), objectMapper, Duration.ofSeconds(5))) {
      assertThrows(OpenAIException.class, () -> OpenAIModel.drainToResponse(iterator));
    }
  }

  @Test
  void drainToResponseWrapsGenericError() {
    var sse = "data: {\"type\":\"error\",\"message\":\"bad things\"}\n\n";

    var objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (var iterator =
        new OpenAIModel.StreamingIterator(fakeResponse(sse), objectMapper, Duration.ofSeconds(5))) {
      assertThrows(OpenAIException.class, () -> OpenAIModel.drainToResponse(iterator));
    }
  }

  @Test
  void drainToResponseEmptyStreamReturnsDone() {
    var sse = "";

    var objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (var iterator =
        new OpenAIModel.StreamingIterator(fakeResponse(sse), objectMapper, Duration.ofSeconds(5))) {
      var response = OpenAIModel.drainToResponse(iterator);
      assertNotNull(response);
      assertEquals("", response.content());
      assertEquals(FinishReason.STOP, response.finishReason());
    }
  }

  private static HttpResponse<InputStream> fakeResponse(String sseData) {
    var inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8));
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 200;
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
      }

      @Override
      public InputStream body() {
        return inputStream;
      }

      @Override
      public Optional<HttpResponse<InputStream>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpRequest request() {
        return null;
      }

      @Override
      public URI uri() {
        return URI.create("https://test");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_2;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }
    };
  }

  @Test
  void closeReleasesHttpClientResources() {
    var model = createModel();
    model.close();
  }

  @Test
  void closeIsIdempotent() {
    var model = createModel();
    model.close();
    model.close();
    model.close();
  }

  @Test
  void modelUsableInTryWithResources() {
    try (var model = createModel()) {
      assertEquals(OpenAIModelId.GPT_4O.id(), model.id());
    }
  }

  @Test
  void parseStructuredContentSchemaMismatchSurfacesFieldLevelDiff() {
    var model = createModel();
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
    var model = createModel();
    var schema = OutputSchema.provenancedOf(TestPerson.class);
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
}
