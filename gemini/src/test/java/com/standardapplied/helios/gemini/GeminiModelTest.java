/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.FileReference;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.BoundedErrorBodyContract;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.gemini.api.GeminiJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GeminiModelTest extends BoundedErrorBodyContract {

  @Test
  void thoughtSignatureDelimiterIsRecordSeparator() {
    assertEquals("", GeminiResponseAssembler.SIGNATURE_DELIMITER);
  }

  @Test
  void thoughtSignaturesRoundTripWithNewlines() {
    var signatures = List.of("abc123", "sig\nwith\nnewlines", "def456");

    var joined = String.join(GeminiResponseAssembler.SIGNATURE_DELIMITER, signatures);
    var split = joined.split(GeminiResponseAssembler.SIGNATURE_DELIMITER);

    assertArrayEquals(signatures.toArray(), split);
  }

  @Test
  void thoughtSignaturesRoundTripSingleSignature() {
    var signatures = List.of("single-sig");

    var joined = String.join(GeminiResponseAssembler.SIGNATURE_DELIMITER, signatures);
    var split = joined.split(GeminiResponseAssembler.SIGNATURE_DELIMITER);

    assertArrayEquals(signatures.toArray(), split);
  }

  @Test
  void thoughtSignatureDelimiterDoesNotAppearInBase64() {
    assertFalse("aGVsbG8gd29ybGQ=".contains(GeminiResponseAssembler.SIGNATURE_DELIMITER));
  }

  @Test
  void constructorRequiresModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertThrows(IllegalArgumentException.class, () -> new GeminiProvider().create(null, config));
  }

  @Test
  void constructorRequiresConfig() {
    assertThrows(
        IllegalArgumentException.class, () -> create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, null));
  }

  @Test
  void constructorRequiresApiKey() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(
        IllegalArgumentException.class, () -> create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config));
  }

  @Test
  void idReturnsModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), model.id());
  }

  @Test
  void providerReturnsGemini() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals("gemini", model.provider());
  }

  @Test
  void contextWindowReturnsModelValueByDefault() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW.contextWindow(), model.contextWindow());
  }

  @Test
  void contextWindowConfigOverrideWins() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withContextWindow(2_000_000).build();
    var model = create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals(2_000_000, model.contextWindow());
  }

  @ParameterizedTest(name = "interactionsContentType {0}: {1}")
  @CsvSource({
    "image, image/png image/jpeg image/webp",
    "audio, audio/mp3 audio/wav",
    "video, video/mp4",
    "document, application/pdf text/plain application/json"
  })
  void interactionsContentType(String contentType, String mimeTypes) {
    for (var mimeType : mimeTypes.split(" ")) {
      assertEquals(contentType, GeminiConversation.interactionsContentType(mimeType), mimeType);
    }
  }

  @Test
  void defaultBaseUrlUsesStableInteractionsApi() {
    assertEquals("https://generativelanguage.googleapis.com", GeminiEndpoint.DEFAULT_API_ROOT);
  }

  @Test
  void configuredApiVersionsResolveToCanonicalStableAndBetaEndpoints() {
    var stable = ModelConfig.newBuilder().withApiKey("key").withApiVersion("v1").build();
    var beta = ModelConfig.newBuilder().withApiKey("key").withApiVersion("v1beta").build();

    assertEquals(
        "https://generativelanguage.googleapis.com/v1/interactions?alt=sse",
        streams(stable).httpRequest("{}").uri().toString());
    assertEquals(
        "https://generativelanguage.googleapis.com/v1beta/interactions?alt=sse",
        streams(beta).httpRequest("{}").uri().toString());
    assertEquals("v1", GeminiEndpoint.of(stable).apiVersion());
    assertEquals("v1beta", GeminiEndpoint.of(beta).apiVersion());
  }

  @Test
  void constructorRejectsMalformedOrUnsupportedApiVersions() {
    for (var value : List.of("", " ", "beta", "V1", "v2", "/v1", "v1?key=secret")) {
      var config = ModelConfig.newBuilder().withApiKey("key").withApiVersion(value).build();
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> create(GeminiModelId.GEMINI_3_7_FLASH, config),
              value);
      assertTrue(error.getMessage().contains("apiVersion"));
      assertFalse(error.getMessage().contains("secret"));
    }
  }

  @Test
  void rejectsSamplingParametersRemovedFromStableInteractionsApi() {
    var withTemperature =
        ModelConfig.newBuilder().withApiKey("test-key").withTemperature(0.5).build();
    var withTopP = ModelConfig.newBuilder().withApiKey("test-key").withTopP(0.9).build();

    var temperatureError =
        assertThrows(
            IllegalArgumentException.class,
            () -> create(GeminiModelId.GEMINI_3_7_FLASH, withTemperature));
    var topPError =
        assertThrows(
            IllegalArgumentException.class, () -> create(GeminiModelId.GEMINI_3_7_FLASH, withTopP));

    assertTrue(temperatureError.getMessage().contains("temperature"));
    assertTrue(topPError.getMessage().contains("topP"));
  }

  @Test
  void urlContextWithFunctionToolsThrows() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withWebFetch(true).build();
    var model = create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);

    var tool =
        Tool.newBuilder()
            .withName("test_tool")
            .withDescription("A test tool")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("input")
                    .withType(ParameterType.STRING)
                    .withDescription("input")
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var messages = List.of(Message.user("Hello"));

    assertThrows(IllegalStateException.class, () -> model.chat(messages, List.of(tool)));
  }

  private static Model createModel() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    return create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
  }

  // --- convertMessages: every TOOL message becomes its own function_result step ---

  @Test
  void convertMessagesSingleToolCallProducesUserCallResultSteps() {
    var model = createModel();
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("search")
                        .withArguments(Map.of("q", "test"))
                        .build())),
            Message.tool("c1", "search", "result1"));

    var converted = GeminiConversation.of(messages);

    assertEquals(3, converted.steps().size());
    assertEquals("user_input", converted.steps().get(0).type());
    assertEquals("function_call", converted.steps().get(1).type());
    assertEquals("c1", converted.steps().get(1).id());
    assertEquals("search", converted.steps().get(1).name());
    assertEquals(Map.of("q", "test"), converted.steps().get(1).arguments());
    assertEquals("function_result", converted.steps().get(2).type());
    assertEquals("c1", converted.steps().get(2).callId());
    assertEquals("result1", converted.steps().get(2).result());
  }

  @Test
  void convertMessagesEmitsOneFunctionCallStepPerToolCall() {
    var model = createModel();
    var messages =
        List.of(
            Message.user("Get quotes"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "AAPL"))
                        .build(),
                    ToolCall.newBuilder()
                        .withId("c2")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "NVDA"))
                        .build())),
            Message.tool("c1", "quote", "AAPL: $228"),
            Message.tool("c2", "quote", "NVDA: $480"));

    var converted = GeminiConversation.of(messages);

    assertEquals(5, converted.steps().size());
    assertEquals("user_input", converted.steps().get(0).type());
    assertEquals("function_call", converted.steps().get(1).type());
    assertEquals("c1", converted.steps().get(1).id());
    assertEquals("function_call", converted.steps().get(2).type());
    assertEquals("c2", converted.steps().get(2).id());
    assertEquals("function_result", converted.steps().get(3).type());
    assertEquals("c1", converted.steps().get(3).callId());
    assertEquals("AAPL: $228", converted.steps().get(3).result());
    assertEquals("function_result", converted.steps().get(4).type());
    assertEquals("c2", converted.steps().get(4).callId());
    assertEquals("NVDA: $480", converted.steps().get(4).result());
  }

  @Test
  void convertMessagesEmitsThoughtStepsBeforeFunctionCalls() {
    var model = createModel();
    var sigs = "sig-a" + GeminiResponseAssembler.SIGNATURE_DELIMITER + "sig-b";
    var msgWithSigs =
        Message.assistant(
            null,
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("search")
                    .withArguments(Map.of("q", "x"))
                    .build()),
            Map.of(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY, sigs));
    var messages = List.of(Message.user("Hi"), msgWithSigs, Message.tool("c1", "search", "ok"));

    var converted = GeminiConversation.of(messages);

    assertEquals(5, converted.steps().size());
    assertEquals("user_input", converted.steps().get(0).type());
    assertTrue(converted.steps().get(1).hasTypeThought());
    assertEquals("sig-a", converted.steps().get(1).signature());
    assertTrue(converted.steps().get(2).hasTypeThought());
    assertEquals("sig-b", converted.steps().get(2).signature());
    assertEquals("function_call", converted.steps().get(3).type());
    assertEquals("function_result", converted.steps().get(4).type());
  }

  @Test
  void convertMessagesPlainAssistantBecomesModelOutput() {
    var model = createModel();
    var messages = List.of(Message.user("Hi"), Message.assistant("There"));

    var converted = GeminiConversation.of(messages);

    assertEquals(2, converted.steps().size());
    assertTrue(converted.steps().get(1).hasTypeModelOutput());
    assertEquals(1, converted.steps().get(1).content().size());
    assertEquals("There", converted.steps().get(1).content().getFirst().text());
  }

  @Test
  void convertMessagesUserWithInlineFilesBuildsContentList() {
    var model = createModel();
    var bytes = new byte[] {1, 2, 3};
    var user = Message.user("Describe this", List.of(new InlineFile(bytes, "image/png")));

    var converted = GeminiConversation.of(List.of(user));

    assertEquals(1, converted.steps().size());
    var step = converted.steps().getFirst();
    assertEquals("user_input", step.type());
    assertEquals(2, step.content().size());
    var image = step.content().get(0);
    assertEquals("image", image.type());
    assertEquals("image/png", image.mimeType());
    assertNotNull(image.data());
    var text = step.content().get(1);
    assertEquals("text", text.type());
    assertEquals("Describe this", text.text());
  }

  @Test
  void convertMessagesUserWithPdfInlineFileEmitsDocumentContent() {
    var model = createModel();
    var pdf = new byte[] {37, 80, 68, 70};
    var user = Message.user("Extract", List.of(new InlineFile(pdf, "application/pdf")));

    var converted = GeminiConversation.of(List.of(user));

    var step = converted.steps().getFirst();
    var doc = step.content().get(0);
    assertEquals("document", doc.type());
    assertEquals("application/pdf", doc.mimeType());
    assertNotNull(doc.data());
  }

  @Test
  void convertMessagesUserWithUploadedVideoEmitsVideoUriContent() {
    var video =
        FileReference.of(
            "https://generativelanguage.googleapis.com/v1beta/files/video-123", "video/mp4");
    var user =
        new Message(
            Role.USER,
            "Summarize this video",
            List.of(),
            null,
            null,
            Map.of(),
            null,
            List.of(video));

    var result = GeminiConversation.of(List.of(user));

    var content = result.steps().getFirst().content();
    assertEquals(2, content.size());
    assertEquals("video", content.getFirst().type());
    assertEquals("video/mp4", content.getFirst().mimeType());
    assertEquals(video.uri(), content.getFirst().uri());
    assertNull(content.getFirst().data());
    assertEquals("Summarize this video", content.getLast().text());
  }

  @Test
  void convertMessagesExtractsSystemInstruction() {
    var model = createModel();
    var messages = List.of(Message.system("Be helpful"), Message.user("Hi"));

    var converted = GeminiConversation.of(messages);

    assertEquals("Be helpful", converted.systemInstruction());
    assertEquals(1, converted.steps().size());
    assertEquals("user_input", converted.steps().getFirst().type());
  }

  @Test
  void convertMessagesNoSystemInstruction() {
    var model = createModel();
    var messages = List.of(Message.user("Hi"));

    var converted = GeminiConversation.of(messages);

    assertNull(converted.systemInstruction());
    assertEquals(1, converted.steps().size());
  }

  @Test
  void convertMessagesFullMultiTurnRoundTrip() {
    var model = createModel();
    var messages =
        List.of(
            Message.system("You are an analyst"),
            Message.user("Analyze portfolio"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "AAPL"))
                        .build(),
                    ToolCall.newBuilder()
                        .withId("c2")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "NVDA"))
                        .build())),
            Message.tool("c1", "quote", "AAPL: $228"),
            Message.tool("c2", "quote", "NVDA: $480"),
            Message.assistant("Your portfolio looks good"),
            Message.user("What about MSFT?"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c3")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "MSFT"))
                        .build())),
            Message.tool("c3", "quote", "MSFT: $420"),
            Message.assistant("MSFT is strong"));

    var converted = GeminiConversation.of(messages);

    assertEquals("You are an analyst", converted.systemInstruction());
    var steps = converted.steps();
    assertEquals(10, steps.size());
    assertEquals("user_input", steps.get(0).type());
    assertEquals("function_call", steps.get(1).type());
    assertEquals("c1", steps.get(1).id());
    assertEquals("function_call", steps.get(2).type());
    assertEquals("c2", steps.get(2).id());
    assertEquals("function_result", steps.get(3).type());
    assertEquals("c1", steps.get(3).callId());
    assertEquals("function_result", steps.get(4).type());
    assertEquals("c2", steps.get(4).callId());
    assertTrue(steps.get(5).hasTypeModelOutput());
    assertEquals("user_input", steps.get(6).type());
    assertEquals("function_call", steps.get(7).type());
    assertEquals("c3", steps.get(7).id());
    assertEquals("function_result", steps.get(8).type());
    assertTrue(steps.get(9).hasTypeModelOutput());
  }

  // --- Continuation mode tests ---

  @Test
  void findContinuationPointReturnsNullWhenNoInteractionId() {
    var messages = List.of(Message.user("Hello"), Message.assistant("Hi"));

    var result = ContinuationPoint.find(messages);

    assertNull(result);
  }

  @Test
  void findContinuationPointFindsLastAssistantWithId() {
    var metadata = Map.of(ContinuationPoint.INTERACTION_ID_KEY, "interaction-123");
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant("Hi", List.of(), metadata),
            Message.user("Follow-up"));

    var result = ContinuationPoint.find(messages);

    assertNotNull(result);
    assertEquals("interaction-123", result.interactionId());
    assertEquals(2, result.startIndex());
  }

  @Test
  void findContinuationPointPicksLastOfMultipleAssistants() {
    var meta1 = Map.of(ContinuationPoint.INTERACTION_ID_KEY, "interaction-1");
    var meta2 = Map.of(ContinuationPoint.INTERACTION_ID_KEY, "interaction-2");
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant("First", List.of(), meta1),
            Message.user("More"),
            Message.assistant("Second", List.of(), meta2),
            Message.tool("c1", "search", "result"));

    var result = ContinuationPoint.find(messages);

    assertNotNull(result);
    assertEquals("interaction-2", result.interactionId());
    assertEquals(4, result.startIndex());
  }

  @Test
  void findContinuationPointSkipsAssistantWithEmptyId() {
    var metadata = Map.of(ContinuationPoint.INTERACTION_ID_KEY, "");
    var messages = List.of(Message.user("Hello"), Message.assistant("Hi", List.of(), metadata));

    var result = ContinuationPoint.find(messages);

    assertNull(result);
  }

  @Test
  void findContinuationPointWithToolCallAssistant() {
    var toolCalls =
        List.of(
            ToolCall.newBuilder()
                .withId("c1")
                .withName("search")
                .withArguments(Map.of("q", "test"))
                .build());
    var metadata = Map.of(ContinuationPoint.INTERACTION_ID_KEY, "interaction-456");
    var messages =
        List.of(
            Message.user("Search for something"),
            Message.assistant(null, toolCalls, metadata),
            Message.tool("c1", "search", "found it"));

    var result = ContinuationPoint.find(messages);

    assertNotNull(result);
    assertEquals("interaction-456", result.interactionId());
    assertEquals(2, result.startIndex());
  }

  @Test
  void buildContinuationStepsSingleToolResult() {
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant("calling tool"),
            Message.tool("c1", "search", "result1"));

    var steps = GeminiConversation.continuationSteps(messages, 2);

    assertEquals(1, steps.size());
    assertEquals("function_result", steps.getFirst().type());
    assertEquals("search", steps.getFirst().name());
    assertEquals("c1", steps.getFirst().callId());
    assertEquals("result1", steps.getFirst().result());
  }

  @Test
  void buildContinuationStepsMultipleToolResultsRemainSeparateSteps() {
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant("calling tools"),
            Message.tool("c1", "quote", "AAPL: $228"),
            Message.tool("c2", "quote", "NVDA: $480"));

    var steps = GeminiConversation.continuationSteps(messages, 2);

    assertEquals(2, steps.size());
    assertEquals("function_result", steps.get(0).type());
    assertEquals("c1", steps.get(0).callId());
    assertEquals("AAPL: $228", steps.get(0).result());
    assertEquals("function_result", steps.get(1).type());
    assertEquals("c2", steps.get(1).callId());
    assertEquals("NVDA: $480", steps.get(1).result());
  }

  @Test
  void buildContinuationStepsUserMessage() {
    var messages =
        List.of(Message.user("Hello"), Message.assistant("Hi"), Message.user("Follow-up"));

    var steps = GeminiConversation.continuationSteps(messages, 2);

    assertEquals(1, steps.size());
    assertEquals("user_input", steps.getFirst().type());
    assertEquals(1, steps.getFirst().content().size());
    assertEquals("Follow-up", steps.getFirst().content().getFirst().text());
  }

  @Test
  void buildContinuationStepsSkipsSystemMessages() {
    var messages = new ArrayList<Message>();
    messages.add(Message.system("Be helpful"));
    messages.add(Message.user("Hello"));
    messages.add(Message.assistant("Hi"));
    messages.add(Message.user("Follow-up"));

    var steps = GeminiConversation.continuationSteps(messages, 3);

    assertEquals(1, steps.size());
    assertEquals("user_input", steps.getFirst().type());
    assertEquals("Follow-up", steps.getFirst().content().getFirst().text());
  }

  @Test
  void buildContinuationStepsMixedToolAndUser() {
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant("calling tool"),
            Message.tool("c1", "search", "result1"),
            Message.user("Thanks, now search more"));

    var steps = GeminiConversation.continuationSteps(messages, 2);

    assertEquals(2, steps.size());
    assertEquals("function_result", steps.get(0).type());
    assertEquals("c1", steps.get(0).callId());
    assertEquals("user_input", steps.get(1).type());
    assertEquals("Thanks, now search more", steps.get(1).content().getFirst().text());
  }

  @Test
  void findContinuationPointSkipsAssistantWithNullMetadata() {
    var msg = new Message(Role.ASSISTANT, "Hi", List.of(), null, null, null, List.of());
    var messages = List.of(Message.user("Hello"), msg);

    var result = ContinuationPoint.find(messages);

    assertNull(result);
  }

  @Test
  void buildContinuationStepsSkipsAssistantMessages() {
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant("Hi"),
            Message.assistant("more"),
            Message.user("Follow-up"));

    var steps = GeminiConversation.continuationSteps(messages, 1);

    assertEquals(1, steps.size());
    assertEquals("user_input", steps.getFirst().type());
    assertEquals("Follow-up", steps.getFirst().content().getFirst().text());
  }

  @Test
  void interactionIdKeyConstant() {
    assertEquals("gemini.interactionId", ContinuationPoint.INTERACTION_ID_KEY);
  }

  @Test
  void continuationPointRecord() {
    var point = new ContinuationPoint("id-123", 5);
    assertEquals("id-123", point.interactionId());
    assertEquals(5, point.startIndex());
  }

  @Test
  void buildRequestContinuationIncludesSystemInstruction() {
    var requests = defaultRequests();
    var metadata = Map.of(ContinuationPoint.INTERACTION_ID_KEY, "int-abc");
    var toolCalls =
        List.of(
            ToolCall.newBuilder()
                .withId("c1")
                .withName("search")
                .withArguments(Map.of("q", "test"))
                .build());
    var messages =
        List.of(
            Message.system("You are a helpful assistant"),
            Message.user("Search for X"),
            Message.assistant(null, toolCalls, metadata),
            Message.tool("c1", "search", "found it"));

    var request = requests.build(messages, null, null);

    assertNotNull(request.previousInteractionId(), "continuation must use previous_interaction_id");
    assertEquals("int-abc", request.previousInteractionId());
    assertEquals(
        "You are a helpful assistant",
        request.systemInstruction(),
        "system instruction must be included on continuation requests");
  }

  @Test
  void statelessInteractionsResendLocalHistoryWithoutInteractionId() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withProviderContinuation(false).build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_7_FLASH, config);
    var messages =
        List.of(
            Message.system("system"),
            Message.user("first"),
            Message.assistant(
                "answer",
                List.of(),
                Map.of(ContinuationPoint.INTERACTION_ID_KEY, "interaction-secret")),
            Message.user("second"));

    var request = requests.build(messages, null, null);

    assertNull(request.previousInteractionId());
    assertEquals("system", request.systemInstruction());
    assertEquals(3, request.input().size());
    assertEquals("user_input", request.input().get(0).type());
    assertTrue(request.input().get(1).hasTypeModelOutput());
    assertEquals("user_input", request.input().get(2).type());
  }

  @Test
  void buildRequestThinkingLevelMinimalMapsToMinimal() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.MINIMAL)
            .build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_5_FLASH, config);
    var messages = List.of(Message.user("Hello"));

    var request = requests.build(messages, null, null);

    assertEquals("minimal", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelLowMapsToLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.LOW)
            .build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_5_FLASH, config);
    var messages = List.of(Message.user("Hello"));

    var request = requests.build(messages, null, null);

    assertEquals("low", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelHighMapsToHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.HIGH)
            .build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_5_FLASH, config);
    var messages = List.of(Message.user("Hello"));

    var request = requests.build(messages, null, null);

    assertEquals("high", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelMediumMapsToMedium() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.MEDIUM)
            .build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_5_FLASH, config);
    var request = requests.build(List.of(Message.user("Hello")), null, null);

    assertEquals("medium", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelXhighClampsToHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.XHIGH)
            .build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_5_FLASH, config);
    var request = requests.build(List.of(Message.user("Hello")), null, null);

    assertEquals("high", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelMaxClampsToHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.MAX)
            .build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_5_FLASH, config);
    var request = requests.build(List.of(Message.user("Hello")), null, null);

    assertEquals("high", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelNoneSendsLowestSupportedLevel() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.NONE)
            .build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_5_FLASH, config);

    var request = requests.build(List.of(Message.user("Hello")), null, null);

    assertEquals(
        "minimal",
        request.generationConfig().thinkingLevel(),
        "Gemini 3.x cannot turn thinking off; NONE must pin the lowest tier, not the default");
  }

  @Test
  void buildRequestThinkingLevelNoneOnLowFloorModelSendsLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.NONE)
            .build();
    var requests = new GeminiRequestBuilder(GeminiModelId.GEMINI_3_7_FLASH, config);

    var request = requests.build(List.of(Message.user("Hello")), null, null);

    assertEquals("low", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelMinimalClampsToLowWhereMinimalIsUnsupported() {
    for (var id : List.of(GeminiModelId.GEMINI_3_7_FLASH, GeminiModelId.GEMINI_3_1_PRO_PREVIEW)) {
      var config =
          ModelConfig.newBuilder()
              .withApiKey("test-key")
              .withThinkingLevel(com.standardapplied.helios.core.model.ThinkingLevel.MINIMAL)
              .build();
      var requests = new GeminiRequestBuilder(id, config);

      var request = requests.build(List.of(Message.user("Hello")), null, null);

      assertEquals("low", request.generationConfig().thinkingLevel(), id.id());
    }
  }

  @Test
  void extractSystemInstructionFindsSystemMessage() {
    var messages = List.of(Message.system("Be helpful"), Message.user("Hi"));
    assertEquals("Be helpful", GeminiConversation.extractSystemInstruction(messages));
  }

  @Test
  void extractSystemInstructionReturnsNullWhenAbsent() {
    var messages = List.of(Message.user("Hi"));
    assertNull(GeminiConversation.extractSystemInstruction(messages));
  }

  @Test
  void extractSystemInstructionTakesLastSystemMessage() {
    var messages = List.of(Message.system("First"), Message.user("Hi"), Message.system("Second"));
    assertEquals("Second", GeminiConversation.extractSystemInstruction(messages));
  }

  @Test
  void buildRequestContinuationWithoutSystemMessageOmitsIt() {
    var requests = defaultRequests();
    var metadata = Map.of(ContinuationPoint.INTERACTION_ID_KEY, "int-abc");
    var messages =
        List.of(
            Message.user("Search for X"),
            Message.assistant("ok", List.of(), metadata),
            Message.user("Follow-up"));

    var request = requests.build(messages, null, null);

    assertNotNull(request.previousInteractionId());
    assertNull(request.systemInstruction());
  }

  @Test
  void closeReleasesHttpClientResources() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    model.close();
  }

  @Test
  void closeIsIdempotent() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    model.close();
    model.close();
    model.close();
  }

  @Test
  void modelUsableInTryWithResources() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    try (var model = create(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config)) {
      assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), model.id());
    }
  }

  public record TestPerson(String name, int age) {}

  @Test
  void parseStructuredContentPlainSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var result =
        parse("{\"name\":\"Alice\",\"age\":30}", OutputSchema.of(TestPerson.class), config);
    assertEquals("Alice", result.name());
    assertEquals(30, result.age());
  }

  @Test
  void parseStructuredContentProvenancedReconstructsTypedOutput() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    var json =
        "{\"output\":{\"name\":\"Alice\",\"age\":30},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[{\"url\":\"https://x.com\",\"excerpts\":[\"a\"]}],"
            + "\"reasoning\":\"named in source\",\"confidence\":\"HIGH\"},"
            + "{\"field\":\"age\",\"sources\":[],\"reasoning\":\"guess\",\"confidence\":\"LOW\"}]}";

    var result = parse(json, schema, config);

    assertNotNull(result);
    assertEquals("Alice", result.output().name());
    assertEquals(30, result.output().age());
    assertEquals(2, result.provenance().size());
    assertEquals("HIGH", result.forField("name").confidence().wireValue());
  }

  @Test
  void parseStructuredContentThatIsNotAnObjectReportsTheUntypedMapItCouldNotRead() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> parse("[1, 2]", OutputSchema.of(TestPerson.class), config));

    assertEquals(
        "JSON syntax error: Cannot deserialize value of type"
            + " `java.util.LinkedHashMap<java.lang.Object,java.lang.Object>` from Array value"
            + " (token `JsonToken.START_ARRAY`)",
        ex.errors().getFirst().lines().findFirst().orElseThrow());
  }

  @Test
  void parseStructuredContentNullReturnsNull() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertNull(parse(null, OutputSchema.of(TestPerson.class), config));
  }

  @Test
  void parseStructuredContentSchemaMismatchSurfacesFieldLevelDiff() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var schema = OutputSchema.of(TestPerson.class);
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> parse("{\"name\":\"Alice\"}", schema, config));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.contains("age") && e.contains("required")),
        "diff must name the missing 'age' field as required: " + ex.errors());
    assertEquals("{\"name\":\"Alice\"}", ex.rawContent());
  }

  @Test
  void parseStructuredContentSyntaxErrorThrowsStructuredOutputParseException() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                parse(
                    "{\"name\":\"Alice\",unterminated", OutputSchema.of(TestPerson.class), config));
    assertTrue(ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")));
  }

  @Test
  void disabledRawOutputCaptureIsAppliedByGeminiParser() {
    var canary = "private-model-output-canary";
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
            .build();

    var error =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                parse("{\"name\":\"" + canary + "\"}", OutputSchema.of(TestPerson.class), config));

    assertNull(error.rawContent());
    assertFalse(error.getMessage().contains(canary));
  }

  @Test
  void buildHttpRequestUsesDefaultsWhenBaseUrlAndHeadersUnset() {
    var config = ModelConfig.newBuilder().withApiKey("g-key").build();
    var httpRequest = streams(config).httpRequest("{}");
    assertEquals(
        java.net.URI.create("https://generativelanguage.googleapis.com/v1/interactions?alt=sse"),
        httpRequest.uri());
    assertEquals("g-key", httpRequest.headers().firstValue("x-goog-api-key").orElseThrow());
    assertTrue(httpRequest.headers().firstValue("Api-Revision").isEmpty());
  }

  @Test
  void stableVideoRejectionIsSurfacedWithoutBetaReplay() throws Exception {
    var paths = new CopyOnWriteArrayList<String>();
    try (var server = new ServerSocket(0)) {
      var serverThread =
          Thread.startVirtualThread(
              () -> {
                try {
                  while (!server.isClosed()) {
                    try (var socket = server.accept()) {
                      var reader =
                          new BufferedReader(
                              new InputStreamReader(
                                  socket.getInputStream(), StandardCharsets.US_ASCII));
                      var requestLine = reader.readLine();
                      paths.add(requestLine.split(" ")[1].replace("?alt=sse", ""));
                      String header;
                      while ((header = reader.readLine()) != null && !header.isEmpty()) {}
                      var body =
                          "{\"error\":{\"message\":\"The value 'video' is not supported for 'type'\"}}"
                              .getBytes(StandardCharsets.UTF_8);
                      var output = socket.getOutputStream();
                      output.write(
                          ("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: "
                                  + body.length
                                  + "\r\nConnection: close\r\n\r\n")
                              .getBytes(StandardCharsets.US_ASCII));
                      output.write(body);
                      output.flush();
                    }
                  }
                } catch (SocketException ignored) {
                  return;
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      var baseUrl = "http://127.0.0.1:" + server.getLocalPort() + "/v1";
      var config =
          ModelConfig.newBuilder()
              .withApiKey("test-key")
              .withApiVersion("v1")
              .withBaseUrl(baseUrl)
              .build();
      var model = create(GeminiModelId.GEMINI_3_7_FLASH, config);
      var video = FileReference.of("https://files.example/private", "video/mp4");
      var message =
          Message.newBuilder()
              .withRole(Role.USER)
              .withContent("analyze")
              .withFileReferences(List.of(video))
              .build();

      var error = assertThrows(GeminiException.class, () -> model.chat(List.of(message)));

      assertEquals(400, error.statusCode());
      assertTrue(error.getMessage().contains("video"));
      assertEquals(List.of("/v1/interactions"), paths);
      server.close();
      serverThread.join();
    }
  }

  @Test
  void buildHttpRequestUsesConfiguredBaseUrl() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("g-key")
            .withBaseUrl("https://vertex.example/v1beta")
            .build();
    var httpRequest = streams(config).httpRequest("{}");
    assertEquals(
        java.net.URI.create("https://vertex.example/v1beta/interactions?alt=sse"),
        httpRequest.uri(),
        "configured baseUrl replaces the default host+/v1beta prefix; provider keeps appending its own /interactions?alt=sse");
    assertEquals("v1beta", GeminiEndpoint.of(config).apiVersion());
  }

  @Test
  void customBaseUrlHasNonSensitiveCustomVersionDiagnostic() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("g-key")
            .withBaseUrl("https://gateway.example/gemini")
            .build();

    assertEquals("custom", GeminiEndpoint.of(config).apiVersion());
  }

  @Test
  void buildHttpRequestUserHeaderReplacesBuiltinByCaseInsensitiveName() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("default-key")
            .withHeader("X-GOOG-API-KEY", "override-key")
            .build();
    var httpRequest = streams(config).httpRequest("{}");
    assertEquals("override-key", httpRequest.headers().firstValue("x-goog-api-key").orElseThrow());
    assertEquals(
        1,
        httpRequest.headers().allValues("x-goog-api-key").size(),
        "name match must replace the default rather than append a second header line");
  }

  @Test
  void buildHttpRequestExtraHeaderIsAppended() {
    var config = ModelConfig.newBuilder().withApiKey("g-key").withHeader("x-trace", "t1").build();
    var httpRequest = streams(config).httpRequest("{}");
    assertEquals("t1", httpRequest.headers().firstValue("x-trace").orElseThrow());
    assertEquals("g-key", httpRequest.headers().firstValue("x-goog-api-key").orElseThrow());
  }

  private static Model create(GeminiModelId id, ModelConfig config) {
    return new GeminiProvider().create(id.id(), config);
  }

  private static GeminiRequestBuilder defaultRequests() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    return new GeminiRequestBuilder(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
  }

  private static GeminiStreams streams(ModelConfig config) {
    return new GeminiStreams(config, HttpClientFactory.create(config), GeminiEndpoint.of(config));
  }

  private static <T> T parse(String content, OutputSchema<T> schema, ModelConfig config) {
    return StructuredContentParser.parse(
        content, schema, GeminiJson.STRUCTURED, config.rawOutputCapturePolicy());
  }
}
