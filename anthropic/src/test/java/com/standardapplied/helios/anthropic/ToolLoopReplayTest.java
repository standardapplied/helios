/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A recorded tool loop replayed through the Anthropic client. Every request after the first replays
 * the turns before it, signed thinking included, and repeats exactly the prefix the request before
 * it marked for the prompt cache, so the provider can serve that prefix from its cache. Whether it
 * does is the provider's decision, which no test asserts.
 *
 * <p>{@code progress-loop} is a live claude-opus-5-5 tool loop under {@code Display.PROGRESS},
 * three tool turns and a final answer, recorded 2026-10-09.
 */
class ToolLoopReplayTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final List<Tool> TOOLS =
      List.of(tool("search_profiles", "query", "location"), tool("get_profile", "id"));

  /**
   * One block of a request in prompt-cache order, as the JSON text Helios sent without its cache
   * breakpoint, and whether it carried one.
   */
  private record Block(String content, boolean breakpoint) {}

  /** The requests and responses of one replay of the recorded loop. */
  private record Replay(List<Map<String, Object>> requests, List<Response<Void>> responses) {}

  @Test
  void everyRequestRepeatsTheCachedPrefixOfTheRequestBefore() {
    var requests = replay().requests();

    assertEquals(4, requests.size());
    for (var turn = 0; turn + 1 < requests.size(); turn++) {
      var before = blocks(requests.get(turn));
      var after = blocks(requests.get(turn + 1));
      assertTrue(
          before.getLast().breakpoint(), "request " + turn + " left its last block uncached");
      for (var end = 0; end < before.size(); end++) {
        if (before.get(end).breakpoint()) {
          assertEquals(
              contents(before, end + 1),
              contents(after, end + 1),
              "request " + (turn + 1) + " changed the prefix request " + turn + " cached");
        }
      }
    }
  }

  @Test
  void everyRequestReplaysTheSignedThinkingOfTheTurnBefore() {
    var replay = replay();

    for (var turn = 0; turn + 1 < replay.requests().size(); turn++) {
      var signatures = thinkingSignatures(replay.requests().get(turn + 1));
      var thinking = ThinkingBlock.decodeAll(replay.responses().get(turn).metadata());
      assertFalse(thinking.isEmpty(), "turn " + turn + " thought");
      for (var block : thinking) {
        assertTrue(
            signatures.contains(block.signature()),
            "request " + (turn + 1) + " lost " + block.signature());
      }
    }
  }

  private static Replay replay() {
    var replies =
        Arrays.asList(
            Golden.read("anthropic/streams/progress-loop.sse").split(ModelHarness.NEXT_RESPONSE));
    var history =
        new ArrayList<>(
            List.of(
                Message.system("You are a matchmaking agent for a founders community."),
                Message.user("Find a seed investor and a supply-chain cofounder in Austin.")));
    var responses = new ArrayList<Response<Void>>();
    var requests =
        ModelHarness.exchange(
            replies,
            uri ->
                new AnthropicProvider()
                    .create(
                        AnthropicModelId.CLAUDE_OPUS_5_5.id(),
                        ModelConfig.newBuilder()
                            .withApiKey("test-key")
                            .withBaseUrl(uri + "/v1/messages")
                            .withReasoning(new Reasoning.Effort(Level.MEDIUM, Display.PROGRESS))
                            .build(),
                        CachePolicy.shortLived()),
            model -> {
              for (var turn = 0; turn < replies.size(); turn++) {
                var response = model.chat(history, TOOLS);
                responses.add(response);
                history.add(response.toMessage());
                for (var call : response.toolCalls()) {
                  history.add(Message.tool(call.id(), call.name(), "ids: p7, p12, p19"));
                }
              }
            });
    return new Replay(requests.stream().map(request -> parse(request.body())).toList(), responses);
  }

  /**
   * The blocks of {@code request} in the order the prompt cache reads them: tools, then system,
   * then every message's content blocks, each tagged with its message's role. A message whose
   * content is a string is one text block: the API reads it so, and the recorded loop read from the
   * cache a prefix holding the first user message in both forms.
   */
  private static List<Block> blocks(Map<String, Object> request) {
    var blocks = new ArrayList<Block>();
    maps(request.get("tools")).forEach(tool -> blocks.add(block("tool", tool)));
    maps(request.get("system")).forEach(system -> blocks.add(block("system", system)));
    for (var message : maps(request.get("messages"))) {
      for (var content : contentBlocks(message)) {
        blocks.add(block((String) message.get("role"), content));
      }
    }
    return blocks;
  }

  private static List<Map<String, Object>> contentBlocks(Map<String, Object> message) {
    if (message.get("content") instanceof String text) {
      var block = new LinkedHashMap<String, Object>();
      block.put("type", "text");
      block.put("text", text);
      return List.of(block);
    }
    return maps(message.get("content"));
  }

  private static Block block(String role, Map<String, Object> content) {
    var copy = new LinkedHashMap<>(content);
    var breakpoint = copy.remove("cache_control") != null;
    return new Block(role + " " + JSON.writeValueAsString(copy), breakpoint);
  }

  private static List<String> contents(List<Block> blocks, int end) {
    return blocks.subList(0, end).stream().map(Block::content).toList();
  }

  private static List<String> thinkingSignatures(Map<String, Object> request) {
    return maps(request.get("messages")).stream()
        .flatMap(message -> contentBlocks(message).stream())
        .filter(content -> "thinking".equals(content.get("type")))
        .map(content -> (String) content.get("signature"))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String body) {
    return JSON.readValue(body, Map.class);
  }

  private static Tool tool(String name, String... parameters) {
    var tool = Tool.newBuilder().withName(name).withDescription(name);
    for (var parameter : parameters) {
      tool.withParameter(
          ToolParameter.newBuilder()
              .withName(parameter)
              .withType(ParameterType.STRING)
              .withDescription(parameter)
              .withRequired(true)
              .build());
    }
    return tool.withExecutor((arguments, context) -> ToolResult.success("ok")).build();
  }
}
