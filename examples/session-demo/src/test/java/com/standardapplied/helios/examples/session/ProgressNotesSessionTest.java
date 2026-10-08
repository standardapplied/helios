/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.test.SseReplies;
import com.standardapplied.helios.core.test.StubHttpServer;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Claude model asked for {@code Display.PROGRESS} returns the notes it writes between tool calls
 * as thinking blocks; an {@code AgentSession} surfaces each as {@link QueryEvent.AssistantThinking}
 * ahead of the tool call it introduces. Runs offline against a stub of the Messages API.
 */
final class ProgressNotesSessionTest {

  private static final String NOTE = "Searching profiles for climate-hardware investors in Texas.";

  private static final String TOOL_TURN =
      "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":20}}}\n"
          + "data: {\"type\":\"content_block_start\",\"index\":0,"
          + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":0,"
          + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\""
          + NOTE
          + "\"}}\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":0,"
          + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"SIG-1\"}}\n"
          + "data: {\"type\":\"content_block_stop\",\"index\":0}\n"
          + "data: {\"type\":\"content_block_start\",\"index\":1,"
          + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\","
          + "\"name\":\"search_profiles\"}}\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":1,"
          + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":"
          + "\"{\\\"query\\\":\\\"climate investors\\\"}\"}}\n"
          + "data: {\"type\":\"content_block_stop\",\"index\":1}\n"
          + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
          + "\"usage\":{\"output_tokens\":30}}\n"
          + "data: {\"type\":\"message_stop\"}\n";

  private static final String ANSWER_TURN =
      "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":40}}}\n"
          + "data: {\"type\":\"content_block_start\",\"index\":0,"
          + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":0,"
          + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Meet p7.\"}}\n"
          + "data: {\"type\":\"content_block_stop\",\"index\":0}\n"
          + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
          + "\"usage\":{\"output_tokens\":5}}\n"
          + "data: {\"type\":\"message_stop\"}\n";

  @Test
  void progressNotesSurfaceAsAssistantThinkingAheadOfTheirToolCall() throws Exception {
    var events = new CollectingSubscriber();
    try (var server =
            StubHttpServer.start(
                InetAddress.getLoopbackAddress(),
                0,
                SseReplies.inOrder(List.of(TOOL_TURN, ANSWER_TURN)));
        var model =
            new AnthropicProvider()
                .create(
                    "claude-opus-5-5",
                    ModelConfig.newBuilder()
                        .withApiKey("test-key")
                        .withBaseUrl(server.uri() + "/v1/messages")
                        .withReasoning(new Reasoning.Effort(Level.MEDIUM, Display.PROGRESS))
                        .build());
        var session =
            AgentSession.create(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withTools(new ToolRegistry(List.of(searchProfiles())))
                    .build())) {
      session.events().subscribe(events);

      var terminal = session.runBlocking(UserMessage.text("Who should I meet?"));
      events.awaitDone();

      assertInstanceOf(ResultMessage.Success.class, terminal);
      var notes = events.eventsOf(QueryEvent.AssistantThinking.class);
      assertEquals(List.of(NOTE), notes.stream().map(QueryEvent.AssistantThinking::text).toList());
      var order =
          events.events().stream()
              .filter(
                  e -> e instanceof QueryEvent.AssistantThinking || e instanceof QueryEvent.ToolUse)
              .map(e -> e.getClass().getSimpleName())
              .toList();
      assertEquals(List.of("AssistantThinking", "ToolUse"), order);
      var first = server.requests().getFirst();
      assertEquals("thinking-display-updates-2026-08-18", first.headers().get("anthropic-beta"));
      assertEquals(
          Map.of("type", "adaptive", "display", "updates"),
          JsonMapper.builder().build().readValue(first.body(), Map.class).get("thinking"));
    }
  }

  private static ToolBinding searchProfiles() {
    var tool =
        Tool.newBuilder()
            .withName("search_profiles")
            .withDescription("Search member profiles by free-text query")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withType(ParameterType.STRING)
                    .withDescription("query")
                    .withRequired(true)
                    .build())
            .withIdempotent(true)
            .withExecutor((args, ctx) -> ToolResult.success("ids: p7"))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.SEARCH).build();
  }
}
