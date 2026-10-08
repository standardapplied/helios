/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SerializationTest {

  private final ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  // --- Step-shaped input (step_list per Api-Revision 2026-05-20) ---

  @Test
  void serializeSimpleUserInputStep() throws Exception {
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Step.userInput("Hello")))
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"model\":\"gemini-3-flash-preview\""));
    assertTrue(json.contains("\"type\":\"user_input\""));
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"text\":\"Hello\""));
    assertFalse(json.contains("\"role\""), "v2 input is step_list — there is no role field");
  }

  @Test
  void serializeModelOutputStep() throws Exception {
    var step = Step.modelOutput("I'm here.");
    var json = objectMapper.writeValueAsString(step);
    assertTrue(json.contains("\"type\":\"model_output\""));
    assertTrue(json.contains("\"text\":\"I'm here.\""));
    assertFalse(json.contains("\"role\""));
  }

  @Test
  void serializeFunctionCallStep() throws Exception {
    var step = Step.functionCall("call_001", "search_people", Map.of("query", "founders"));
    var json = objectMapper.writeValueAsString(step);

    assertTrue(json.contains("\"type\":\"function_call\""));
    assertTrue(json.contains("\"id\":\"call_001\""));
    assertTrue(json.contains("\"name\":\"search_people\""));
    assertTrue(json.contains("\"arguments\":{\"query\":\"founders\"}"));
    assertFalse(json.contains("\"call_id\""), "function_call uses id, not call_id");
  }

  @Test
  void serializeFunctionCallStepNullArgsBecomesEmptyObject() throws Exception {
    var step = Step.functionCall("call_002", "ping", null);
    var json = objectMapper.writeValueAsString(step);

    assertTrue(json.contains("\"arguments\":{}"));
  }

  @Test
  void serializeFunctionResultStep() throws Exception {
    var step = Step.functionResult("call_001", "search_people", "Found 3 people");
    var json = objectMapper.writeValueAsString(step);

    assertTrue(json.contains("\"type\":\"function_result\""));
    assertTrue(json.contains("\"call_id\":\"call_001\""));
    assertTrue(json.contains("\"name\":\"search_people\""));
    assertTrue(json.contains("\"result\":\"Found 3 people\""));
    assertFalse(json.contains("\"id\":"), "function_result must not carry a top-level id");
    assertFalse(json.contains("\"is_error\""));
  }

  @Test
  void serializeFunctionResultStepWithErrorFlag() throws Exception {
    var step = Step.functionResult("call_x", "lookup", "boom", Boolean.TRUE);
    var json = objectMapper.writeValueAsString(step);

    assertTrue(json.contains("\"is_error\":true"));
  }

  @Test
  void serializeThoughtStep() throws Exception {
    var step = Step.thought("sig-abc");
    var json = objectMapper.writeValueAsString(step);

    assertTrue(json.contains("\"type\":\"thought\""));
    assertTrue(json.contains("\"signature\":\"sig-abc\""));
    assertFalse(json.contains("\"summary\""));
  }

  // --- Multi-turn round trip: user / model_output / function_call / function_result ---

  @Test
  void serializeMultiTurnRoundTrip() throws Exception {
    var input =
        List.of(
            Step.userInput("Who should I meet?"),
            Step.functionCall("call_001", "search_people", Map.of("query", "interesting people")),
            Step.functionResult("call_001", "search_people", "Found 3 people"),
            Step.modelOutput("You should meet Alice, Bob, and Carol."));

    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(input)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"type\":\"user_input\""));
    assertTrue(json.contains("\"type\":\"function_call\""));
    assertTrue(json.contains("\"type\":\"function_result\""));
    assertTrue(json.contains("\"type\":\"model_output\""));
    assertTrue(json.contains("\"id\":\"call_001\""));
    assertTrue(json.contains("\"call_id\":\"call_001\""));
    assertFalse(json.contains("\"role\":\"user\""));
    assertFalse(json.contains("\"role\":\"model\""));
  }

  // --- Content union: text, image, audio, document (PDF), video ---

  @ParameterizedTest(name = "{0} inline data ({1})")
  @CsvSource({
    "image, image/png, iVBORw0KGgo=",
    "document, application/pdf, JVBERi0=",
    "audio, audio/wav, AAAA",
    "video, video/mp4, BBBB"
  })
  void serializeInlineDataContentItem(String type, String mimeType, String data) throws Exception {
    var json = objectMapper.writeValueAsString(ContentItem.inlineData(type, mimeType, data));

    assertTrue(json.contains("\"type\":\"" + type + "\""));
    assertTrue(json.contains("\"mime_type\":\"" + mimeType + "\""));
    assertTrue(json.contains("\"data\":\"" + data + "\""));
    assertFalse(json.contains("\"text\""));
    assertFalse(json.contains("\"uri\""));
  }

  @ParameterizedTest(name = "{0} uri ({1})")
  @CsvSource({
    "image, image/jpeg, https://example.com/cat.jpg",
    "document, application/pdf, https://example.com/spec.pdf",
    "audio, audio/mp3, https://example.com/track.mp3",
    "video, video/mp4, https://example.com/clip.mp4"
  })
  void serializeUriContentItem(String type, String mimeType, String uri) throws Exception {
    var json = objectMapper.writeValueAsString(ContentItem.fileUri(type, mimeType, uri));

    assertTrue(json.contains("\"type\":\"" + type + "\""));
    assertTrue(json.contains("\"mime_type\":\"" + mimeType + "\""));
    assertTrue(json.contains("\"uri\":\"" + uri + "\""));
    assertFalse(json.contains("\"data\""));
  }

  @ParameterizedTest(name = "user input with {0} and text")
  @CsvSource({
    "document, application/pdf, JVBERi0=, Extract text from this PDF",
    "image, image/png, iVBORw0KGgo=, Caption this image"
  })
  void serializeUserInputWithMediaAndText(String type, String mimeType, String data, String text)
      throws Exception {
    var step =
        Step.userInput(
            List.of(ContentItem.inlineData(type, mimeType, data), ContentItem.text(text)));

    var json = objectMapper.writeValueAsString(step);
    assertTrue(json.contains("\"type\":\"user_input\""));
    assertTrue(json.contains("\"type\":\"" + type + "\""));
    assertTrue(json.contains("\"mime_type\":\"" + mimeType + "\""));
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains(text));
  }

  // --- Tools, tool_choice (must live inside generation_config) ---

  @Test
  void serializeRequestWithFunctionTools() throws Exception {
    var searchPeople =
        Tool.newBuilder()
            .withName("search_people")
            .withDescription("Finds people in the community using semantic search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Natural language description of who to find")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("[]"))
            .build();

    var toolDefinitions =
        List.of(
            ToolDefinition.function(
                searchPeople.name(),
                searchPeople.description(),
                searchPeople.parametersAsJsonSchema()));

    var input = new ArrayList<Step>();
    input.add(Step.userInput("Who should I meet?"));

    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withSystemInstruction("You are a helpful assistant.")
            .withInput(input)
            .withTools(toolDefinitions)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"search_people\""));
    assertTrue(json.contains("\"type\":\"function\""));
    assertTrue(json.contains("\"system_instruction\":\"You are a helpful assistant.\""));
  }

  @Test
  void toolChoiceLivesInsideGenerationConfig() throws Exception {
    var gen =
        InteractionGenerationConfig.newBuilder()
            .withMaxOutputTokens(16)
            .withToolChoice(ToolChoiceConfig.auto())
            .build();
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Step.userInput("hi")))
            .withTools(List.of(ToolDefinition.googleSearch()))
            .withGenerationConfig(gen)
            .build();

    var json = objectMapper.writeValueAsString(request);

    var requestMap = objectMapper.readValue(json, Map.class);
    assertFalse(
        requestMap.containsKey("tool_choice"), "tool_choice must NOT sit at the request root");
    @SuppressWarnings("unchecked")
    var genCfg = (Map<String, Object>) requestMap.get("generation_config");
    assertEquals("auto", genCfg.get("tool_choice"));
  }

  @Test
  void toolChoiceBareModesSerializeAsStrings() throws Exception {
    assertEquals("\"auto\"", objectMapper.writeValueAsString(ToolChoiceConfig.auto()));
    assertEquals("\"any\"", objectMapper.writeValueAsString(ToolChoiceConfig.any()));
    assertEquals("\"none\"", objectMapper.writeValueAsString(ToolChoiceConfig.none()));
  }

  @Test
  void toolChoiceValidatedSerializesAsAllowedToolsObjectInGivenOrder() throws Exception {
    var validated = ToolChoiceConfig.validated(new LinkedHashSet<>(List.of("b", "a")));
    assertEquals(
        "{\"allowed_tools\":{\"mode\":\"validated\",\"tools\":[\"b\",\"a\"]}}",
        objectMapper.writeValueAsString(validated));
  }

  @Test
  void toolChoiceFactoriesPreserveMode() {
    assertEquals("auto", ToolChoiceConfig.auto().mode());
    assertNull(ToolChoiceConfig.auto().allowedTools());
    assertEquals("any", ToolChoiceConfig.any().mode());
    assertEquals("none", ToolChoiceConfig.none().mode());
    var validated = ToolChoiceConfig.validated(new LinkedHashSet<>(List.of("a")));
    assertEquals("validated", validated.mode());
    assertEquals(Set.of("a"), validated.allowedTools());
  }

  // --- Response-format selector ---

  @Test
  void serializeRequestWithJsonResponseFormat() throws Exception {
    var schema =
        Map.<String, Object>of(
            "type", "object", "properties", Map.of("name", Map.of("type", "string")));
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Step.userInput("Extract")))
            .withResponseFormat(ResponseFormat.json(schema))
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"response_format\""));
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"mime_type\":\"application/json\""));
    assertTrue(json.contains("\"schema\""));
    assertFalse(
        json.contains("response_mime_type"),
        "response_mime_type was removed in Api-Revision 2026-05-20");
  }

  @Test
  void jsonResponseFormatRequiresSchema() {
    assertThrows(IllegalArgumentException.class, () -> ResponseFormat.json(null));
  }

  // --- previous_interaction_id ---

  @Test
  void serializeRequestWithPreviousInteractionId() throws Exception {
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Step.userInput("Follow-up")))
            .withPreviousInteractionId("interaction_abc123")
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"previous_interaction_id\":\"interaction_abc123\""));
    assertFalse(json.contains("system_instruction"));
  }

  @Test
  void serializeRequestWithoutPreviousInteractionIdOmitsField() throws Exception {
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Step.userInput("Hello")))
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertFalse(json.contains("previous_interaction_id"));
  }

  // --- Response parsing ---

  @Test
  void deserializeInteractionResponseWithSteps() throws Exception {
    var json =
        """
        {
          "id": "int_123",
          "model": "gemini-3-flash-preview",
          "status": "completed",
          "steps": [
            {
              "type": "model_output",
              "content": [
                {
                  "type": "text",
                  "text": "Result from search",
                  "annotations": [
                    {
                      "type": "url_citation",
                      "url": "https://example.com",
                      "title": "Source",
                      "start_index": 0,
                      "end_index": 18
                    }
                  ]
                }
              ]
            }
          ],
          "usage": {
            "total_input_tokens": 5,
            "total_output_tokens": 3,
            "total_tokens": 8
          }
        }
        """;

    var response = objectMapper.readValue(json, InteractionResponse.class);

    assertEquals("int_123", response.id());
    assertEquals("completed", response.status());
    assertNotNull(response.steps());
    assertEquals(1, response.steps().size());

    var step = response.steps().getFirst();
    assertTrue(step.hasTypeModelOutput());
    assertTrue(step.hasContent());
    assertEquals(1, step.content().size());

    var text = step.content().getFirst();
    assertTrue(text.hasTypeText());
    assertTrue(text.hasAnnotations());
    assertEquals("url_citation", text.annotations().getFirst().type());
    assertEquals("https://example.com", text.annotations().getFirst().url());

    assertEquals(5, response.usage().inputTokens());
    assertEquals(3, response.usage().outputTokens());
    assertEquals(8, response.usage().totalTokens());
  }

  @Test
  void deserializeFunctionCallAndThoughtSteps() throws Exception {
    var json =
        """
        {
          "id": "int_001",
          "status": "requires_action",
          "steps": [
            {
              "type": "thought",
              "summary": [
                {"type": "text", "text": "I need to check the weather in Boston..."}
              ],
              "signature": "abc123..."
            },
            {
              "type": "function_call",
              "id": "fc_1",
              "name": "get_weather",
              "arguments": { "location": "Boston, MA" }
            }
          ]
        }
        """;

    var response = objectMapper.readValue(json, InteractionResponse.class);

    assertEquals("requires_action", response.status());
    assertEquals(2, response.steps().size());

    var thought = response.steps().get(0);
    assertTrue(thought.hasTypeThought());
    assertEquals("abc123...", thought.signature());
    assertTrue(thought.hasSummary());
    assertEquals("I need to check the weather in Boston...", thought.summary().getFirst().text());

    var call = response.steps().get(1);
    assertEquals("function_call", call.type());
    assertEquals("fc_1", call.id());
    assertEquals("get_weather", call.name());
    assertEquals("Boston, MA", call.arguments().get("location"));
  }

  @Test
  void deserializeFunctionResultStep() throws Exception {
    var json =
        """
        {
          "type": "function_result",
          "call_id": "fc_1",
          "name": "get_weather",
          "is_error": false,
          "result": {"temp": 72, "unit": "F"}
        }
        """;
    var step = objectMapper.readValue(json, Step.class);

    assertEquals("function_result", step.type());
    assertEquals("fc_1", step.callId());
    assertEquals("get_weather", step.name());
    assertEquals(Boolean.FALSE, step.errorFlag());
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) step.result();
    assertEquals(72, result.get("temp"));
  }

  @Test
  void deserializeGoogleSearchSteps() throws Exception {
    var json =
        """
        {
          "id": "int_456",
          "status": "completed",
          "steps": [
            {
              "type": "google_search_call",
              "id": "gs_1",
              "arguments": { "queries": ["last Super Bowl winner"] },
              "signature": "sig_call"
            },
            {
              "type": "google_search_result",
              "call_id": "gs_1",
              "result": {"search_suggestions": "<div>...</div>"},
              "signature": "sig_result"
            },
            {
              "type": "model_output",
              "content": [
                {
                  "type": "text",
                  "text": "Kansas City Chiefs.",
                  "annotations": [
                    {
                      "type": "url_citation",
                      "url": "https://www.nfl.com/super-bowl",
                      "title": "NFL.com",
                      "start_index": 0,
                      "end_index": 19
                    }
                  ]
                }
              ]
            }
          ]
        }
        """;

    var response = objectMapper.readValue(json, InteractionResponse.class);

    assertEquals(3, response.steps().size());
    assertEquals("google_search_call", response.steps().get(0).type());
    assertEquals("sig_call", response.steps().get(0).signature());
    assertEquals("google_search_result", response.steps().get(1).type());
    assertEquals("gs_1", response.steps().get(1).callId());
    assertNotNull(response.steps().get(1).result());
    @SuppressWarnings("unchecked")
    var resultMap = (Map<String, Object>) response.steps().get(1).result();
    assertEquals("<div>...</div>", resultMap.get("search_suggestions"));

    var listJson =
        """
        {
          "id":"int_456",
          "status":"completed",
          "steps":[{"type":"google_search_result","call_id":"gs_1",
            "result":[{"search_suggestions":"<div>chip1</div>"},
                     {"search_suggestions":"<div>chip2</div>"}]}]
        }
        """;
    var listResponse = objectMapper.readValue(listJson, InteractionResponse.class);
    @SuppressWarnings("unchecked")
    var chips = (List<Map<String, Object>>) listResponse.steps().getFirst().result();
    assertEquals(2, chips.size());
    assertEquals("<div>chip1</div>", chips.get(0).get("search_suggestions"));

    var modelStep = response.steps().get(2);
    assertTrue(modelStep.hasTypeModelOutput());
    assertTrue(modelStep.content().getFirst().hasAnnotations());
  }

  @Test
  void deserializeFailedInteractionStatus() throws Exception {
    var json =
        """
        {
          "id": "int_x",
          "status": "failed",
          "steps": []
        }
        """;
    var response = objectMapper.readValue(json, InteractionResponse.class);
    assertEquals("failed", response.status());
  }

  @Test
  void deserializeStepFlags() throws Exception {
    var userStep =
        objectMapper.readValue(
            "{\"type\":\"user_input\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}",
            Step.class);
    assertEquals("user_input", userStep.type());
    assertFalse(userStep.hasTypeModelOutput());
    assertFalse(userStep.hasTypeThought());
    assertTrue(userStep.hasContent());
    assertFalse(userStep.hasSummary());

    var bareThought = objectMapper.readValue("{\"type\":\"thought\"}", Step.class);
    assertFalse(bareThought.hasSummary());
    assertFalse(bareThought.hasContent());
  }

  // --- ContentItem deserialization + helpers ---

  @Test
  void contentItemRoundTripWithAnnotations() throws Exception {
    var json =
        """
        {
          "type": "text",
          "text": "According to sources...",
          "annotations": [
            {
              "type": "url_citation",
              "url": "https://example.com",
              "title": "Example Source",
              "start_index": 0,
              "end_index": 25
            }
          ]
        }
        """;

    var item = objectMapper.readValue(json, ContentItem.class);

    assertEquals("text", item.type());
    assertTrue(item.hasTypeText());
    assertTrue(item.hasAnnotations());
    assertEquals(1, item.annotations().size());

    var annotation = item.annotations().getFirst();
    assertEquals("url_citation", annotation.type());
    assertEquals("https://example.com", annotation.url());
    assertEquals("Example Source", annotation.title());
    assertEquals(0, annotation.startIndex());
    assertEquals(25, annotation.endIndex());
  }

  @Test
  void contentItemWithoutAnnotationsHasAnnotationsReturnsFalse() throws Exception {
    var item =
        objectMapper.readValue("{\"type\":\"text\",\"text\":\"Hello world\"}", ContentItem.class);

    assertEquals("text", item.type());
    assertFalse(item.hasAnnotations());
  }

  @Test
  void contentItemHasTypeTextMatchesOnlyText() {
    assertTrue(ContentItem.text("x").hasTypeText());
    assertFalse(ContentItem.inlineData("image", "image/png", "AAA=").hasTypeText());
  }

  @Test
  void contentItemAnnotationHelperRejectsEmptyList() {
    var item = new ContentItem("text", "x", null, null, null, null, null, null, null);
    assertFalse(item.hasAnnotations());
    var withEmpty = new ContentItem("text", "x", null, null, null, null, List.of(), null, null);
    assertFalse(withEmpty.hasAnnotations());
  }

  // --- Annotations ---

  @Test
  void deserializeOutputAnnotation() throws Exception {
    var json =
        """
        {
          "type": "url_citation",
          "url": "https://example.com/page",
          "title": "Page Title",
          "start_index": 10,
          "end_index": 50
        }
        """;

    var annotation = objectMapper.readValue(json, OutputAnnotation.class);

    assertEquals("url_citation", annotation.type());
    assertEquals("https://example.com/page", annotation.url());
    assertEquals("Page Title", annotation.title());
    assertEquals(10, annotation.startIndex());
    assertEquals(50, annotation.endIndex());
  }

  // --- Tool definitions ---

  @Test
  void serializeUrlContextToolDefinition() throws Exception {
    var tool = ToolDefinition.urlContext();
    var json = objectMapper.writeValueAsString(tool);

    assertTrue(json.contains("\"type\":\"url_context\""));
    assertFalse(json.contains("\"name\""));
    assertFalse(json.contains("\"description\""));
    assertFalse(json.contains("\"parameters\""));
  }

  // --- Generation config ---

  @Test
  void interactionGenerationConfigBuilderCoversEverySetter() throws Exception {
    var cfg =
        InteractionGenerationConfig.newBuilder()
            .withMaxOutputTokens(64)
            .withStopSequences(List.of("STOP"))
            .withSeed(42L)
            .withThinkingLevel("medium")
            .withToolChoice(ToolChoiceConfig.any())
            .build();
    assertEquals(64, cfg.maxOutputTokens());
    assertEquals(List.of("STOP"), cfg.stopSequences());
    assertEquals(42L, cfg.seed());
    assertEquals("medium", cfg.thinkingLevel());
    assertEquals("any", cfg.toolChoice().mode());

    var json = objectMapper.writeValueAsString(cfg);
    assertTrue(json.contains("\"max_output_tokens\":64"));
    assertTrue(json.contains("\"stop_sequences\""));
    assertTrue(json.contains("\"seed\":42"));
    assertTrue(json.contains("\"thinking_level\":\"medium\""));
    assertTrue(json.contains("\"tool_choice\":\"any\""));
  }

  // --- Builders ---

  @Test
  void interactionRequestBuilderCoversEverySetter() throws Exception {
    var gen =
        InteractionGenerationConfig.newBuilder()
            .withMaxOutputTokens(16)
            .withToolChoice(ToolChoiceConfig.auto())
            .build();
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Step.userInput("hi")))
            .withTools(List.of(ToolDefinition.googleSearch()))
            .withGenerationConfig(gen)
            .withResponseFormat(ResponseFormat.json(Map.of("type", "object")))
            .withStream(true)
            .build();

    assertEquals("gemini-3-flash-preview", request.model());
    assertEquals(1, request.input().size());
    assertEquals(1, request.tools().size());
    assertEquals(16, request.generationConfig().maxOutputTokens());
    assertEquals("auto", request.generationConfig().toolChoice().mode());
    assertEquals("text", request.responseFormat().type());
    assertTrue(request.stream());
  }

  @Test
  void stepHasSummaryHandlesEmptyAndPopulatedLists() throws Exception {
    var emptySummary = objectMapper.readValue("{\"type\":\"thought\",\"summary\":[]}", Step.class);
    assertFalse(emptySummary.hasSummary());

    var populatedSummary =
        objectMapper.readValue(
            "{\"type\":\"thought\",\"summary\":[{\"type\":\"text\",\"text\":\"inner\"}]}",
            Step.class);
    assertTrue(populatedSummary.hasSummary());
  }

  @Test
  void stepHasContentHandlesEmptyList() {
    var step = Step.modelOutput(List.of());
    assertFalse(step.hasContent());
  }

  @Test
  void functionCallFactoryBuildsAFunctionCallStep() {
    var fc = Step.functionCall("id1", "name1", Map.of());
    assertEquals("function_call", fc.type());
    assertFalse(fc.hasTypeModelOutput());
  }

  // --- Streaming events ---

  @Test
  void streamingEventDiscriminatorsCoverNewEventTypes() throws Exception {
    var created =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.created\",\"interaction\":{\"id\":\"int_z\"}}",
            StreamingEvent.class);
    assertEquals("interaction.created", created.eventType());

    var inProgress =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.in_progress\",\"interaction_id\":\"int_z\"}",
            StreamingEvent.class);
    assertEquals("interaction.in_progress", inProgress.eventType());
    assertEquals("int_z", inProgress.interactionId());

    var requires =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.requires_action\",\"interaction_id\":\"int_z\"}",
            StreamingEvent.class);
    assertEquals("interaction.requires_action", requires.eventType());

    var statusUpdate =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.status_update\","
                + "\"interaction_id\":\"int_z\",\"status\":\"in_progress\"}",
            StreamingEvent.class);
    assertEquals("interaction.status_update", statusUpdate.eventType());
    assertEquals("int_z", statusUpdate.interactionId());
    assertEquals("in_progress", statusUpdate.status());

    var completed =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.completed\","
                + "\"interaction\":{\"id\":\"int_z\",\"status\":\"completed\"}}",
            StreamingEvent.class);
    assertEquals("interaction.completed", completed.eventType());

    var start =
        objectMapper.readValue(
            "{\"event_type\":\"step.start\",\"index\":0,\"step\":{\"type\":\"model_output\"}}",
            StreamingEvent.class);
    assertEquals("step.start", start.eventType());
    assertEquals(0, start.index());
    assertNotNull(start.step());

    var deltaText =
        objectMapper.readValue(
            "{\"event_type\":\"step.delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text\",\"text\":\"hi\"}}",
            StreamingEvent.class);
    assertEquals("step.delta", deltaText.eventType());
    assertNotNull(deltaText.delta());
    assertNull(deltaText.argumentsDelta());

    var deltaArgs =
        objectMapper.readValue(
            "{\"event_type\":\"step.delta\",\"index\":1,\"arguments_delta\":\"{\\\"a\\\":1\"}",
            StreamingEvent.class);
    assertEquals("step.delta", deltaArgs.eventType());
    assertEquals("{\"a\":1", deltaArgs.argumentsDelta());

    var stop =
        objectMapper.readValue(
            "{\"event_type\":\"step.stop\",\"index\":0,\"status\":\"done\"}", StreamingEvent.class);
    assertEquals("step.stop", stop.eventType());
    assertEquals("done", stop.status());

    var error =
        objectMapper.readValue(
            "{\"event_type\":\"error\",\"error\":{\"code\":500,\"message\":\"Internal error\"}}",
            StreamingEvent.class);
    assertEquals("error", error.eventType());
    assertNotNull(error.error());
    assertEquals(500, error.error().get("code"));
    assertEquals("Internal error", error.error().get("message"));
  }

  @Test
  void contentItemArgumentsPreservePartialJsonStringFragment() throws Exception {
    // The arguments_delta streaming variant ships partial, individually-invalid JSON fragments
    // that the loop accumulates into a buffer. RawArgumentsDeserializer must return them verbatim
    // and must NOT try to parse them as JSON.
    var item =
        objectMapper.readValue(
            "{\"type\":\"arguments_delta\",\"arguments\":\"{\\\"city\\\":\\\"\"}",
            ContentItem.class);
    assertEquals("{\"city\":\"", item.arguments());
  }

  @Test
  void contentItemArgumentsNormalizeObjectToCompactJsonString() throws Exception {
    // The google_search_call step packs a complete arguments OBJECT into the step.delta union
    // slot. RawArgumentsDeserializer re-serialises it to a compact JSON string so it never lands
    // in the bare String component as a raw object (the grounded-streaming crash).
    var item =
        objectMapper.readValue(
            "{\"type\":\"google_search_call\",\"arguments\":{\"queries\":[\"helios\"]}}",
            ContentItem.class);
    assertEquals("{\"queries\":[\"helios\"]}", item.arguments());
  }

  @Test
  void contentItemArgumentsTolerateNull() throws Exception {
    var item = objectMapper.readValue("{\"type\":\"text\",\"text\":\"hi\"}", ContentItem.class);
    assertNull(item.arguments());

    var explicitNull =
        objectMapper.readValue("{\"type\":\"text\",\"arguments\":null}", ContentItem.class);
    assertNull(explicitNull.arguments());
  }

  @Test
  void stepGoogleSearchCallRoundTripsSearchType() throws Exception {
    var step =
        objectMapper.readValue(
            "{\"type\":\"google_search_call\",\"id\":\"gsc_1\","
                + "\"arguments\":{\"queries\":[\"x\"]},\"search_type\":\"web_search\"}",
            Step.class);
    assertEquals("google_search_call", step.type());
    assertEquals("web_search", step.searchType());
    assertEquals(List.of("x"), step.arguments().get("queries"));
  }

  @Test
  void stepErrorFlagWireFormatIsStillIsError() throws Exception {
    // Renaming the record component from isError to errorFlag (to avoid Jackson's is*() boolean
    // getter auto-detection synthesising a parallel virtual "error" property) must NOT change the
    // wire format. The @JsonProperty("is_error") binding keeps both directions on the original
    // serialised name.
    var stepIn =
        objectMapper.readValue(
            "{\"type\":\"function_result\",\"call_id\":\"fc_1\",\"is_error\":true,"
                + "\"result\":\"boom\"}",
            Step.class);
    assertEquals(Boolean.TRUE, stepIn.errorFlag(), "deserialise reads is_error into errorFlag");

    var stepOut = Step.functionResult("fc_2", "tool", Map.of("ok", true), Boolean.FALSE);
    var json = objectMapper.writeValueAsString(stepOut);
    assertTrue(
        json.contains("\"is_error\":false"),
        "serialise must emit is_error, never errorFlag/error — actual: " + json);
    assertTrue(
        !json.contains("\"errorFlag\""), "must not leak the internal field name to the wire");
    assertTrue(!json.contains("\"error\":"), "must not emit a phantom 'error' property");
  }

  // ── implicit prompt caching usage wire shape (hv2-bug2 Issue 1 — Gemini peer) ──

  @Test
  void interactionUsageDeserializesTotalCachedTokensFromWire() throws Exception {
    var usage =
        objectMapper.readValue(
            "{\"total_input_tokens\":2006,\"total_output_tokens\":150,"
                + "\"total_tokens\":2156,\"total_cached_tokens\":1920}",
            InteractionUsage.class);
    assertEquals(2006, usage.inputTokens());
    assertEquals(150, usage.outputTokens());
    assertEquals(2156, usage.totalTokens());
    assertEquals(1920, usage.cachedTokens());
    assertEquals(1920, usage.cachedTokensOrZero());
  }

  @Test
  void interactionUsageCachedTokensOrZeroHandlesAbsentField() throws Exception {
    var usage =
        objectMapper.readValue(
            "{\"total_input_tokens\":25,\"total_output_tokens\":15,\"total_tokens\":40}",
            InteractionUsage.class);
    assertEquals(
        0,
        usage.cachedTokensOrZero(),
        "missing total_cached_tokens must surface as cached=0 (Gemini 1.5/2.0 path) — no NPE");
    assertNull(usage.cachedTokens());
  }

  @Test
  void interactionUsageCachedTokensZeroExplicitlyReportedOnGemini25() throws Exception {
    // Gemini 2.5+ always emits the field, even when no cache hit happened.
    var usage =
        objectMapper.readValue(
            "{\"total_input_tokens\":25,\"total_output_tokens\":15,"
                + "\"total_tokens\":40,\"total_cached_tokens\":0}",
            InteractionUsage.class);
    assertEquals(0, usage.cachedTokens());
    assertEquals(0, usage.cachedTokensOrZero());
  }

  @Test
  void interactionUsageThreeArgConstructorOmitsCachedTokens() {
    var usage = new InteractionUsage(40, 25, 15);
    assertNull(usage.cachedTokens());
    assertEquals(0, usage.cachedTokensOrZero());
  }
}
