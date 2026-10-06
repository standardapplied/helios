/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ThinkingLevel;
import com.standardapplied.helios.core.schema.Description;
import com.standardapplied.helios.core.schema.Nullable;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicModelIntegrationTest {

  private static AnthropicModel model;
  private static String apiKey;

  @BeforeAll
  static void setUp() {
    apiKey = System.getenv("ANTHROPIC_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
  }

  @Test
  void simpleChat() {
    var messages = List.of(Message.user("What is 2 + 2? Reply with just the number."));

    var response = model.chat(messages);

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(response.content().contains("4"));
    assertEquals(FinishReason.STOP, response.finishReason());
  }

  @Test
  void chatWithSystemMessage() {
    var messages =
        List.of(
            Message.system("You are a pirate. Always respond in pirate speak."),
            Message.user("Hello, how are you?"));

    var response = model.chat(messages);

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(
        response.content().toLowerCase().contains("arr")
            || response.content().toLowerCase().contains("ahoy")
            || response.content().toLowerCase().contains("matey")
            || response.content().toLowerCase().contains("ye"));
  }

  @Test
  void chatWithUsageStats() {
    var messages = List.of(Message.user("Say hello"));

    var response = model.chat(messages);

    assertNotNull(response.usage());
    assertTrue(response.usage().inputTokens() > 0);
    assertTrue(response.usage().outputTokens() > 0);
    assertTrue(response.usage().totalTokens() > 0);
  }

  @Test
  void streamingChat() {
    var messages = List.of(Message.user("Count from 1 to 5, one number per line."));

    var iterator = model.chatStream(messages, List.of());

    var textDeltas = new ArrayList<String>();
    StreamEvent.Done doneEvent = null;

    while (iterator.hasNext()) {
      var event = iterator.next();
      if (event instanceof StreamEvent.TextDelta(String text)) {
        textDeltas.add(text);
      } else if (event instanceof StreamEvent.Done done) {
        doneEvent = done;
      }
    }

    assertFalse(textDeltas.isEmpty());
    assertNotNull(doneEvent);
    assertNotNull(doneEvent.response());

    var fullContent = String.join("", textDeltas);
    assertTrue(fullContent.contains("1"));
    assertTrue(fullContent.contains("5"));
  }

  @Test
  void chatWithToolCall() {
    var weatherTool =
        Tool.newBuilder()
            .withName("get_weather")
            .withDescription("Get the current weather for a location")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("location")
                    .withType(ParameterType.STRING)
                    .withDescription("The city name")
                    .withRequired(true)
                    .build())
            .withExecutor(
                (args, ctx) -> {
                  var location = (String) args.get("location");
                  return ToolResult.success("Weather in " + location + ": 72°F, sunny");
                })
            .build();

    var messages = List.of(Message.user("What's the weather in San Francisco?"));

    var response = model.chat(messages, List.of(weatherTool));

    assertNotNull(response);
    if (response.hasToolCalls()) {
      assertEquals(1, response.toolCalls().size());
      var toolCall = response.toolCalls().getFirst();
      assertEquals("get_weather", toolCall.name());
      assertNotNull(toolCall.arguments());
    }
  }

  @Test
  void multiTurnConversation() {
    var messages = new ArrayList<Message>();
    messages.add(Message.user("My name is Alice."));

    var response1 = model.chat(messages);
    assertNotNull(response1);

    messages.add(Message.assistant(response1.content()));
    messages.add(Message.user("What is my name?"));

    var response2 = model.chat(messages);

    assertNotNull(response2);
    assertTrue(response2.content().toLowerCase().contains("alice"));
  }

  @Test
  void modelMetadata() {
    assertEquals("claude-sonnet-4-6", model.id());
    assertEquals("anthropic", model.provider());
    assertEquals(1_000_000, model.contextWindow());
  }

  @Test
  void chatWithThinking() {
    var config =
        ModelConfig.newBuilder().withApiKey(apiKey).withThinkingLevel(ThinkingLevel.HIGH).build();
    var thinkingModel = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var messages =
        List.of(
            Message.user(
                "Three friends split a bill of $187.40 so that Ana pays twice what Ben pays and"
                    + " Cy pays $12 more than Ben. How much does each pay? Verify the total."));

    var response = thinkingModel.chat(messages);

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(response.hasThinking(), "Expected thinking content");
    assertFalse(response.thinking().isBlank(), "Thinking should not be empty");

    assertFalse(
        ThinkingBlock.decodeAll(response.metadata()).isEmpty(),
        "Expected a signed thinking block in metadata");
  }

  // ── Opus 5.5 / Sonnet 5.5 / always-on models ──────────────────────────────

  private static final List<AnthropicModelId> THE_55_MODELS =
      List.of(AnthropicModelId.CLAUDE_OPUS_5_5, AnthropicModelId.CLAUDE_SONNET_5_5);

  private static AnthropicModel modelFor(String modelId, ThinkingLevel level) {
    return new AnthropicModel(
        modelId,
        ModelConfig.newBuilder()
            .withApiKey(apiKey)
            .withThinkingLevel(level)
            .withMaxOutputTokens(16_000)
            .build());
  }

  private static Tool stringTool(String name, String description, String parameter) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription(description)
        .withParameter(
            ToolParameter.newBuilder()
                .withName(parameter)
                .withType(ParameterType.STRING)
                .withDescription(parameter)
                .withRequired(true)
                .build())
        .withExecutor((args, ctx) -> ToolResult.success("ok"))
        .build();
  }

  /**
   * Drive a tool loop to its final answer, answering every tool call with {@code toolOutput}, and
   * return each turn's response. Every turn after the first replays the previous assistant turns,
   * thinking blocks included, so a request the API would reject for a modified or misplaced block
   * fails here.
   */
  private static List<com.standardapplied.helios.core.model.Response<Void>> runToolLoop(
      AnthropicModel candidate, List<Message> opening, List<Tool> tools, String toolOutput) {
    var history = new ArrayList<>(opening);
    var turns = new ArrayList<com.standardapplied.helios.core.model.Response<Void>>();
    for (var turn = 0; turn < 8; turn++) {
      var response = candidate.chat(history, tools);
      turns.add(response);
      if (!response.hasToolCalls()) {
        return turns;
      }
      history.add(response.toMessage());
      for (var call : response.toolCalls()) {
        history.add(Message.tool(call.id(), call.name(), toolOutput));
      }
    }
    throw new AssertionError("tool loop did not finish within 8 turns");
  }

  @Test
  void the55ModelsAcceptEveryThinkingLevel() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());
    for (var modelId : THE_55_MODELS) {
      for (var level : ThinkingLevel.values()) {
        var label = modelId.id() + " " + level;
        try (var candidate = modelFor(modelId.id(), level)) {
          var response = candidate.chat(List.of(Message.user("Reply with the single word: ok")));

          assertEquals(FinishReason.STOP, response.finishReason(), label);
          assertFalse(response.content().isBlank(), label);
          assertTrue(calculator.cost(candidate.id(), response.usage()).microUsd() > 0, label);
        }
      }
    }
  }

  @Test
  void alwaysOnFableModelsAcceptSummarizedAdaptiveThinking() {
    for (var modelId :
        List.of(AnthropicModelId.CLAUDE_FABLE_5_1, AnthropicModelId.CLAUDE_FABLE_5)) {
      for (var level : List.of(ThinkingLevel.NONE, ThinkingLevel.LOW, ThinkingLevel.MAX)) {
        var label = modelId.id() + " " + level;
        try (var candidate = modelFor(modelId.id(), level)) {
          var response = candidate.chat(List.of(Message.user("Reply with the single word: ok")));

          assertEquals(FinishReason.STOP, response.finishReason(), label);
        } catch (AnthropicException e) {
          assumeTrue(
              e.statusCode() != 403
                  && e.statusCode() != 404
                  && !e.getMessage().contains("data retention"),
              () -> modelId.id() + " is not available to this API key");
          throw e;
        }
      }
    }
  }

  @Test
  void the55ModelsReplayTheirThinkingBlocksAcrossAToolLoop() {
    var weatherTool =
        stringTool("get_weather", "Get the current weather for a location", "location");

    for (var modelId : THE_55_MODELS) {
      for (var level : List.of(ThinkingLevel.NONE, ThinkingLevel.LOW, ThinkingLevel.MAX)) {
        var label = modelId.id() + " " + level;
        try (var candidate = modelFor(modelId.id(), level)) {
          var turns =
              runToolLoop(
                  candidate,
                  List.of(
                      Message.user(
                          "Use the get_weather tool for San Francisco and for Austin, then"
                              + " compare them.")),
                  List.of(weatherTool),
                  "72°F, sunny");

          assertTrue(turns.size() >= 2, label);
          assertTrue(turns.getFirst().hasToolCalls(), label);
          assertEquals(FinishReason.STOP, turns.getLast().finishReason(), label);
        }
      }
    }
  }

  @Test
  void agenticLoopKeepsProgressNotesReadableAndThePromptCacheWarm() {
    var tools =
        List.of(
            stringTool(
                "search_profiles",
                "Search member profiles by free-text query; returns ids",
                "query"),
            stringTool("get_profile", "Read one member profile by id", "id"));
    var opening =
        List.of(
            Message.system(
                "You are a matchmaking agent for a founders community. Work in steps: form"
                    + " hypotheses about who the viewer should meet, run several different"
                    + " searches, read the most promising profiles, and keep the user informed"
                    + " of what you found and what you will do next as you go."),
            Message.user(
                "Viewer: a climate-hardware founder in Austin seeking a seed investor and a"
                    + " supply-chain cofounder. Find the best two matches. Run at least four"
                    + " searches and read at least three profiles before answering."));
    var profile =
        "Profile p7: operator turned angel, hardware supply chain background, based in Texas,"
            + " seeking early-stage climate deals. Related ids: p7, p12, p19.";

    for (var modelId : THE_55_MODELS) {
      for (var level : List.of(ThinkingLevel.NONE, ThinkingLevel.MEDIUM)) {
        var label = modelId.id() + " " + level;
        try (var candidate = modelFor(modelId.id(), level)) {
          var turns = runToolLoop(candidate, opening, tools, profile);

          assertEquals(FinishReason.STOP, turns.getLast().finishReason(), label);
          assertFalse(turns.getLast().content().isBlank(), label);
          assertTrue(
              turns.stream().anyMatch(turn -> turn.usage().cacheReadInputTokens() > 0),
              () -> label + ": no turn read the prompt cache");
        }
      }
    }

    try (var opus = modelFor(AnthropicModelId.CLAUDE_OPUS_5_5.id(), ThinkingLevel.MEDIUM)) {
      var turns = runToolLoop(opus, opening, tools, profile);

      assertTrue(
          turns.stream().anyMatch(com.standardapplied.helios.core.model.Response::hasThinking),
          "a level above NONE requests the summarized display, so the notes Opus 5.5 writes"
              + " between tool calls arrive as thinking text instead of empty blocks");
    }
  }

  @Test
  void webSearchTurnThatFiltersResultsInCodeIsReplayable() {
    var saveNote = stringTool("save_note", "Save a note for the user", "text");

    for (var modelId : THE_55_MODELS) {
      var config =
          ModelConfig.newBuilder()
              .withApiKey(apiKey)
              .withThinkingLevel(ThinkingLevel.MEDIUM)
              .withMaxOutputTokens(16_000)
              .withWebSearch(true)
              .build();
      try (var candidate = new AnthropicModel(modelId, config)) {
        var turns =
            runToolLoop(
                candidate,
                List.of(
                    Message.user(
                        "Search the web for the year Austin, Texas was founded, then call"
                            + " save_note with the year, then tell me what you saved.")),
                List.of(saveNote),
                "saved");

        assertEquals(FinishReason.STOP, turns.getLast().finishReason(), modelId.id());
        assertTrue(
            turns.stream()
                .anyMatch(turn -> turn.metadata().containsKey(AnthropicModel.RAW_CONTENT_KEY)),
            () -> modelId.id() + ": the search turn must be echoed from its raw content");
      }
    }
  }

  @Test
  void datedSnapshotIdKeepsItsFamilyRequestShape() {
    try (var haiku = modelFor("claude-haiku-4-5-20251001", ThinkingLevel.LOW)) {
      var response = haiku.chat(List.of(Message.user("What is 17 * 23? Think it through.")));

      assertEquals(FinishReason.STOP, response.finishReason());
      assertTrue(response.content().contains("391"), response.content());
    }
  }

  @Test
  void streamingIteratorDeliversASonnet55TurnWithoutUpFrontThinking() {
    try (var sonnet = modelFor(AnthropicModelId.CLAUDE_SONNET_5_5.id(), ThinkingLevel.NONE);
        var iterator =
            sonnet.chatStream(
                List.of(Message.user("Count from 1 to 3, one per line.")), List.of())) {
      var text = new StringBuilder();
      StreamEvent.Done done = null;
      while (iterator.hasNext()) {
        var event = iterator.next();
        assertFalse(event instanceof StreamEvent.Error, () -> "stream error: " + event);
        if (event instanceof StreamEvent.TextDelta(String delta)) {
          text.append(delta);
        } else if (event instanceof StreamEvent.Done d) {
          done = d;
        }
      }

      assertNotNull(done);
      assertEquals(FinishReason.STOP, done.response().finishReason());
      assertTrue(text.toString().contains("3"), text.toString());
    }
  }

  @Test
  void opus47ChatWithAdaptiveThinking() {
    // 1.1.5 bug #2: Opus 4.7 rejected the legacy thinking shape with 400 invalid_request_error.
    // After dispatching to thinking.type=adaptive + output_config.effort, the call must succeed.
    // This is the regression test that fails in 1.1.4 and passes in 1.1.5.
    var config =
        ModelConfig.newBuilder().withApiKey(apiKey).withThinkingLevel(ThinkingLevel.MEDIUM).build();
    var opus47 = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var response = opus47.chat(List.of(Message.user("What is 2+2? Think briefly.")));

    assertNotNull(response, "Opus 4.7 with thinking=MEDIUM must return a response (not 400)");
    assertNotNull(response.content());
    assertFalse(response.content().isBlank());
  }

  @Test
  void opus48ChatWithAdaptiveThinking() {
    // Validates the claude-opus-4-8 wire id is live and the adaptive thinking shape is accepted.
    var config =
        ModelConfig.newBuilder().withApiKey(apiKey).withThinkingLevel(ThinkingLevel.MEDIUM).build();
    var opus48 = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_8, config);

    var response = opus48.chat(List.of(Message.user("What is 2+2? Think briefly.")));

    assertNotNull(response, "Opus 4.8 with thinking=MEDIUM must return a response (not 400)");
    assertNotNull(response.content());
    assertFalse(response.content().isBlank());
    assertTrue(response.content().contains("4"));
  }

  @Test
  void fullToolRoundTrip() {
    var searchPeople =
        Tool.newBuilder()
            .withName("search_people")
            .withDescription("Finds people using semantic search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Natural language description of who to find")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor(
                (args, ctx) ->
                    ToolResult.success("[{\"name\":\"Alice\",\"headline\":\"AI researcher\"}]"))
            .build();

    var messages =
        List.of(
            Message.system(
                "You are a helpful assistant. Use the search_people tool when asked to find people."),
            Message.user("Find me AI researchers"));

    var response1 = model.chat(messages, List.of(searchPeople));
    assertNotNull(response1);
    assertEquals(FinishReason.TOOL_CALLS, response1.finishReason());
    assertFalse(response1.toolCalls().isEmpty());

    var toolCall = response1.toolCalls().getFirst();
    var toolResult = searchPeople.execute(toolCall.arguments(), ToolContext.noop());

    var messages2 = new ArrayList<>(messages);
    messages2.add(response1.toMessage());
    messages2.add(Message.tool(toolCall.id(), toolCall.name(), toolResult.output()));

    var response2 = model.chat(messages2, List.of(searchPeople));
    assertNotNull(response2);
    assertNotNull(response2.content());
    assertEquals(FinishReason.STOP, response2.finishReason());
    assertTrue(response2.content().toLowerCase().contains("alice"));
  }

  public record Person(String name, int age, String occupation) {}

  public enum Component {
    OutputText,
    Table
  }

  @Description("Simple text block. Default component for basic responses.")
  public record OutputTextProps(@Description("The text content") String text) {}

  @Description("Use to display tabular data.")
  public record TableProps(
      @Description("Column headers") List<String> columns,
      @Description("Row data") List<List<String>> rows) {}

  public record UiResponse(
      @Description("The UI component to render") Component component,
      @Nullable @Description("Text output") OutputTextProps outputText,
      @Nullable @Description("Table output") TableProps table) {}

  @Test
  void chatWithStructuredOutput() {
    var messages =
        List.of(
            Message.user(
                "Extract the person info: John Smith is a 35-year-old software engineer."));

    var response = model.chat(messages, OutputSchema.of(Person.class));

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(response.hasParsed(), "Expected parsed output to be present");

    var person = response.parsed();
    assertNotNull(person);
    assertEquals("John Smith", person.name());
    assertEquals(35, person.age());
    assertTrue(
        person.occupation().toLowerCase().contains("software")
            || person.occupation().toLowerCase().contains("engineer"));
  }

  @Test
  void chatWithGenerativeUi() {
    var messages =
        List.of(
            Message.system(
                "You are a UI renderer. Respond using the structured output schema. "
                    + "Choose the most appropriate component for the user's request."),
            Message.user(
                "List the first 5 prime numbers with their ordinal position"
                    + " (1st, 2nd, etc.)"));

    var response = model.chat(messages, OutputSchema.of(UiResponse.class));

    assertNotNull(response);
    assertTrue(response.hasParsed(), "Expected parsed output");

    var ui = response.parsed();
    assertEquals(Component.Table, ui.component());
    assertNotNull(ui.table(), "Expected table props");
    assertFalse(ui.table().columns().isEmpty(), "Expected columns");
    assertFalse(ui.table().rows().isEmpty(), "Expected rows");
  }
}
