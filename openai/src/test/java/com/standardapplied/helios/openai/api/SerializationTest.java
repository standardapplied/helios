/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SerializationTest {

  private final ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void serializeSimpleRequest() throws Exception {
    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Hello")))
            .withStream(true)
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"model\":\"gpt-4o\""));
    assertTrue(json.contains("\"Hello\""));
    assertTrue(json.contains("\"stream\":true"));
    assertFalse(json.contains("\"instructions\""));
  }

  @Test
  void serializeRequestWithInstructions() throws Exception {
    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Hello")))
            .withInstructions("You are helpful")
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"instructions\":\"You are helpful\""));
  }

  @Test
  void serializeInputItemUserMessage() throws Exception {
    var item = InputItem.userMessage("Hello world");
    var json = objectMapper.writeValueAsString(item);
    assertTrue(json.contains("\"type\":\"message\""));
    assertTrue(json.contains("\"role\":\"user\""));
    assertTrue(json.contains("\"content\":\"Hello world\""));
    assertFalse(json.contains("\"call_id\""));
  }

  @Test
  void serializeInputItemAssistantMessage() throws Exception {
    var item = InputItem.assistantMessage("I can help");
    var json = objectMapper.writeValueAsString(item);
    assertTrue(json.contains("\"type\":\"message\""));
    assertTrue(json.contains("\"role\":\"assistant\""));
    assertTrue(json.contains("\"output_text\""));
  }

  @Test
  void serializeInputItemFunctionCall() throws Exception {
    var item = InputItem.functionCall("call_1", "get_weather", "{\"city\":\"NYC\"}");
    var json = objectMapper.writeValueAsString(item);
    assertTrue(json.contains("\"type\":\"function_call\""));
    assertTrue(json.contains("\"call_id\":\"call_1\""));
    assertTrue(json.contains("\"name\":\"get_weather\""));
    assertTrue(json.contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\""));
    assertFalse(json.contains("\"role\""));
  }

  @Test
  void serializeInputItemFunctionCallOutput() throws Exception {
    var item = InputItem.functionCallOutput("call_1", "Weather is sunny");
    var json = objectMapper.writeValueAsString(item);
    assertTrue(json.contains("\"type\":\"function_call_output\""));
    assertTrue(json.contains("\"call_id\":\"call_1\""));
    assertTrue(json.contains("\"output\":\"Weather is sunny\""));
    assertFalse(json.contains("\"role\""));
    assertFalse(json.contains("\"name\""));
  }

  @Test
  void serializeToolDefinition() throws Exception {
    var tool =
        Tool.newBuilder()
            .withName("search_people")
            .withDescription("Finds people using semantic search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Search query")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("[]"))
            .build();

    var toolDef =
        ToolDefinition.function(tool.name(), tool.description(), tool.parametersAsJsonSchema());
    var json = objectMapper.writeValueAsString(toolDef);
    assertTrue(json.contains("\"type\":\"function\""));
    assertTrue(json.contains("\"name\":\"search_people\""));
    assertTrue(json.contains("\"description\":\"Finds people using semantic search\""));
    assertTrue(json.contains("\"parameters\""));
    assertTrue(json.contains("\"type\":\"object\""));
    assertTrue(json.contains("\"query\""));
  }

  @Test
  void serializeTextFormatJsonSchema() throws Exception {
    var format =
        TextFormatConfig.jsonSchema(
            "output",
            Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))));
    var json = objectMapper.writeValueAsString(format);
    assertTrue(json.contains("\"type\":\"json_schema\""));
    assertTrue(json.contains("\"name\":\"output\""));
    assertTrue(json.contains("\"strict\":true"));
    assertTrue(json.contains("\"schema\""));
  }

  @Test
  void serializeTextFormatText() throws Exception {
    var format = TextFormatConfig.text();
    var json = objectMapper.writeValueAsString(format);
    assertTrue(json.contains("\"type\":\"text\""));
    assertFalse(json.contains("\"name\""));
    assertFalse(json.contains("\"schema\""));
    assertFalse(json.contains("\"strict\""));
  }

  @Test
  void serializeContentPartOutputText() throws Exception {
    var part = ContentPart.outputText("Hello");
    var json = objectMapper.writeValueAsString(part);
    assertTrue(json.contains("\"type\":\"output_text\""));
    assertTrue(json.contains("\"text\":\"Hello\""));
  }

  @Test
  void serializeContentPartInputText() throws Exception {
    var part = ContentPart.inputText("User said");
    var json = objectMapper.writeValueAsString(part);
    assertTrue(json.contains("\"type\":\"input_text\""));
    assertTrue(json.contains("\"text\":\"User said\""));
  }

  @Test
  void serializeContentPartRefusal() throws Exception {
    var part = ContentPart.refusal("I cannot do that");
    var json = objectMapper.writeValueAsString(part);
    assertTrue(json.contains("\"type\":\"refusal\""));
    assertTrue(json.contains("\"text\":\"I cannot do that\""));
  }

  @Test
  void serializeRequestWithTools() throws Exception {
    var searchTool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("Search for items")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Search query")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("[]"))
            .build();

    var toolDefs =
        List.of(
            ToolDefinition.function(
                searchTool.name(), searchTool.description(), searchTool.parametersAsJsonSchema()));

    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Find things")))
            .withTools(toolDefs)
            .withToolChoice("auto")
            .withStream(true)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"search\""));
    assertTrue(json.contains("\"parameters\""));
    assertTrue(json.contains("\"tool_choice\":\"auto\""));
  }

  @Test
  void serializeRequestWithReasoning() throws Exception {
    var request =
        ResponsesRequest.newBuilder()
            .withModel("o3")
            .withInput(List.of(InputItem.userMessage("Think hard")))
            .withReasoning(ResponsesRequest.ReasoningConfig.of("high"))
            .withStream(true)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"reasoning\""));
    assertTrue(json.contains("\"effort\":\"high\""));
  }

  @Test
  void serializeRequestWithGenerationParams() throws Exception {
    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Hi")))
            .withTemperature(0.7)
            .withTopP(0.9)
            .withMaxOutputTokens(8192)
            .withStop(List.of("END", "STOP"))
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"temperature\":0.7"));
    assertTrue(json.contains("\"top_p\":0.9"));
    assertTrue(json.contains("\"max_output_tokens\":8192"));
    assertTrue(json.contains("\"stop\""));
    assertTrue(json.contains("\"END\""));
    assertTrue(json.contains("\"STOP\""));
  }

  @Test
  void serializeRequestWithTextFormat() throws Exception {
    var format = TextFormatConfig.jsonSchema("output", Map.of("type", "object"));
    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Extract")))
            .withText(new ResponsesRequest.TextConfig(format))
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"text\""));
    assertTrue(json.contains("\"format\""));
    assertTrue(json.contains("\"json_schema\""));
  }

  @Test
  void deserializeResponsesResponse() throws Exception {
    var json =
        """
        {
          "id": "resp_123",
          "object": "response",
          "status": "completed",
          "output": [
            {
              "type": "message",
              "id": "msg_1",
              "role": "assistant",
              "content": [
                {"type": "output_text", "text": "Hello!"}
              ],
              "status": "completed"
            }
          ],
          "model": "gpt-4o",
          "usage": {
            "input_tokens": 10,
            "output_tokens": 25,
            "total_tokens": 35
          }
        }
        """;

    var response = objectMapper.readValue(json, ResponsesResponse.class);

    assertEquals("resp_123", response.id());
    assertEquals("response", response.object());
    assertEquals("completed", response.status());
    assertEquals(1, response.output().size());
    assertTrue(response.output().getFirst().hasTypeMessage());
    assertEquals("assistant", response.output().getFirst().role());
    assertEquals(1, response.output().getFirst().content().size());
    assertTrue(response.output().getFirst().content().getFirst().hasTypeOutputText());
    assertEquals("Hello!", response.output().getFirst().content().getFirst().text());
    assertNotNull(response.usage());
    assertEquals(10, response.usage().inputTokens());
    assertEquals(25, response.usage().outputTokens());
    assertEquals(35, response.usage().totalTokens());
  }

  @Test
  void deserializeResponseWithFunctionCall() throws Exception {
    var json =
        """
        {
          "id": "resp_456",
          "object": "response",
          "status": "completed",
          "output": [
            {
              "type": "function_call",
              "id": "fc_1",
              "call_id": "call_1",
              "name": "get_weather",
              "arguments": "{\\"city\\":\\"NYC\\"}",
              "status": "completed"
            }
          ],
          "model": "gpt-4o",
          "usage": {"input_tokens": 20, "output_tokens": 50, "total_tokens": 70}
        }
        """;

    var response = objectMapper.readValue(json, ResponsesResponse.class);

    assertEquals(1, response.output().size());
    assertTrue(response.output().getFirst().hasTypeFunctionCall());
    assertEquals("fc_1", response.output().getFirst().id());
    assertEquals("call_1", response.output().getFirst().callId());
    assertEquals("get_weather", response.output().getFirst().name());
    assertEquals("{\"city\":\"NYC\"}", response.output().getFirst().arguments());
  }

  @Test
  void deserializeApiStreamEventTextDelta() throws Exception {
    var json =
        """
        {
          "type": "response.output_text.delta",
          "output_index": 0,
          "content_index": 0,
          "delta": "Hello"
        }
        """;

    var event = objectMapper.readValue(json, ApiStreamEvent.class);

    assertTrue(event.hasTypeResponseOutputTextDelta());
    assertEquals(0, event.outputIndex());
    assertEquals(0, event.contentIndex());
    assertEquals("Hello", event.delta());
  }

  @Test
  void deserializeApiStreamEventOutputItemAdded() throws Exception {
    var json =
        """
        {
          "type": "response.output_item.added",
          "output_index": 0,
          "item": {
            "type": "function_call",
            "id": "fc_1",
            "call_id": "call_1",
            "name": "search",
            "arguments": "",
            "status": "in_progress"
          }
        }
        """;

    var event = objectMapper.readValue(json, ApiStreamEvent.class);

    assertTrue(event.hasTypeResponseOutputItemAdded());
    assertNotNull(event.item());
    assertTrue(event.item().hasTypeFunctionCall());
    assertEquals("fc_1", event.item().id());
    assertEquals("call_1", event.item().callId());
    assertEquals("search", event.item().name());
  }

  @Test
  void deserializeApiStreamEventResponseCompleted() throws Exception {
    var json =
        """
        {
          "type": "response.completed",
          "response": {
            "id": "resp_1",
            "object": "response",
            "status": "completed",
            "output": [],
            "model": "gpt-4o",
            "usage": {"input_tokens": 25, "output_tokens": 15, "total_tokens": 40}
          }
        }
        """;

    var event = objectMapper.readValue(json, ApiStreamEvent.class);

    assertTrue(event.hasTypeResponseCompleted());
    assertNotNull(event.response());
    assertEquals("resp_1", event.response().id());
    assertEquals("completed", event.response().status());
    assertEquals(25, event.response().usage().inputTokens());
  }

  @Test
  void deserializeApiUsage() throws Exception {
    var json =
        """
        {
          "input_tokens": 100,
          "output_tokens": 50,
          "total_tokens": 150
        }
        """;

    var usage = objectMapper.readValue(json, ApiUsage.class);

    assertEquals(100, usage.inputTokens());
    assertEquals(50, usage.outputTokens());
    assertEquals(150, usage.totalTokens());
  }

  @Test
  void inputItemHelperMethods() {
    var user = InputItem.userMessage("hi");
    assertTrue(user.hasTypeMessage());
    assertFalse(user.hasTypeFunctionCall());
    assertFalse(user.hasTypeFunctionCallOutput());

    var fc = InputItem.functionCall("c1", "fn", "{}");
    assertFalse(fc.hasTypeMessage());
    assertTrue(fc.hasTypeFunctionCall());

    var fco = InputItem.functionCallOutput("c1", "result");
    assertTrue(fco.hasTypeFunctionCallOutput());
  }

  @Test
  void outputItemHelperMethods() throws Exception {
    var msgJson =
        """
        {"type": "message", "id": "msg_1", "role": "assistant", "content": [], "status": "completed"}
        """;
    var msg = objectMapper.readValue(msgJson, OutputItem.class);
    assertTrue(msg.hasTypeMessage());
    assertFalse(msg.hasTypeFunctionCall());
    assertFalse(msg.hasTypeReasoning());

    var fcJson =
        """
        {"type": "function_call", "id": "fc_1", "call_id": "c1", "name": "fn", "arguments": "{}", "status": "completed"}
        """;
    var fc = objectMapper.readValue(fcJson, OutputItem.class);
    assertTrue(fc.hasTypeFunctionCall());

    var reasoningJson =
        """
        {"type": "reasoning", "id": "rs_1", "summary": [{"type": "summary_text", "text": "thinking"}]}
        """;
    var reasoning = objectMapper.readValue(reasoningJson, OutputItem.class);
    assertTrue(reasoning.hasTypeReasoning());
    assertNotNull(reasoning.summary());
    assertEquals("thinking", reasoning.summary().getFirst().text());
  }

  @Test
  void contentPartHelperMethods() {
    var outputText = ContentPart.outputText("hello");
    assertTrue(outputText.hasTypeOutputText());
    assertFalse(outputText.hasTypeInputText());
    assertFalse(outputText.hasTypeRefusal());

    var inputText = ContentPart.inputText("user");
    assertTrue(inputText.hasTypeInputText());
    assertFalse(inputText.hasTypeOutputText());

    var refusal = ContentPart.refusal("nope");
    assertTrue(refusal.hasTypeRefusal());
  }

  @Test
  void apiStreamEventHelperMethods() throws Exception {
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.output_text.delta\"}", ApiStreamEvent.class)
            .hasTypeResponseOutputTextDelta());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.output_item.added\"}", ApiStreamEvent.class)
            .hasTypeResponseOutputItemAdded());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.output_item.done\"}", ApiStreamEvent.class)
            .hasTypeResponseOutputItemDone());
    assertTrue(
        objectMapper
            .readValue(
                "{\"type\":\"response.function_call_arguments.delta\"}", ApiStreamEvent.class)
            .hasTypeFunctionCallArgumentsDelta());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.function_call_arguments.done\"}", ApiStreamEvent.class)
            .hasTypeFunctionCallArgumentsDone());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.completed\"}", ApiStreamEvent.class)
            .hasTypeResponseCompleted());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.failed\"}", ApiStreamEvent.class)
            .hasTypeResponseFailed());
    assertTrue(objectMapper.readValue("{\"type\":\"error\"}", ApiStreamEvent.class).hasTypeError());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.reasoning_summary_text.delta\"}", ApiStreamEvent.class)
            .hasTypeReasoningSummaryTextDelta());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.content_part.added\"}", ApiStreamEvent.class)
            .hasTypeContentPartAdded());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"response.content_part.done\"}", ApiStreamEvent.class)
            .hasTypeContentPartDone());
  }

  @Test
  void serializeFunctionCallRoundTrip() throws Exception {
    var input = new java.util.ArrayList<InputItem>();
    input.add(InputItem.userMessage("Find people"));
    input.add(InputItem.assistantMessage("I'll search for that"));
    input.add(InputItem.functionCall("call_1", "search", "{\"query\":\"engineers\"}"));
    input.add(InputItem.functionCallOutput("call_1", "Found 3 engineers"));

    var request = ResponsesRequest.newBuilder().withModel("gpt-4o").withInput(input).build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"type\":\"function_call\""));
    assertTrue(json.contains("\"call_id\":\"call_1\""));
    assertTrue(json.contains("\"type\":\"function_call_output\""));
    assertTrue(json.contains("\"output\":\"Found 3 engineers\""));
  }

  @Test
  void reasoningConfigSerialization() throws Exception {
    var config = ResponsesRequest.ReasoningConfig.of("medium");
    var json = objectMapper.writeValueAsString(config);
    assertTrue(json.contains("\"effort\":\"medium\""));
    assertTrue(json.contains("\"summary\":\"auto\""));
  }

  @Test
  void reasoningSummaryIsJsonStringNotObject() throws Exception {
    var config = ResponsesRequest.ReasoningConfig.of("high");
    var json = objectMapper.writeValueAsString(config);
    assertFalse(
        json.contains("\"summary\":{"),
        "summary must serialize as a JSON string, not an object — OpenAI's Responses API rejects"
            + " object-typed summary with HTTP 400");
    assertTrue(json.contains("\"summary\":\"auto\""));
  }

  @Test
  void textConfigSerialization() throws Exception {
    var textConfig =
        new ResponsesRequest.TextConfig(
            TextFormatConfig.jsonSchema("output", Map.of("type", "object")));
    var json = objectMapper.writeValueAsString(textConfig);
    assertTrue(json.contains("\"format\""));
    assertTrue(json.contains("\"json_schema\""));
    assertTrue(json.contains("\"output\""));
  }

  @Test
  void builderWithAllOptions() throws Exception {
    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Hi")))
            .withInstructions("Be helpful")
            .withStream(true)
            .withTools(
                List.of(ToolDefinition.function("test", "Test tool", Map.of("type", "object"))))
            .withToolChoice("auto")
            .withTemperature(0.5)
            .withTopP(0.8)
            .withMaxOutputTokens(2048)
            .withStop(List.of("END"))
            .withText(new ResponsesRequest.TextConfig(TextFormatConfig.text()))
            .withReasoning(ResponsesRequest.ReasoningConfig.of("low"))
            .build();

    assertEquals("gpt-4o", request.model());
    assertEquals(1, request.input().size());
    assertEquals("Be helpful", request.instructions());
    assertTrue(request.stream());
    assertEquals(1, request.tools().size());
    assertEquals("auto", request.toolChoice());
    assertEquals(0.5, request.temperature());
    assertEquals(0.8, request.topP());
    assertEquals(2048, request.maxOutputTokens());
    assertEquals(List.of("END"), request.stop());
    assertNotNull(request.text());
    assertNotNull(request.reasoning());
  }

  @Test
  void deserializeOutputItemWithReasoningSummary() throws Exception {
    var json =
        """
        {
          "type": "reasoning",
          "id": "rs_1",
          "summary": [
            {"type": "summary_text", "text": "I analyzed the problem step by step."}
          ]
        }
        """;

    var item = objectMapper.readValue(json, OutputItem.class);

    assertTrue(item.hasTypeReasoning());
    assertNotNull(item.summary());
    assertEquals(1, item.summary().size());
    assertEquals("summary_text", item.summary().getFirst().type());
    assertEquals("I analyzed the problem step by step.", item.summary().getFirst().text());
  }

  @Test
  void nullFieldsAreOmitted() throws Exception {
    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Hi")))
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertFalse(json.contains("\"instructions\""));
    assertFalse(json.contains("\"tools\""));
    assertFalse(json.contains("\"tool_choice\""));
    assertFalse(json.contains("\"temperature\""));
    assertFalse(json.contains("\"top_p\""));
    assertFalse(json.contains("\"max_output_tokens\""));
    assertFalse(json.contains("\"stop\""));
    assertFalse(json.contains("\"text\""));
    assertFalse(json.contains("\"reasoning\""));
    assertFalse(json.contains("\"stream\""));
  }

  @Test
  void inputItemAssistantMessageWithParts() throws Exception {
    var parts = List.of(ContentPart.outputText("Hello"), ContentPart.outputText(" World"));
    var item = InputItem.assistantMessage(parts);
    var json = objectMapper.writeValueAsString(item);
    assertTrue(json.contains("\"output_text\""));
    assertTrue(json.contains("Hello"));
    assertTrue(json.contains("World"));
  }

  // ── prompt-caching usage shape (hv2-bug2 Issue 1 — OpenAI peer) ──────────

  @Test
  void apiUsageDeserializesInputTokensDetailsCachedTokens() throws Exception {
    var usage =
        objectMapper.readValue(
            "{\"input_tokens\":2006,\"output_tokens\":150,\"total_tokens\":2156,"
                + "\"input_tokens_details\":{\"cached_tokens\":1920}}",
            ApiUsage.class);
    assertEquals(2006, usage.inputTokens());
    assertEquals(150, usage.outputTokens());
    assertEquals(2156, usage.totalTokens());
    assertEquals(1920, usage.cachedTokensOrZero());
    assertNotNull(usage.inputTokensDetails());
    assertEquals(1920, usage.inputTokensDetails().cachedTokens());
  }

  @Test
  void apiUsageDeserializesCacheWriteTokens() throws Exception {
    var usage =
        objectMapper.readValue(
            "{\"input_tokens\":3000,\"output_tokens\":150,\"total_tokens\":3150,"
                + "\"input_tokens_details\":{\"cached_tokens\":1920,\"cache_write_tokens\":1000}}",
            ApiUsage.class);
    assertEquals(1920, usage.cachedTokensOrZero());
    assertEquals(1000, usage.cacheWriteTokensOrZero());
  }

  @Test
  void apiUsageCacheWriteTokensOrZeroHandlesAbsence() throws Exception {
    var missingDetails =
        objectMapper.readValue(
            "{\"input_tokens\":25,\"output_tokens\":15,\"total_tokens\":40}", ApiUsage.class);
    assertEquals(0, missingDetails.cacheWriteTokensOrZero());

    var detailsWithoutWrites =
        objectMapper.readValue(
            "{\"input_tokens\":25,\"output_tokens\":15,\"total_tokens\":40,"
                + "\"input_tokens_details\":{\"cached_tokens\":10}}",
            ApiUsage.class);
    assertEquals(0, detailsWithoutWrites.cacheWriteTokensOrZero());
  }

  @Test
  void apiUsageCachedTokensOrZeroHandlesMissingDetails() throws Exception {
    var usage =
        objectMapper.readValue(
            "{\"input_tokens\":25,\"output_tokens\":15,\"total_tokens\":40}", ApiUsage.class);
    assertEquals(
        0,
        usage.cachedTokensOrZero(),
        "missing input_tokens_details must surface as cached=0, not NPE");
    assertNull(usage.inputTokensDetails());
  }

  @Test
  void apiUsageCachedTokensOrZeroHandlesPresentDetailsWithNullCount() throws Exception {
    var usage =
        objectMapper.readValue(
            "{\"input_tokens\":25,\"output_tokens\":15,\"total_tokens\":40,"
                + "\"input_tokens_details\":{}}",
            ApiUsage.class);
    assertEquals(
        0,
        usage.cachedTokensOrZero(),
        "details object present but cached_tokens absent must also yield 0");
    assertNotNull(usage.inputTokensDetails());
    assertNull(usage.inputTokensDetails().cachedTokens());
  }

  @Test
  void apiUsageDeserializesOutputTokensDetailsReasoningTokens() throws Exception {
    var usage =
        objectMapper.readValue(
            "{\"input_tokens\":50,\"output_tokens\":300,\"total_tokens\":350,"
                + "\"output_tokens_details\":{\"reasoning_tokens\":250}}",
            ApiUsage.class);
    assertNotNull(usage.outputTokensDetails());
    assertEquals(250, usage.outputTokensDetails().reasoningTokens());
  }

  @Test
  void apiUsageThreeArgConstructorOmitsDetails() {
    var usage = new ApiUsage(10, 20, 30);
    assertNull(usage.inputTokensDetails());
    assertNull(usage.outputTokensDetails());
    assertEquals(0, usage.cachedTokensOrZero());
  }
}
