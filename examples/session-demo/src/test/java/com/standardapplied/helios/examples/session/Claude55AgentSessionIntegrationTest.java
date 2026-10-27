/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicPricing;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.anthropic.CachePolicy;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Claude Opus 5.5 and Sonnet 5.5 driving a real {@code AgentSession} through a search-and-read tool
 * loop, the shape of a matchmaking agent. Verifies end to end what only live traffic can: the API
 * accepts every turn the session sends, thinking blocks replayed, and the run is priced from {@link
 * AnthropicPricing}. Whether the model calls its tools or writes progress notes is its choice;
 * {@code RecordedSessionTest} replays a recorded run that does both.
 *
 * <p>Guarded by {@code ANTHROPIC_API_KEY} so the suite stays runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
final class Claude55AgentSessionIntegrationTest {

  private static final String SYSTEM_PROMPT =
      "You are a matchmaking agent for a founders community. Work in steps: form hypotheses about"
          + " who the viewer should meet, run several different searches, read the most promising"
          + " profiles, and keep the user informed of what you found and what you will do next as"
          + " you go.";

  private static final String VIEWER =
      "Viewer: a climate-hardware founder in Austin seeking a seed investor and a supply-chain"
          + " cofounder. Find the best two matches. Run at least four searches and read at least"
          + " three profiles before answering.";

  @Test
  void opus55RunsTheToolLoopAndSurfacesItsProgressNotes() {
    var events =
        runMatchmaking(
            AnthropicModelId.CLAUDE_OPUS_5_5, new Reasoning.Effort(Level.MEDIUM, Display.PROGRESS));

    assumeTrue(
        !events.eventsOf(QueryEvent.AssistantThinking.class).isEmpty(),
        "claude-opus-5-5 wrote no progress note; RecordedSessionTest"
            + "#claudeProgressLoopSurfacesItsNotesAndRunsEveryToolCall covers the surfacing");
  }

  @Test
  void sonnet55RunsTheToolLoopWithoutUpFrontThinking() {
    runMatchmaking(AnthropicModelId.CLAUDE_SONNET_5_5, new Reasoning.Off());
  }

  @Test
  void opus55CompactsAfterASignedThinkingTurnAndTheNextRequestIsAccepted() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("ANTHROPIC_API_KEY"))
            .withReasoning(new Reasoning.Effort(Level.HIGH, Display.SUMMARY))
            .withMaxOutputTokens(16_000)
            .build();
    try (var model =
        new AnthropicProvider()
            .create(AnthropicModelId.CLAUDE_OPUS_5_5.id(), config, CachePolicy.shortLived())) {
      var run = CompactingSession.run(model);

      assertTrue(
          run.terminal() instanceof ResultMessage.Success
              || run.terminal() instanceof ResultMessage.ErrorMaxTurns,
          () -> "claude-opus-5-5 ended as " + run.terminal());
      assertTrue(run.events().eventsOf(QueryEvent.Error.class).isEmpty());
      assumeTrue(
          run.compactedASignedThinkingTurn(),
          "claude-opus-5-5 called no tool after a signed thinking block in its first turn;"
              + " RecordedSessionTest#claudeCompactionAfterASignedThinkingTurnReplaysNoThinking"
              + " covers the compaction");
      assertEquals(1, run.events().eventsOf(QueryEvent.ContextEdited.class).size());
    }
  }

  /**
   * Two runs of one session on Opus 5.5 at high effort: the first states a fact and may read a
   * profile, the second asks about that fact. The API accepts both runs, each ends in an accepted
   * terminal with no {@code Error} event, and the second run's first request carries the first
   * run's messages; what the model answers is not asserted. {@code SessionRunsTest} proves the
   * conversation carried offline.
   */
  @Test
  void opus55SecondRunOfASessionCarriesTheFirstRunsConversation() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("ANTHROPIC_API_KEY"))
            .withReasoning(new Reasoning.Effort(Level.HIGH, Display.SUMMARY))
            .withMaxOutputTokens(16_000)
            .build();
    var histories = new CopyOnWriteArrayList<List<Message>>();
    PreModelTurnHook recording =
        (history, ctx) -> {
          if (ctx.turnIndex() == 1) {
            histories.add(history);
          }
          return HookOutcome.cont();
        };
    var first =
        "My project's code name is Heliotrope. Read profile p7 with get_profile and tell me in one"
            + " sentence who it is.";
    try (var model =
            new AnthropicProvider()
                .create(AnthropicModelId.CLAUDE_OPUS_5_5.id(), config, CachePolicy.shortLived());
        var session =
            AgentSession.open(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withTools(List.of(getProfile()))
                    .withHook(recording)
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(6).build())
                    .build())) {
      var events = new EventCollector(QuestionAnswers.selecting(session, "Deny"));
      events.read(session.events());

      var runOne = session.send(first).join();
      var runTwo = session.send("What is my project's code name?").join();

      for (var terminal : List.of(runOne, runTwo)) {
        assertTrue(
            terminal instanceof ResultMessage.Success
                || terminal instanceof ResultMessage.ErrorMaxTurns,
            () -> "claude-opus-5-5 ended a run as " + terminal);
      }
      session.close();
      events.awaitDone();
      assertTrue(events.eventsOf(QueryEvent.Error.class).isEmpty());
      assertEquals(2, events.eventsOf(QueryEvent.RunEnded.class).size());
      assertTrue(
          histories.get(1).stream().anyMatch(message -> first.equals(message.content())),
          "the second run's first request carries the first run's message");
    }
  }

  /**
   * Runs the matchmaking session and asserts what holds whatever the model does: the run ends in
   * success or at the turn limit, no turn failed, and the run is priced. That it called its tools
   * is the model's choice, so a run that did not skips.
   */
  private static EventCollector runMatchmaking(AnthropicModelId modelId, Reasoning reasoning) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("ANTHROPIC_API_KEY"))
            .withReasoning(reasoning)
            .withMaxOutputTokens(16_000)
            .build();
    try (var model =
            new AnthropicProvider().create(modelId.id(), config, CachePolicy.shortLived());
        var session =
            AgentSession.open(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withTools(List.of(searchProfiles(), getProfile()))
                    .withSystemPrompt(SYSTEM_PROMPT)
                    .withCostCalculator(AnthropicPricing.calculator(CachePolicy.shortLived()))
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(12).build())
                    .build())) {
      var events = new EventCollector(QuestionAnswers.selecting(session, "Deny"));
      events.read(session.events());

      var terminal = session.runBlocking(UserMessage.text(VIEWER));
      events.awaitRunEnded();

      assertTrue(
          terminal instanceof ResultMessage.Success
              || terminal instanceof ResultMessage.ErrorMaxTurns,
          () -> modelId.id() + " ended as " + terminal);
      assertTrue(events.eventsOf(QueryEvent.Error.class).isEmpty(), modelId.id());
      assertTrue(
          terminal.cost().microUsd() > 0,
          () -> modelId.id() + " must be priced by the rate card: " + terminal.cost());
      assumeTrue(
          events.eventsOf(QueryEvent.ToolUse.class).size() >= 2,
          modelId.id()
              + " made fewer than two tool calls; RecordedSessionTest"
              + "#claudeProgressLoopSurfacesItsNotesAndRunsEveryToolCall covers the tool loop");
      return events;
    }
  }

  private static ToolBinding searchProfiles() {
    return binding(
        "search_profiles",
        "Search member profiles by free-text query; returns matching profile ids",
        "query",
        "ids: p7, p12, p19");
  }

  private static ToolBinding getProfile() {
    return binding(
        "get_profile",
        "Read one member profile by id",
        "id",
        "Operator turned angel, hardware supply chain background, based in Texas, seeking"
            + " early-stage climate deals. Built two factories.");
  }

  private static ToolBinding binding(
      String name, String description, String parameter, String output) {
    var tool =
        Tool.newBuilder()
            .withName(name)
            .withDescription(description)
            .withParameter(
                ToolParameter.newBuilder()
                    .withName(parameter)
                    .withType(ParameterType.STRING)
                    .withDescription(parameter)
                    .withRequired(true)
                    .build())
            .withIdempotent(true)
            .withExecutor((args, ctx) -> ToolResult.success(output))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
  }
}
