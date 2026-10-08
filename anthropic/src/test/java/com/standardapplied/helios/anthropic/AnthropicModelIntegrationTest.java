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
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.schema.Description;
import com.standardapplied.helios.core.schema.Nullable;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.ModelIntegrationContract;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicModelIntegrationTest extends ModelIntegrationContract {

  private static Model model;
  private static String apiKey;

  AnthropicModelIntegrationTest() {
    super("claude-sonnet-4-6", "anthropic", 1_000_000);
  }

  @BeforeAll
  static void setUp() {
    apiKey = System.getenv("ANTHROPIC_API_KEY");
    model = sonnet46(ModelConfig.newBuilder());
  }

  private static Model sonnet46(ModelConfig.Builder config) {
    return new AnthropicProvider()
        .create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), config.withApiKey(apiKey).build());
  }

  @Override
  protected Model model() {
    return model;
  }

  @Override
  protected Model model(ModelConfig.Builder config) {
    return sonnet46(config);
  }

  @Test
  void chatWithThinking() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(apiKey)
            .withReasoning(new Reasoning.Effort(Level.HIGH, Display.SUMMARY))
            .build();
    var thinkingModel =
        new AnthropicProvider().create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), config);

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

  private static final List<AnthropicModelId> LIVE_MODELS =
      List.of(
          AnthropicModelId.CLAUDE_OPUS_5_5,
          AnthropicModelId.CLAUDE_SONNET_5_5,
          AnthropicModelId.CLAUDE_FABLE_5_1);

  private static Model modelFor(String modelId, Reasoning reasoning) {
    return new AnthropicProvider()
        .create(
            modelId,
            ModelConfig.newBuilder()
                .withApiKey(apiKey)
                .withReasoning(reasoning)
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
      Model candidate, List<Message> opening, List<Tool> tools, String toolOutput) {
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

  /**
   * The documented reasoning matrix of a live model: absent, off where accepted, every level and
   * every display.
   */
  private static List<Reasoning> documentedReasonings(AnthropicModelId modelId) {
    var reasonings = new ArrayList<Reasoning>();
    reasonings.add(null);
    if (modelId == AnthropicModelId.CLAUDE_SONNET_5_5) {
      reasonings.add(new Reasoning.Off());
    }
    for (var level : EnumSet.range(Level.LOW, Level.MAX)) {
      reasonings.add(new Reasoning.Effort(level, Display.SUMMARY));
    }
    reasonings.add(new Reasoning.Effort(Level.LOW, Display.HIDDEN));
    reasonings.add(new Reasoning.Effort(Level.LOW, Display.PROGRESS));
    return reasonings;
  }

  @Test
  void the55AndFable51ModelsAcceptEveryDocumentedReasoning() {
    var calculator = AnthropicPricing.calculator(CachePolicy.shortLived());
    for (var modelId : LIVE_MODELS) {
      for (var reasoning : documentedReasonings(modelId)) {
        var label = modelId.id() + " " + reasoning;
        try (var candidate = modelFor(modelId.id(), reasoning)) {
          var response =
              availableOrSkip(
                  modelId,
                  () -> candidate.chat(List.of(Message.user("Reply with the single word: ok"))));

          assertEquals(FinishReason.STOP, response.finishReason(), label);
          assertFalse(response.content().isBlank(), label);
          assertTrue(calculator.cost(candidate.id(), response.usage()).microUsd() > 0, label);
        }
      }
    }
  }

  @Test
  void the55AndFable51ModelsReplayTheirThinkingBlocksAcrossAToolLoop() {
    var weatherTool =
        stringTool("get_weather", "Get the current weather for a location", "location");

    for (var modelId : LIVE_MODELS) {
      for (var reasoning :
          List.of(
              new Reasoning.Effort(Level.LOW, Display.SUMMARY),
              new Reasoning.Effort(Level.MAX, Display.PROGRESS))) {
        var label = modelId.id() + " " + reasoning;
        try (var candidate = modelFor(modelId.id(), reasoning)) {
          var turns =
              availableOrSkip(
                  modelId,
                  () ->
                      runToolLoop(
                          candidate,
                          List.of(
                              Message.user(
                                  "Use the get_weather tool for San Francisco and for Austin,"
                                      + " then compare them.")),
                          List.of(weatherTool),
                          "72°F, sunny"));

          assertTrue(turns.size() >= 2, label);
          assertTrue(turns.getFirst().hasToolCalls(), label);
          assertEquals(FinishReason.STOP, turns.getLast().finishReason(), label);
        }
      }
    }
  }

  @Test
  void sonnet55ReplaysAToolLoopWithUpFrontThinkingOff() {
    var weatherTool =
        stringTool("get_weather", "Get the current weather for a location", "location");
    try (var sonnet = modelFor(AnthropicModelId.CLAUDE_SONNET_5_5.id(), new Reasoning.Off())) {
      var turns =
          runToolLoop(
              sonnet,
              List.of(Message.user("Use the get_weather tool for Austin, then summarize it.")),
              List.of(weatherTool),
              "72°F, sunny");

      assertEquals(FinishReason.STOP, turns.getLast().finishReason());
    }
  }

  @Test
  void sonnet46SendsATopPOfPoint95AlongsideEffort() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(apiKey)
            .withReasoning(new Reasoning.Effort(Level.LOW, Display.SUMMARY))
            .withTopP(0.95)
            .withMaxOutputTokens(4_000);
    try (var sonnet = sonnet46(config)) {
      var response = sonnet.chat(List.of(Message.user("Reply with the single word: ok")));

      assertEquals(FinishReason.STOP, response.finishReason());
    }
  }

  /**
   * {@code call}'s result, or a skipped test when {@code modelId} is not available to this API key:
   * Fable 5.1 needs an organization with 30-day data retention.
   */
  private static <T> T availableOrSkip(AnthropicModelId modelId, Supplier<T> call) {
    try {
      return call.get();
    } catch (AnthropicException e) {
      assumeTrue(
          e.statusCode() != 403
              && e.statusCode() != 404
              && !e.getMessage().contains("data retention"),
          () -> modelId.id() + " is not available to this API key");
      throw e;
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
      for (var reasoning :
          List.<Reasoning>of(new Reasoning.Effort(Level.MEDIUM, Display.SUMMARY))) {
        var label = modelId.id() + " " + reasoning;
        try (var candidate = modelFor(modelId.id(), reasoning)) {
          var turns = runToolLoop(candidate, opening, tools, profile);

          assertEquals(FinishReason.STOP, turns.getLast().finishReason(), label);
          assertFalse(turns.getLast().content().isBlank(), label);
          assertTrue(
              turns.stream().anyMatch(turn -> turn.usage().cacheReadInputTokens() > 0),
              () -> label + ": no turn read the prompt cache");
        }
      }
    }

    try (var opus =
        modelFor(
            AnthropicModelId.CLAUDE_OPUS_5_5.id(),
            new Reasoning.Effort(Level.MEDIUM, Display.PROGRESS))) {
      var turns = runToolLoop(opus, opening, tools, profile);

      assertTrue(
          turns.stream().anyMatch(com.standardapplied.helios.core.model.Response::hasThinking),
          "the progress display returns the notes Opus 5.5 writes between tool calls as"
              + " thinking text instead of empty blocks");
    }
  }

  @Test
  void webSearchTurnThatFiltersResultsInCodeIsReplayable() {
    var saveNote = stringTool("save_note", "Save a note for the user", "text");

    for (var modelId : THE_55_MODELS) {
      var config =
          ModelConfig.newBuilder()
              .withApiKey(apiKey)
              .withReasoning(new Reasoning.Effort(Level.MEDIUM, Display.SUMMARY))
              .withMaxOutputTokens(16_000)
              .withWebSearch(true)
              .build();
      try (var candidate = new AnthropicProvider().create(modelId.id(), config)) {
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
                .anyMatch(turn -> turn.metadata().containsKey(RawContentEcho.RAW_CONTENT_KEY)),
            () -> modelId.id() + ": the search turn must be echoed from its raw content");
      }
    }
  }

  @Test
  void datedSnapshotIdKeepsItsFamilyRequestShape() {
    try (var haiku = modelFor("claude-haiku-4-5-20251001", new Reasoning.Off())) {
      var response = haiku.chat(List.of(Message.user("What is 17 * 23? Think it through.")));

      assertEquals(FinishReason.STOP, response.finishReason());
      assertTrue(response.content().contains("391"), response.content());
    }
  }

  @Test
  void streamingIteratorDeliversASonnet55TurnWithoutUpFrontThinking() {
    try (var sonnet = modelFor(AnthropicModelId.CLAUDE_SONNET_5_5.id(), new Reasoning.Off());
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
        ModelConfig.newBuilder()
            .withApiKey(apiKey)
            .withReasoning(new Reasoning.Effort(Level.MEDIUM, Display.SUMMARY))
            .build();
    var opus47 = new AnthropicProvider().create(AnthropicModelId.CLAUDE_OPUS_4_7.id(), config);

    var response = opus47.chat(List.of(Message.user("What is 2+2? Think briefly.")));

    assertNotNull(response, "Opus 4.7 with thinking=MEDIUM must return a response (not 400)");
    assertNotNull(response.content());
    assertFalse(response.content().isBlank());
  }

  @Test
  void opus48ChatWithAdaptiveThinking() {
    // Validates the claude-opus-4-8 wire id is live and the adaptive thinking shape is accepted.
    var config =
        ModelConfig.newBuilder()
            .withApiKey(apiKey)
            .withReasoning(new Reasoning.Effort(Level.MEDIUM, Display.SUMMARY))
            .build();
    var opus48 = new AnthropicProvider().create(AnthropicModelId.CLAUDE_OPUS_4_8.id(), config);

    var response = opus48.chat(List.of(Message.user("What is 2+2? Think briefly.")));

    assertNotNull(response, "Opus 4.8 with thinking=MEDIUM must return a response (not 400)");
    assertNotNull(response.content());
    assertFalse(response.content().isBlank());
    assertTrue(response.content().contains("4"));
  }

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
