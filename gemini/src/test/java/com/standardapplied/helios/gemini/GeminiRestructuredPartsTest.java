/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.FileReference;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.gemini.api.ContentItem;
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.Step;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiRestructuredPartsTest {

  @Test
  void eachToolChoiceModeReachesTheGenerationConfig() {
    for (var choice : List.of(ToolChoice.auto(), ToolChoice.any(), ToolChoice.none())) {
      var requests =
          new GeminiRequestBuilder(
              GeminiModelId.GEMINI_3_5_FLASH,
              ModelConfig.newBuilder().withApiKey("k").withToolChoice(choice).build());

      var config = requests.build(List.of(Message.user("hi")), List.of(), null).generationConfig();

      assertEquals(
          choice.getClass().getSimpleName().toLowerCase(Locale.ROOT), config.toolChoice().mode());
    }
  }

  @Test
  void theModelReportsItsCatalogCeilingAndCapturePolicy() {
    var model =
        new GeminiProvider()
            .create(
                GeminiModelId.GEMINI_3_5_FLASH.id(),
                ModelConfig.newBuilder()
                    .withApiKey("k")
                    .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
                    .build());

    assertEquals(GeminiModelId.GEMINI_3_5_FLASH.maxOutputTokens(), model.maxOutputTokens());
    assertEquals(RawOutputCapturePolicy.DISABLED, model.rawOutputCapturePolicy());
  }

  @Test
  void aUserTurnWithOnlyFileReferencesSendsNoText() {
    var message =
        Message.newBuilder()
            .withRole(Role.USER)
            .withFileReferences(List.of(FileReference.of("https://files.example/v", "video/mp4")))
            .build();

    var step = GeminiConversation.of(List.of(message)).steps().getFirst();

    assertEquals(1, step.content().size());
    assertEquals("video", step.content().getFirst().type());
  }

  @Test
  void aKeylessRequestToACustomEndpointCarriesNoApiKeyHeader() {
    var config = ModelConfig.newBuilder().withBaseUrl("http://gateway.local/v1beta").build();
    var streams =
        new GeminiStreams(config, HttpClientFactory.create(config), GeminiEndpoint.of(config));

    assertTrue(streams.httpRequest("{}").headers().firstValue("x-goog-api-key").isEmpty());
  }

  @Test
  void aToolThatCannotBeWrittenFailsTheCallBeforeItIsSent() {
    var tool = ConversationFixture.unwritableTool();
    var model =
        new GeminiProvider()
            .create(
                GeminiModelId.GEMINI_3_5_FLASH.id(),
                ModelConfig.newBuilder()
                    .withApiKey("k")
                    .withBaseUrl("http://127.0.0.1:1/v1beta")
                    .build());

    var error =
        assertThrows(
            GeminiException.class, () -> model.chat(List.of(Message.user("hi")), List.of(tool)));

    assertEquals("Failed to serialize request", error.getMessage());
  }

  @Test
  void anErrorEventWithoutAMessageOrNumericCodeIsAPlainApiError() {
    var parser = new GeminiStreamParser(true, "v1");

    var error =
        assertInstanceOf(
            StreamEvent.Error.class,
            parser.parse("{\"event_type\":\"error\",\"error\":{\"code\":\"UNAVAILABLE\"}}"));

    assertEquals("API error", error.message());
    assertEquals(0, ((GeminiException) error.cause()).statusCode());
  }

  @Test
  void anErrorEventWithANonPositiveCodeIsARetryableFailureWithoutAStatus() {
    var parser = new GeminiStreamParser(true, "v1");

    var error =
        assertInstanceOf(
            StreamEvent.Error.class,
            parser.parse(
                "{\"event_type\":\"error\",\"error\":{\"code\":-1,\"message\":\"boom\"}}"));

    var cause = (GeminiException) error.cause();
    assertEquals("API error: boom", error.message());
    assertEquals(0, cause.statusCode());
    assertTrue(cause.isRetryable());
  }

  @Test
  void argumentsDeltasOutsideAFunctionCallOnlyHarvestTheirAnnotations() {
    var steps = new StreamedSteps();
    steps.start(0, Step.modelOutput("x"));
    steps.start(1, functionCall());

    assertNull(steps.delta(0, null, item("arguments_delta", null, "{\"a\":1}")));
    assertNull(steps.delta(1, null, item("arguments_delta", null, null)));
    assertNull(steps.delta(9, null, item("arguments_delta", null, "{}")));

    var call = assertInstanceOf(StreamEvent.ToolCallComplete.class, steps.stop(1)).toolCall();
    assertEquals(Map.of(), call.arguments());
  }

  @Test
  void aModelOutputThatStartsWithoutTextYieldsNoDelta() {
    var steps = new StreamedSteps();
    var start =
        new Step(
            "model_output",
            List.of(item("image", null, null), item("text", "", null), item("text", null, null)),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertNull(steps.start(0, start));
    assertEquals("", steps.result().content());
  }

  @Test
  void aThoughtSignatureWithoutASignatureAndAnEmptyThoughtYieldNothing() {
    var steps = new StreamedSteps();
    steps.start(0, new Step("thought", null, null, null, null, null, null, null, null, null, null));

    assertNull(steps.delta(0, null, item("thought_signature", null, null)));
    assertEquals(new StreamEvent.ThinkingDelta(""), steps.delta(0, null, item("text", "", null)));
    assertNull(steps.stop(0));
    assertTrue(steps.result().signatures().isEmpty());
  }

  @Test
  void functionArgumentsArriveAsAnObjectOrAJsonStringOrNotAtAll() {
    assertEquals(Map.of("a", 1), stepArguments("{\"a\":1}"));
    assertEquals(Map.of("a", 1), stepArguments("\"{\\\"a\\\":1}\""));
    assertEquals(Map.of(), stepArguments("\" \""));
    assertNull(stepArguments("null"));
    assertNull(stepArguments("42"));
  }

  @Test
  void rawArgumentsKeepAStringAndWriteAnObjectAsJson() {
    assertEquals("{\"a\"", itemArguments("\"{\\\"a\\\"\""));
    assertEquals("{\"a\":1}", itemArguments("{\"a\":1}"));
    assertNull(itemArguments("null"));
  }

  private static Map<String, Object> stepArguments(String json) {
    return GeminiJson.LENIENT
        .readValue("{\"type\":\"function_call\",\"arguments\":" + json + "}", Step.class)
        .arguments();
  }

  private static String itemArguments(String json) {
    return GeminiJson.LENIENT
        .readValue("{\"type\":\"arguments_delta\",\"arguments\":" + json + "}", ContentItem.class)
        .arguments();
  }

  private static Step functionCall() {
    return new Step(
        "function_call", null, null, null, "c1", "search", null, null, null, null, null);
  }

  private static ContentItem item(String type, String text, String arguments) {
    return new ContentItem(type, text, null, null, null, null, null, arguments);
  }
}
