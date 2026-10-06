/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ThinkingLevel;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.openai.OpenAIModelId.EffortSupport;
import com.standardapplied.helios.openai.api.ApiUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAIRestructuredPartsTest {

  @Test
  void aUserTurnWithoutTextSendsOnlyItsFiles() {
    var message =
        new Message(
            Role.USER,
            null,
            List.of(),
            null,
            null,
            Map.of(),
            List.of(InlineFile.of(new byte[] {1}, "application/pdf")));

    var item = OpenAIInput.user(message);

    assertEquals(1, ((List<?>) item.content()).size());
  }

  @Test
  void aToolCallWithoutArgumentsSendsAnEmptyObject() {
    var message = Message.assistant(List.of(new ToolCall("c1", "search", null)));

    assertEquals("{}", OpenAIInput.assistant(message).getFirst().arguments());
  }

  @Test
  void toolCallArgumentsThatCannotBeWrittenFailTheRequest() {
    var call =
        ToolCall.newBuilder()
            .withId("c1")
            .withName("search")
            .withArguments(ConversationFixture.selfReferencing())
            .build();

    var failure =
        assertThrows(
            OpenAIException.class, () -> OpenAIInput.assistant(Message.assistant(List.of(call))));

    assertEquals("Failed to serialize tool call arguments", failure.getMessage());
  }

  @Test
  void aToolThatCannotBeWrittenFailsTheCallBeforeItIsSent() {
    var model =
        new OpenAIModel(
            OpenAIModelId.GPT_5_6,
            ModelConfig.newBuilder().withApiKey("k").withBaseUrl("http://127.0.0.1:1").build());
    var tool = ConversationFixture.unwritableTool();

    var failure =
        assertThrows(
            OpenAIException.class, () -> model.chat(List.of(Message.user("hi")), List.of(tool)));

    assertEquals("Failed to serialize request", failure.getMessage());
  }

  @Test
  void anUnrecognisedModelIsStandardWithoutKnownLimits() {
    var model = new OpenAIModel("gpt-next", ModelConfig.newBuilder().withApiKey("k").build());

    var request = model.requests.build(List.of(Message.user("hi")), List.of(), null);

    assertNull(request.reasoning());
    assertEquals(0, request.maxOutputTokens());
    assertEquals(0, model.contextWindow());
    assertEquals(0, model.maxOutputTokens());
  }

  @Test
  void anUnsetReasoningLevelIsTheModelsLowestEffort() {
    assertEquals("none", OpenAIReasoning.of(EffortSupport.FULL, null).effort());
    assertEquals("low", OpenAIReasoning.of(EffortSupport.FULL_WITHOUT_NONE, null).effort());
    assertNull(OpenAIReasoning.of(EffortSupport.STANDARD, ThinkingLevel.NONE));
  }

  @Test
  void aReasoningSummaryThatEndsEmptyYieldsNoCompletion() {
    var parser = new OpenAIStreamParser();

    assertNull(parser.parse("{\"type\":\"response.reasoning_summary_text.done\"}"));
  }

  @Test
  void usageCountsAnyReportedTokenClassAndNothingWhenAllAreZero() {
    assertNull(usage(new ApiUsage(0, 0, 0)));
    assertEquals(Response.Usage.of(0, 4, 0, 0), usage(new ApiUsage(null, 4, 4)));
    assertEquals(
        Response.Usage.of(0, 0, 0, 3),
        usage(new ApiUsage(0, 0, 0, new ApiUsage.InputTokensDetails(3, null), null)));
    assertEquals(
        Response.Usage.of(0, 0, 2, 0),
        usage(new ApiUsage(0, 0, 0, new ApiUsage.InputTokensDetails(null, 2), null)));
  }

  @Test
  void anArraySchemaWithoutAnItemSchemaAndScalarPropertiesStayAsTheyAre() {
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("tags", Map.of("type", "array", "items", true), "flag", true));

    var strict = StrictSchema.addAdditionalPropertiesFalse(schema);

    var properties = (Map<?, ?>) strict.get("properties");
    assertEquals(true, properties.get("flag"));
    assertEquals(Map.of("type", "array", "items", true), properties.get("tags"));
  }

  private static Response.Usage usage(ApiUsage usage) {
    var done =
        assertInstanceOf(
            StreamEvent.Done.class,
            OpenAIResponseAssembler.done("", List.of(), "", usage, "completed"));
    return done.response().usage();
  }
}
