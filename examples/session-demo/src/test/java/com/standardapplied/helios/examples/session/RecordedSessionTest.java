/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicPricing;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.anthropic.CachePolicy;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.SummarizingCompactor;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.SampleDocuments;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recorded live sessions replayed offline through the real provider clients and an {@code
 * AgentSession}: the counterparts of the steps a live session test skips when the model does not
 * take them. The tools run for real against a workspace seeded as it was when each session was
 * recorded.
 *
 * <p>{@code anthropic/sessions/progress-loop} is a live claude-opus-5-5 matchmaking session under
 * {@code Display.PROGRESS}, recorded 2026-10-09.
 *
 * <p>{@code gemini/sessions/grounded} is a live gemini-3.5-flash Google Search session, recorded
 * 2026-10-09.
 *
 * <p>{@code anthropic/sessions/file-tools} is a live claude-sonnet-4-6 file-tool session, recorded
 * 2026-10-09.
 *
 * <p>{@code gemini/sessions/file-tools} is a live gemini-3.5-flash file-tool session, recorded
 * 2026-10-09.
 *
 * <p>{@code anthropic/sessions/compaction} is a live claude-opus-5-5 {@link CompactingSession}
 * under {@code Display.SUMMARY}, recorded 2026-10-23.
 */
final class RecordedSessionTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String SECRET = "sk-test-CONFIDENTIAL-do-not-leak-789012";

  /** What one replayed session produced and every request it sent. */
  private record Replay(ResultMessage terminal, EventCollector events, List<String> bodies) {}

  @Test
  void claudeProgressLoopSurfacesItsNotesAndRunsEveryToolCall() {
    var replay =
        replay(
            "anthropic/sessions/progress-loop.sse",
            uri ->
                new AnthropicProvider()
                    .create(
                        AnthropicModelId.CLAUDE_OPUS_5_5.id(),
                        anthropicConfig(uri)
                            .withReasoning(new Reasoning.Effort(Level.MEDIUM, Display.PROGRESS))
                            .build(),
                        CachePolicy.shortLived()),
            model ->
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withTools(
                        List.of(
                            search("search_profiles", "query", "location"),
                            search("get_profile", "id")))
                    .withCostCalculator(AnthropicPricing.calculator(CachePolicy.shortLived())));

    var success = assertInstanceOf(ResultMessage.Success.class, replay.terminal());
    assertTrue(success.cost().microUsd() > 0);
    assertTrue(replay.events().eventsOf(QueryEvent.Error.class).isEmpty());
    assertEquals(8, replay.events().eventsOf(QueryEvent.ToolUse.class).size());
    assertEquals(8, replay.events().eventsOf(QueryEvent.ToolResult.class).size());
    var notesByTurn =
        replay.events().eventsOf(QueryEvent.AssistantThinking.class).stream()
            .collect(
                Collectors.groupingBy(
                    QueryEvent.AssistantThinking::turnIndex,
                    Collectors.mapping(QueryEvent.AssistantThinking::text, Collectors.joining())));
    assertEquals(2, notesByTurn.size());
    notesByTurn.values().forEach(notes -> assertFalse(notes.isBlank()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void claudeCompactionAfterASignedThinkingTurnReplaysNoThinking() {
    var replies =
        Arrays.asList(
            Golden.read("anthropic/sessions/compaction.sse").split(ModelHarness.NEXT_RESPONSE));
    var run = new CompactingSession.Run[1];

    var requests =
        ModelHarness.exchange(
            replies,
            uri ->
                new AnthropicProvider()
                    .create(
                        AnthropicModelId.CLAUDE_OPUS_5_5.id(),
                        anthropicConfig(uri)
                            .withReasoning(new Reasoning.Effort(Level.HIGH, Display.SUMMARY))
                            .build(),
                        CachePolicy.shortLived()),
            model -> run[0] = CompactingSession.run(model));

    assertInstanceOf(ResultMessage.Success.class, run[0].terminal());
    assertTrue(run[0].compactedASignedThinkingTurn());
    assertEquals(1, run[0].events().eventsOf(QueryEvent.ContextEdited.class).size());
    assertEquals(3, requests.size());
    var messages =
        (List<Map<String, Object>>)
            JSON.readValue(requests.getLast().body(), Map.class).get("messages");
    assertEquals(1, messages.size());
    var blocks = (List<Map<String, Object>>) messages.getFirst().get("content");
    assertEquals(List.of("text"), blocks.stream().map(block -> block.get("type")).toList());
    assertTrue(
        ((String) blocks.getFirst().get("text")).startsWith(SummarizingCompactor.SUMMARY_PREFIX));
  }

  @Test
  void groundedGeminiTurnSurfacesItsCitations() {
    var replay =
        replay(
            "gemini/sessions/grounded.sse",
            RecordedSessionTest::gemini,
            model -> SessionOptions.newBuilder().withModel(model));

    var success = assertInstanceOf(ResultMessage.Success.class, replay.terminal());
    assertFalse(success.citations().isEmpty());
    success.citations().forEach(citation -> assertNotNull(citation.sourceId()));
    var streamed = replay.events().eventsOf(QueryEvent.AssistantCitations.class);
    assertEquals(1, streamed.size());
    assertEquals(streamed.getFirst().citations().stream().distinct().toList(), success.citations());
  }

  @ParameterizedTest
  @ValueSource(strings = {"anthropic", "gemini"})
  void fileToolsRedactTheirOutputAndAttachWhatTheyRead(String provider, @TempDir Path corpus)
      throws IOException {
    seed(corpus);
    Function<URI, Model> modelAt =
        "anthropic".equals(provider)
            ? uri ->
                new AnthropicProvider()
                    .create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), anthropicConfig(uri).build())
            : RecordedSessionTest::gemini;

    var replay =
        replay(
            provider + "/sessions/file-tools.sse",
            modelAt,
            model ->
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withSecretRegistry(secrets())
                    .withTools(fileTools(corpus)));

    assertInstanceOf(ResultMessage.Success.class, replay.terminal());
    assertTrue(replay.events().eventsOf(QueryEvent.Error.class).isEmpty());
    var glob = output(replay, GlobTool.NAME, "**/*.md");
    assertTrue(glob.contains("intro.md") && glob.contains("guide.md"), glob);
    assertFalse(glob.contains("config.yaml"), glob);
    var grep = output(replay, GrepTool.NAME, "reactor");
    assertTrue(grep.contains("patterns.md"), grep);
    assertFalse(grep.contains("misc.md"), grep);
    var config = output(replay, ReadTool.NAME, "./config.yaml");
    assertTrue(config.contains("<redacted:OPENAI_KEY>"), config);
    replay.bodies().forEach(body -> assertFalse(body.contains(SECRET)));
    assertTrue(sentBase64Of(replay, SampleDocuments.pixelPng()));
    assertTrue(sentBase64Of(replay, SampleDocuments.helloWorldPdf()));
  }

  private static Replay replay(
      String fixture,
      Function<URI, Model> modelAt,
      Function<Model, SessionOptions.Builder> options) {
    var replies = Arrays.asList(Golden.read(fixture).split(ModelHarness.NEXT_RESPONSE));
    var events = new EventCollector();
    var terminal = new ResultMessage[1];
    var requests =
        ModelHarness.exchange(
            replies,
            modelAt,
            model -> {
              try (var session = AgentSession.open(options.apply(model).build())) {
                events.read(session.events());
                terminal[0] = session.runBlocking(UserMessage.text("Go."));
                events.awaitRunEnded();
              }
            });
    assertEquals(replies.size(), requests.size());
    return new Replay(
        terminal[0], events, requests.stream().map(request -> request.body()).toList());
  }

  /** The output of the one call to {@code tool} whose arguments name {@code argument}. */
  private static String output(Replay replay, String tool, String argument) {
    var results =
        replay.events().eventsOf(QueryEvent.ToolResult.class).stream()
            .filter(result -> result.call().name().equals(tool))
            .filter(result -> result.call().arguments().containsValue(argument))
            .toList();
    assertEquals(1, results.size(), tool + " " + argument);
    assertTrue(results.getFirst().result().succeeded(), tool + " " + argument);
    return results.getFirst().result().output();
  }

  private static boolean sentBase64Of(Replay replay, byte[] bytes) {
    var encoded = Base64.getEncoder().encodeToString(bytes);
    return replay.bodies().stream().anyMatch(body -> body.contains(encoded));
  }

  private static ModelConfig.Builder anthropicConfig(URI uri) {
    return ModelConfig.newBuilder().withApiKey("test-key").withBaseUrl(uri + "/v1/messages");
  }

  private static Model gemini(URI uri) {
    return new GeminiProvider()
        .create(
            GeminiModelId.GEMINI_3_5_FLASH.id(),
            ModelConfig.newBuilder().withApiKey("test-key").withBaseUrl(uri + "/v1beta").build());
  }

  private static ToolBinding search(String name, String... parameters) {
    var tool = Tool.newBuilder().withName(name).withDescription(name).withIdempotent(true);
    for (var parameter : parameters) {
      tool.withParameter(
          ToolParameter.newBuilder()
              .withName(parameter)
              .withType(ParameterType.STRING)
              .withDescription(parameter)
              .withRequired(true)
              .build());
    }
    var built = tool.withExecutor((arguments, context) -> ToolResult.success("ids: p7")).build();
    return ToolBinding.newBuilder(built).withCategory(ToolCategory.READ).build();
  }

  private static SecretRegistry secrets() {
    var registry = new SecretRegistry();
    registry.register("OPENAI_KEY", SECRET);
    return registry;
  }

  private static List<ToolBinding> fileTools(Path corpus) {
    var workspace = WorkspaceRoot.of(corpus);
    return List.of(
        ReadTool.binding(workspace), GrepTool.binding(workspace), GlobTool.binding(workspace));
  }

  private static void seed(Path corpus) throws IOException {
    write(corpus, "intro.md", "# Intro\n");
    write(corpus, "guide.md", "# Guide\n");
    write(
        corpus,
        "patterns.md",
        "# Architecture notes\nThe reactor pattern decouples producers and consumers.\n");
    write(corpus, "misc.md", "# Misc\nNothing relevant here.\n");
    write(corpus, "config.yaml", "service: backend\napi_key: " + SECRET + "\nregion: us-east-1\n");
    Files.write(corpus.resolve("pixel.png"), SampleDocuments.pixelPng());
    Files.write(corpus.resolve("greeting.pdf"), SampleDocuments.helloWorldPdf());
  }

  private static void write(Path corpus, String name, String text) throws IOException {
    Files.writeString(corpus.resolve(name), text, StandardCharsets.UTF_8);
  }
}
