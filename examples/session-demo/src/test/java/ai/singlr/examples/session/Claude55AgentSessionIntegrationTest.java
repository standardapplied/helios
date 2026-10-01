/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.examples.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.anthropic.AnthropicModelId;
import ai.singlr.anthropic.AnthropicPricing;
import ai.singlr.anthropic.AnthropicProvider;
import ai.singlr.anthropic.CachePolicy;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.AgentSession;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Claude Opus 5.5 and Sonnet 5.5 driving a real {@code AgentSession} through a search-and-read tool
 * loop, the shape of a matchmaking agent. Verifies end to end what only live traffic can: the
 * session replays each model's thinking blocks turn after turn, surfaces the notes they write
 * between tool calls as {@link QueryEvent.AssistantThinking}, reads the prompt cache, and prices
 * the run from {@link AnthropicPricing}.
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
    var run = runMatchmaking(AnthropicModelId.CLAUDE_OPUS_5_5, ThinkingLevel.MEDIUM);

    assertTrue(
        run.events().stream()
            .anyMatch(e -> e instanceof QueryEvent.AssistantThinking t && !t.text().isBlank()),
        "Opus 5.5 writes its between-tool-call notes as thinking; the session must surface them");
  }

  @Test
  void sonnet55RunsTheToolLoopWithoutUpFrontThinking() {
    runMatchmaking(AnthropicModelId.CLAUDE_SONNET_5_5, ThinkingLevel.NONE);
  }

  private record Run(ResultMessage.Success result, List<QueryEvent> events) {}

  private static Run runMatchmaking(AnthropicModelId modelId, ThinkingLevel level) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("ANTHROPIC_API_KEY"))
            .withThinkingLevel(level)
            .withMaxOutputTokens(16_000)
            .build();
    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var model =
            new AnthropicProvider().create(modelId.id(), config, CachePolicy.shortLived());
        var session =
            AgentSession.create(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withTools(new ToolRegistry(List.of(searchProfiles(), getProfile())))
                    .withSystemPrompt(SYSTEM_PROMPT)
                    .withCostCalculator(AnthropicPricing.calculator(CachePolicy.shortLived()))
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(12).build())
                    .build())) {
      session.events().subscribe(collector(events));

      var terminal = session.runBlocking(UserMessage.text(VIEWER));

      var success =
          assertInstanceOf(
              ResultMessage.Success.class, terminal, () -> modelId.id() + " ended as " + terminal);
      assertTrue(!success.result().isBlank(), modelId.id());
      assertTrue(
          events.stream().filter(QueryEvent.ToolUse.class::isInstance).count() >= 2,
          () -> modelId.id() + " must have called its tools");
      assertTrue(
          success.usage().cacheReadInputTokens() > 0,
          () -> modelId.id() + " never read the prompt cache: " + success.usage());
      assertTrue(
          success.cost().microUsd() > 0,
          () -> modelId.id() + " must be priced by the rate card: " + success.cost());
      return new Run(success, List.copyOf(events));
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
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.SEARCH).build();
  }

  private static Flow.Subscriber<QueryEvent> collector(List<QueryEvent> sink) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(QueryEvent event) {
        sink.add(event);
      }

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {}
    };
  }
}
