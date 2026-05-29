/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.model.InlineFile;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Role;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredOutputParseException;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.gemini.api.ContentItem;
import ai.singlr.gemini.api.OutputAnnotation;
import ai.singlr.gemini.api.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiModelTest {

  private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

  @Test
  void thoughtSignatureDelimiterIsRecordSeparator() {
    assertEquals("", GeminiModel.SIGNATURE_DELIMITER);
  }

  @Test
  void thoughtSignaturesRoundTripWithNewlines() {
    var signatures = List.of("abc123", "sig\nwith\nnewlines", "def456");

    var joined = String.join(GeminiModel.SIGNATURE_DELIMITER, signatures);
    var split = joined.split(GeminiModel.SIGNATURE_DELIMITER);

    assertArrayEquals(signatures.toArray(), split);
  }

  @Test
  void thoughtSignaturesRoundTripSingleSignature() {
    var signatures = List.of("single-sig");

    var joined = String.join(GeminiModel.SIGNATURE_DELIMITER, signatures);
    var split = joined.split(GeminiModel.SIGNATURE_DELIMITER);

    assertArrayEquals(signatures.toArray(), split);
  }

  @Test
  void thoughtSignatureDelimiterDoesNotAppearInBase64() {
    assertFalse("aGVsbG8gd29ybGQ=".contains(GeminiModel.SIGNATURE_DELIMITER));
  }

  @Test
  void constructorRequiresModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertThrows(IllegalArgumentException.class, () -> new GeminiModel(null, config));
  }

  @Test
  void constructorRequiresConfig() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, null));
  }

  @Test
  void constructorRequiresApiKey() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config));
  }

  @Test
  void idReturnsModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), model.id());
  }

  @Test
  void providerReturnsGemini() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals("gemini", model.provider());
  }

  @Test
  void contextWindowReturnsModelValueByDefault() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW.contextWindow(), model.contextWindow());
  }

  @Test
  void contextWindowConfigOverrideWins() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withContextWindow(2_000_000).build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals(2_000_000, model.contextWindow());
  }

  @Test
  void interactionsContentTypeImage() {
    assertEquals("image", GeminiModel.interactionsContentType("image/png"));
    assertEquals("image", GeminiModel.interactionsContentType("image/jpeg"));
    assertEquals("image", GeminiModel.interactionsContentType("image/webp"));
  }

  @Test
  void interactionsContentTypeAudio() {
    assertEquals("audio", GeminiModel.interactionsContentType("audio/mp3"));
    assertEquals("audio", GeminiModel.interactionsContentType("audio/wav"));
  }

  @Test
  void interactionsContentTypeVideo() {
    assertEquals("video", GeminiModel.interactionsContentType("video/mp4"));
  }

  @Test
  void interactionsContentTypeDocument() {
    assertEquals("document", GeminiModel.interactionsContentType("application/pdf"));
    assertEquals("document", GeminiModel.interactionsContentType("text/plain"));
    assertEquals("document", GeminiModel.interactionsContentType("application/json"));
  }

  private static ContentItem textWithAnnotations(String text, OutputAnnotation... annotations) {
    return new ContentItem("text", text, null, null, null, null, List.of(annotations), null);
  }

  @Test
  void extractCitationsFromUrlAnnotations() {
    var annotation = new OutputAnnotation("url_citation", "https://example.com", "Example", 0, 10);
    var step = Step.modelOutput(List.of(textWithAnnotations("Some text", annotation)));
    var citations = GeminiModel.extractCitations(List.of(step));

    assertEquals(1, citations.size());
    var citation = citations.getFirst();
    assertEquals("https://example.com", citation.sourceId());
    assertEquals("Example", citation.title());
    assertEquals(0, citation.startIndex());
    assertEquals(10, citation.endIndex());
  }

  @Test
  void extractCitationsSkipsNonUrlCitation() {
    var annotation = new OutputAnnotation("file_citation", "file://local", "Local File", 0, 5);
    var step = Step.modelOutput(List.of(textWithAnnotations("Some text", annotation)));
    var citations = GeminiModel.extractCitations(List.of(step));

    assertTrue(citations.isEmpty());
  }

  @Test
  void extractCitationsFromNullSteps() {
    assertTrue(GeminiModel.extractCitations(null).isEmpty());
  }

  @Test
  void extractCitationsSkipsNonModelOutputSteps() {
    var annotation = new OutputAnnotation("url_citation", "https://example.com", "Example", 0, 10);
    var thoughtStep = Step.thought("sig", List.of(ContentItem.text("summary")));
    var userStep = Step.userInput(List.of(textWithAnnotations("ignored", annotation)));
    assertTrue(GeminiModel.extractCitations(List.of(thoughtStep, userStep)).isEmpty());
  }

  @Test
  void extractCitationsSkipsNonTextContent() {
    var step = Step.modelOutput(List.of(ContentItem.image("image/png", "iVBORw0KGgo=")));
    assertTrue(GeminiModel.extractCitations(List.of(step)).isEmpty());
  }

  @Test
  void extractCitationsMultipleStepsAndAnnotations() {
    var ann1 = new OutputAnnotation("url_citation", "https://a.com", "A", 0, 5);
    var ann2 = new OutputAnnotation("url_citation", "https://b.com", "B", 10, 20);
    var step1 = Step.modelOutput(List.of(textWithAnnotations("First", ann1)));
    var step2 = Step.modelOutput(List.of(textWithAnnotations("Second", ann2)));
    var citations = GeminiModel.extractCitations(List.of(step1, step2));

    assertEquals(2, citations.size());
    assertEquals("https://a.com", citations.get(0).sourceId());
    assertEquals("https://b.com", citations.get(1).sourceId());
  }

  @Test
  void extractCitationsFromContentWithNoAnnotations() {
    var step = Step.modelOutput("No sources here");
    assertTrue(GeminiModel.extractCitations(List.of(step)).isEmpty());
  }

  @Test
  void extractCitationsFromEmptyModelOutput() {
    var step = Step.modelOutput(List.of());
    assertTrue(GeminiModel.extractCitations(List.of(step)).isEmpty());
  }

  @Test
  void apiRevisionConstantTracksMay2026Schema() {
    assertEquals("2026-05-20", GeminiModel.API_REVISION);
  }

  @Test
  void urlContextWithFunctionToolsThrows() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withUrlContext(true).build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);

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

  private GeminiModel createModel() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    return new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
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

    var converted = model.convertMessages(messages);

    assertEquals(3, converted.steps().size());
    assertTrue(converted.steps().get(0).hasTypeUserInput());
    assertTrue(converted.steps().get(1).hasTypeFunctionCall());
    assertEquals("c1", converted.steps().get(1).id());
    assertEquals("search", converted.steps().get(1).name());
    assertEquals(Map.of("q", "test"), converted.steps().get(1).arguments());
    assertTrue(converted.steps().get(2).hasTypeFunctionResult());
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

    var converted = model.convertMessages(messages);

    assertEquals(5, converted.steps().size());
    assertTrue(converted.steps().get(0).hasTypeUserInput());
    assertTrue(converted.steps().get(1).hasTypeFunctionCall());
    assertEquals("c1", converted.steps().get(1).id());
    assertTrue(converted.steps().get(2).hasTypeFunctionCall());
    assertEquals("c2", converted.steps().get(2).id());
    assertTrue(converted.steps().get(3).hasTypeFunctionResult());
    assertEquals("c1", converted.steps().get(3).callId());
    assertEquals("AAPL: $228", converted.steps().get(3).result());
    assertTrue(converted.steps().get(4).hasTypeFunctionResult());
    assertEquals("c2", converted.steps().get(4).callId());
    assertEquals("NVDA: $480", converted.steps().get(4).result());
  }

  @Test
  void convertMessagesEmitsThoughtStepsBeforeFunctionCalls() {
    var model = createModel();
    var sigs = "sig-a" + GeminiModel.SIGNATURE_DELIMITER + "sig-b";
    var msgWithSigs =
        Message.assistant(
            null,
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("search")
                    .withArguments(Map.of("q", "x"))
                    .build()),
            Map.of(GeminiModel.THOUGHT_SIGNATURES_KEY, sigs));
    var messages = List.of(Message.user("Hi"), msgWithSigs, Message.tool("c1", "search", "ok"));

    var converted = model.convertMessages(messages);

    assertEquals(5, converted.steps().size());
    assertTrue(converted.steps().get(0).hasTypeUserInput());
    assertTrue(converted.steps().get(1).hasTypeThought());
    assertEquals("sig-a", converted.steps().get(1).signature());
    assertTrue(converted.steps().get(2).hasTypeThought());
    assertEquals("sig-b", converted.steps().get(2).signature());
    assertTrue(converted.steps().get(3).hasTypeFunctionCall());
    assertTrue(converted.steps().get(4).hasTypeFunctionResult());
  }

  @Test
  void convertMessagesPlainAssistantBecomesModelOutput() {
    var model = createModel();
    var messages = List.of(Message.user("Hi"), Message.assistant("There"));

    var converted = model.convertMessages(messages);

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

    var converted = model.convertMessages(List.of(user));

    assertEquals(1, converted.steps().size());
    var step = converted.steps().getFirst();
    assertTrue(step.hasTypeUserInput());
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

    var converted = model.convertMessages(List.of(user));

    var step = converted.steps().getFirst();
    var doc = step.content().get(0);
    assertEquals("document", doc.type());
    assertEquals("application/pdf", doc.mimeType());
    assertNotNull(doc.data());
  }

  @Test
  void convertMessagesExtractsSystemInstruction() {
    var model = createModel();
    var messages = List.of(Message.system("Be helpful"), Message.user("Hi"));

    var converted = model.convertMessages(messages);

    assertEquals("Be helpful", converted.systemInstruction());
    assertEquals(1, converted.steps().size());
    assertTrue(converted.steps().getFirst().hasTypeUserInput());
  }

  @Test
  void convertMessagesNoSystemInstruction() {
    var model = createModel();
    var messages = List.of(Message.user("Hi"));

    var converted = model.convertMessages(messages);

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

    var converted = model.convertMessages(messages);

    assertEquals("You are an analyst", converted.systemInstruction());
    var steps = converted.steps();
    assertEquals(10, steps.size());
    assertTrue(steps.get(0).hasTypeUserInput());
    assertTrue(steps.get(1).hasTypeFunctionCall());
    assertEquals("c1", steps.get(1).id());
    assertTrue(steps.get(2).hasTypeFunctionCall());
    assertEquals("c2", steps.get(2).id());
    assertTrue(steps.get(3).hasTypeFunctionResult());
    assertEquals("c1", steps.get(3).callId());
    assertTrue(steps.get(4).hasTypeFunctionResult());
    assertEquals("c2", steps.get(4).callId());
    assertTrue(steps.get(5).hasTypeModelOutput());
    assertTrue(steps.get(6).hasTypeUserInput());
    assertTrue(steps.get(7).hasTypeFunctionCall());
    assertEquals("c3", steps.get(7).id());
    assertTrue(steps.get(8).hasTypeFunctionResult());
    assertTrue(steps.get(9).hasTypeModelOutput());
  }

  // --- Continuation mode tests ---

  @Test
  void findContinuationPointReturnsNullWhenNoInteractionId() {
    var messages = List.of(Message.user("Hello"), Message.assistant("Hi"));

    var result = GeminiModel.findContinuationPoint(messages);

    assertNull(result);
  }

  @Test
  void findContinuationPointFindsLastAssistantWithId() {
    var metadata = Map.of(GeminiModel.INTERACTION_ID_KEY, "interaction-123");
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant("Hi", List.of(), metadata),
            Message.user("Follow-up"));

    var result = GeminiModel.findContinuationPoint(messages);

    assertNotNull(result);
    assertEquals("interaction-123", result.interactionId());
    assertEquals(2, result.startIndex());
  }

  @Test
  void findContinuationPointPicksLastOfMultipleAssistants() {
    var meta1 = Map.of(GeminiModel.INTERACTION_ID_KEY, "interaction-1");
    var meta2 = Map.of(GeminiModel.INTERACTION_ID_KEY, "interaction-2");
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant("First", List.of(), meta1),
            Message.user("More"),
            Message.assistant("Second", List.of(), meta2),
            Message.tool("c1", "search", "result"));

    var result = GeminiModel.findContinuationPoint(messages);

    assertNotNull(result);
    assertEquals("interaction-2", result.interactionId());
    assertEquals(4, result.startIndex());
  }

  @Test
  void findContinuationPointSkipsAssistantWithEmptyId() {
    var metadata = Map.of(GeminiModel.INTERACTION_ID_KEY, "");
    var messages = List.of(Message.user("Hello"), Message.assistant("Hi", List.of(), metadata));

    var result = GeminiModel.findContinuationPoint(messages);

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
    var metadata = Map.of(GeminiModel.INTERACTION_ID_KEY, "interaction-456");
    var messages =
        List.of(
            Message.user("Search for something"),
            Message.assistant(null, toolCalls, metadata),
            Message.tool("c1", "search", "found it"));

    var result = GeminiModel.findContinuationPoint(messages);

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

    var steps = GeminiModel.buildContinuationSteps(messages, 2);

    assertEquals(1, steps.size());
    assertTrue(steps.getFirst().hasTypeFunctionResult());
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

    var steps = GeminiModel.buildContinuationSteps(messages, 2);

    assertEquals(2, steps.size());
    assertTrue(steps.get(0).hasTypeFunctionResult());
    assertEquals("c1", steps.get(0).callId());
    assertEquals("AAPL: $228", steps.get(0).result());
    assertTrue(steps.get(1).hasTypeFunctionResult());
    assertEquals("c2", steps.get(1).callId());
    assertEquals("NVDA: $480", steps.get(1).result());
  }

  @Test
  void buildContinuationStepsUserMessage() {
    var messages =
        List.of(Message.user("Hello"), Message.assistant("Hi"), Message.user("Follow-up"));

    var steps = GeminiModel.buildContinuationSteps(messages, 2);

    assertEquals(1, steps.size());
    assertTrue(steps.getFirst().hasTypeUserInput());
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

    var steps = GeminiModel.buildContinuationSteps(messages, 3);

    assertEquals(1, steps.size());
    assertTrue(steps.getFirst().hasTypeUserInput());
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

    var steps = GeminiModel.buildContinuationSteps(messages, 2);

    assertEquals(2, steps.size());
    assertTrue(steps.get(0).hasTypeFunctionResult());
    assertEquals("c1", steps.get(0).callId());
    assertTrue(steps.get(1).hasTypeUserInput());
    assertEquals("Thanks, now search more", steps.get(1).content().getFirst().text());
  }

  @Test
  void findContinuationPointSkipsAssistantWithNullMetadata() {
    var msg = new Message(Role.ASSISTANT, "Hi", List.of(), null, null, null, List.of());
    var messages = List.of(Message.user("Hello"), msg);

    var result = GeminiModel.findContinuationPoint(messages);

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

    var steps = GeminiModel.buildContinuationSteps(messages, 1);

    assertEquals(1, steps.size());
    assertTrue(steps.getFirst().hasTypeUserInput());
    assertEquals("Follow-up", steps.getFirst().content().getFirst().text());
  }

  @Test
  void interactionIdKeyConstant() {
    assertEquals("gemini.interactionId", GeminiModel.INTERACTION_ID_KEY);
  }

  @Test
  void continuationPointRecord() {
    var point = new GeminiModel.ContinuationPoint("id-123", 5);
    assertEquals("id-123", point.interactionId());
    assertEquals(5, point.startIndex());
  }

  @Test
  void buildRequestContinuationIncludesSystemInstruction() {
    var model = createModel();
    var metadata = Map.of(GeminiModel.INTERACTION_ID_KEY, "int-abc");
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

    var request = model.buildRequest(messages, null, null);

    assertNotNull(request.previousInteractionId(), "continuation must use previous_interaction_id");
    assertEquals("int-abc", request.previousInteractionId());
    assertEquals(
        "You are a helpful assistant",
        request.systemInstruction(),
        "system instruction must be included on continuation requests");
  }

  @Test
  void buildRequestThinkingLevelMinimalMapsToMinimal() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ai.singlr.core.model.ThinkingLevel.MINIMAL)
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
    var messages = List.of(Message.user("Hello"));

    var request = model.buildRequest(messages, null, null);

    assertEquals("minimal", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelLowMapsToLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ai.singlr.core.model.ThinkingLevel.LOW)
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
    var messages = List.of(Message.user("Hello"));

    var request = model.buildRequest(messages, null, null);

    assertEquals("low", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelHighMapsToHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ai.singlr.core.model.ThinkingLevel.HIGH)
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
    var messages = List.of(Message.user("Hello"));

    var request = model.buildRequest(messages, null, null);

    assertEquals("high", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelMediumMapsToMedium() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ai.singlr.core.model.ThinkingLevel.MEDIUM)
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
    var request = model.buildRequest(List.of(Message.user("Hello")), null, null);

    assertEquals("medium", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelXhighClampsToHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ai.singlr.core.model.ThinkingLevel.XHIGH)
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
    var request = model.buildRequest(List.of(Message.user("Hello")), null, null);

    assertEquals("high", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelMaxClampsToHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ai.singlr.core.model.ThinkingLevel.MAX)
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
    var request = model.buildRequest(List.of(Message.user("Hello")), null, null);

    assertEquals("high", request.generationConfig().thinkingLevel());
  }

  @Test
  void buildRequestThinkingLevelNoneOmitsField() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ai.singlr.core.model.ThinkingLevel.NONE)
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
    var messages = List.of(Message.user("Hello"));

    var request = model.buildRequest(messages, null, null);

    assertNull(request.generationConfig().thinkingLevel());
  }

  @Test
  void extractSystemInstructionFindsSystemMessage() {
    var messages = List.of(Message.system("Be helpful"), Message.user("Hi"));
    assertEquals("Be helpful", GeminiModel.extractSystemInstruction(messages));
  }

  @Test
  void extractSystemInstructionReturnsNullWhenAbsent() {
    var messages = List.of(Message.user("Hi"));
    assertNull(GeminiModel.extractSystemInstruction(messages));
  }

  @Test
  void extractSystemInstructionTakesLastSystemMessage() {
    var messages = List.of(Message.system("First"), Message.user("Hi"), Message.system("Second"));
    assertEquals("Second", GeminiModel.extractSystemInstruction(messages));
  }

  @Test
  void buildRequestContinuationWithoutSystemMessageOmitsIt() {
    var model = createModel();
    var metadata = Map.of(GeminiModel.INTERACTION_ID_KEY, "int-abc");
    var messages =
        List.of(
            Message.user("Search for X"),
            Message.assistant("ok", List.of(), metadata),
            Message.user("Follow-up"));

    var request = model.buildRequest(messages, null, null);

    assertNotNull(request.previousInteractionId());
    assertNull(request.systemInstruction());
  }

  @Test
  void closeReleasesHttpClientResources() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    model.close();
  }

  @Test
  void closeIsIdempotent() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    model.close();
    model.close();
    model.close();
  }

  @Test
  void modelUsableInTryWithResources() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    try (var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config)) {
      assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), model.id());
    }
  }

  public record TestPerson(String name, int age) {}

  @Test
  void parseStructuredContentPlainSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    var result =
        model.parseStructuredContent(
            "{\"name\":\"Alice\",\"age\":30}", OutputSchema.of(TestPerson.class));
    assertEquals("Alice", result.name());
    assertEquals(30, result.age());
  }

  @Test
  void parseStructuredContentProvenancedReconstructsTypedOutput() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
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
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertNull(model.parseStructuredContent(null, OutputSchema.of(TestPerson.class)));
  }

  @Test
  void parseStructuredContentSchemaMismatchSurfacesFieldLevelDiff() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
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
  void parseStructuredContentSyntaxErrorThrowsStructuredOutputParseException() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
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

  @Test
  void buildHttpRequestUsesDefaultsWhenBaseUrlAndHeadersUnset() {
    var config = ModelConfig.newBuilder().withApiKey("g-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals(
        java.net.URI.create(
            "https://generativelanguage.googleapis.com/v1beta/interactions?alt=sse"),
        httpRequest.uri());
    assertEquals("g-key", httpRequest.headers().firstValue("x-goog-api-key").orElseThrow());
  }

  @Test
  void buildHttpRequestUsesConfiguredBaseUrl() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("g-key")
            .withBaseUrl("https://vertex.example/v1beta")
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals(
        java.net.URI.create("https://vertex.example/v1beta/interactions?alt=sse"),
        httpRequest.uri(),
        "configured baseUrl replaces the default host+/v1beta prefix; provider keeps appending its own /interactions?alt=sse");
  }

  @Test
  void buildHttpRequestUserHeaderReplacesBuiltinByCaseInsensitiveName() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("default-key")
            .withHeader("X-GOOG-API-KEY", "override-key")
            .build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals("override-key", httpRequest.headers().firstValue("x-goog-api-key").orElseThrow());
    assertEquals(
        1,
        httpRequest.headers().allValues("x-goog-api-key").size(),
        "name match must replace the default rather than append a second header line");
  }

  @Test
  void buildHttpRequestExtraHeaderIsAppended() {
    var config = ModelConfig.newBuilder().withApiKey("g-key").withHeader("x-trace", "t1").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    var httpRequest = model.buildHttpRequest("{}");
    assertEquals("t1", httpRequest.headers().firstValue("x-trace").orElseThrow());
    assertEquals("g-key", httpRequest.headers().firstValue("x-goog-api-key").orElseThrow());
  }
}
