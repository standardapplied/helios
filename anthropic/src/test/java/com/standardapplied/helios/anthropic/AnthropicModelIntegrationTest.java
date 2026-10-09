/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.schema.Description;
import com.standardapplied.helios.core.schema.Nullable;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Accepted;
import com.standardapplied.helios.core.test.ModelIntegrationContract;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link ModelIntegrationContract} and the Anthropic-specific live cases. Each asserts only that
 * the API accepted what Helios sent and that Helios read the reply; a step only the model decides,
 * such as writing thinking or calling a tool on a model that rejects forced tool use, is an
 * assumption whose message names the recorded test that covers it offline.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicModelIntegrationTest extends ModelIntegrationContract {

  private static final String STRUCTURED_COUNTERPART =
      "AnthropicStreamTranscriptTest#aStructuredReplyParsesIntoItsRecord";

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

  @Override
  protected Optional<String> unconstrainedStructuredOutputCounterpart() {
    return Optional.of(STRUCTURED_COUNTERPART);
  }

  @Test
  void chatWithThinking() {
    var config =
        ModelConfig.newBuilder().withReasoning(new Reasoning.Effort(Level.HIGH, Display.SUMMARY));
    var messages =
        new ArrayList<>(
            List.of(
                Message.user(
                    "Three friends split a bill of $187.40 so that Ana pays twice what Ben pays and"
                        + " Cy pays $12 more than Ben. How much does each pay? Verify the total.")));
    try (var thinkingModel = sonnet46(config)) {
      var response = Accepted.textReply(thinkingModel.chat(messages));

      assumeTrue(
          response.hasThinking(),
          "claude-sonnet-4-6 wrote no thinking; AnthropicStreamTranscriptTest \"thinking\" covers"
              + " the parsing");
      assertFalse(ThinkingBlock.decodeAll(response.metadata()).isEmpty());
      messages.add(response.toMessage());
      messages.add(Message.user("Now split it four ways evenly."));
      Accepted.textReply(thinkingModel.chat(messages));
    }
  }

  // ── Opus 5.5 / Sonnet 5.5 / always-on models ──────────────────────────────

  private static final int MAX_TURNS = 8;

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
   * Drive a tool loop, answering every tool call with {@code toolOutput}, and return each turn's
   * response. Every turn after the first replays the previous assistant turns, thinking blocks
   * included, so a request the API would reject for a modified or misplaced block fails here. The
   * loop ends at a turn without a tool call, at the turn that replays the first turn matching
   * {@code tested}, or after {@code maxTurns}: how many turns the model takes is its choice, never
   * a failure.
   */
  private static List<Response<Void>> runToolLoop(
      Model candidate,
      List<Message> opening,
      List<Tool> tools,
      String toolOutput,
      Predicate<Response<Void>> tested,
      int maxTurns) {
    var history = new ArrayList<>(opening);
    var turns = new ArrayList<Response<Void>>();
    for (var turn = 0; turn < maxTurns; turn++) {
      var response = candidate.chat(history, tools);
      turns.add(response);
      var replayedTheTestedTurn = turn > 0 && tested.test(turns.get(turn - 1));
      if (!response.hasToolCalls() || replayedTheTestedTurn) {
        return turns;
      }
      history.add(response.toMessage());
      for (var call : response.toolCalls()) {
        history.add(Message.tool(call.id(), call.name(), toolOutput));
      }
    }
    return turns;
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

          Accepted.textReply(response);
          assertTrue(calculator.cost(candidate.id(), response.usage()).microUsd() > 0, label);
        }
      }
    }
  }

  @Test
  void the55AndFable51ModelsReplayTheirThinkingBlocksAcrossAToolLoop() {
    var weatherTool =
        stringTool("get_weather", "Get the current weather for a location", "location");
    var calledNoTool = new ArrayList<String>();

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
                          "72°F, sunny",
                          Response::hasToolCalls,
                          MAX_TURNS));

          turns.forEach(Accepted::toolTurn);
          if (!turns.getFirst().hasToolCalls()) {
            calledNoTool.add(label);
          }
        }
      }
    }
    assumeTrue(calledNoTool.isEmpty(), () -> noToolCall(String.join(", ", calledNoTool)));
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
              "72°F, sunny",
              Response::hasToolCalls,
              MAX_TURNS);

      turns.forEach(Accepted::toolTurn);
      assumeTrue(
          turns.getFirst().hasToolCalls(),
          () -> noToolCall(AnthropicModelId.CLAUDE_SONNET_5_5.id() + " " + new Reasoning.Off()));
    }
  }

  /**
   * The message of a skip for a first turn that called no tool, naming its recorded counterpart.
   */
  private static String noToolCall(String label) {
    return label
        + " called no tool on the first turn; ToolLoopReplayTest covers the replay of a"
        + " recorded tool loop";
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
      Accepted.textReply(sonnet.chat(List.of(Message.user("Reply with the single word: ok"))));
    }
  }

  /**
   * {@code call}'s result, or a skipped test when {@code modelId} is Fable 5.1 and not available to
   * this API key: Fable 5.1 needs an organization with 30-day data retention. Any other model's
   * rejection fails, since its id is one Helios sends.
   */
  private static <T> T availableOrSkip(AnthropicModelId modelId, Supplier<T> call) {
    try {
      return call.get();
    } catch (AnthropicException e) {
      var unavailable =
          e.statusCode() == 403
              || e.statusCode() == 404
              || e.getMessage().contains("data retention");
      assumeTrue(
          modelId != AnthropicModelId.CLAUDE_FABLE_5_1 || !unavailable,
          () -> modelId.id() + " is not available to this API key");
      throw e;
    }
  }

  @Test
  void the55ModelsToolLoopsAreAcceptedUnderSummaryAndProgressDisplay() {
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
      try (var candidate =
          modelFor(modelId.id(), new Reasoning.Effort(Level.MEDIUM, Display.SUMMARY))) {
        runToolLoop(candidate, opening, tools, profile, turn -> false, MAX_TURNS)
            .forEach(Accepted::toolTurn);
      }
    }

    var opusId = AnthropicModelId.CLAUDE_OPUS_5_5.id();
    try (var opus = modelFor(opusId, new Reasoning.Effort(Level.MEDIUM, Display.PROGRESS))) {
      var turns = runToolLoop(opus, opening, tools, profile, Response::hasThinking, MAX_TURNS);
      turns.forEach(Accepted::toolTurn);

      var noted = turns.stream().filter(Response::hasThinking).findFirst();
      assumeTrue(
          noted.isPresent(),
          opusId
              + " wrote no progress note; AnthropicStreamTranscriptTest \"progress-notes\" covers"
              + " the parsing");
      assertFalse(ThinkingBlock.decodeAll(noted.get().metadata()).isEmpty());
    }
  }

  @Test
  void webSearchTurnThatFiltersResultsInCodeIsReplayable() {
    var saveNote = stringTool("save_note", "Save a note for the user", "text");
    Predicate<Response<Void>> echoed =
        turn -> turn.metadata().containsKey(RawContentEcho.RAW_CONTENT_KEY);
    var replayedNoRawContent = new ArrayList<String>();

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
                "saved",
                echoed,
                MAX_TURNS);
        turns.forEach(Accepted::toolTurn);
        if (turns.subList(0, turns.size() - 1).stream().noneMatch(echoed)) {
          replayedNoRawContent.add(modelId.id());
        }
      }
    }
    assumeTrue(
        replayedNoRawContent.isEmpty(),
        () ->
            String.join(", ", replayedNoRawContent)
                + " sent no turn carrying its raw content before a tool call;"
                + " AnthropicStreamTranscriptTest \"server-tool\" covers the raw-content echo");
  }

  @Test
  void datedSnapshotIdKeepsItsFamilyRequestShape() {
    try (var haiku = modelFor("claude-haiku-4-5-20251001", new Reasoning.Off())) {
      Accepted.textReply(haiku.chat(List.of(Message.user("What is 17 * 23? Think it through."))));
    }
  }

  @Test
  void streamingIteratorDeliversASonnet55TurnWithoutUpFrontThinking() {
    try (var sonnet = modelFor(AnthropicModelId.CLAUDE_SONNET_5_5.id(), new Reasoning.Off())) {
      Accepted.textReply(
          Accepted.stream(
              sonnet.chatStream(
                  List.of(Message.user("Count from 1 to 3, one per line.")), List.of())));
    }
  }

  @Test
  void opus47ChatWithAdaptiveThinking() {
    adaptiveThinkingIsAccepted(AnthropicModelId.CLAUDE_OPUS_4_7);
  }

  @Test
  void opus48ChatWithAdaptiveThinking() {
    adaptiveThinkingIsAccepted(AnthropicModelId.CLAUDE_OPUS_4_8);
  }

  /**
   * {@code modelId} accepts {@code thinking.type=adaptive} with {@code output_config.effort}; Opus
   * 4.7 answered the legacy thinking shape with a 400 before 1.1.5.
   */
  private static void adaptiveThinkingIsAccepted(AnthropicModelId modelId) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(apiKey)
            .withReasoning(new Reasoning.Effort(Level.MEDIUM, Display.SUMMARY))
            .build();
    try (var opus = new AnthropicProvider().create(modelId.id(), config)) {
      Accepted.textReply(opus.chat(List.of(Message.user("What is 2+2? Think briefly."))));
    }
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

    var response =
        Accepted.parsedOrSkip(
            AnthropicModelId.CLAUDE_SONNET_4_6.id(),
            STRUCTURED_COUNTERPART,
            () -> model.chat(messages, OutputSchema.of(UiResponse.class)));

    assertNotNull(response.parsed().component());
  }
}
