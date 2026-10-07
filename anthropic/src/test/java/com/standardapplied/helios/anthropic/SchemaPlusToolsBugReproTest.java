/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.core.test.SseEvents.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.SystemContent;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the Light Grid bug report (2026-05-22): {@code outputSchema + tools} used
 * to terminate a tool-using session on turn 1 with {@code Failed to parse structured output}.
 *
 * <p>Two cooperating fixes landed in 2.3.3:
 *
 * <ol>
 *   <li>A model's {@link com.standardapplied.helios.core.model.Model#chat(List, List,
 *       com.standardapplied.helios.core.schema.OutputSchema)} skips parsing when {@code
 *       response.toolCalls()} is non-empty — tool-calling turns are intermediate, structured output
 *       is the deliverable of a later text-only turn.
 *   <li>{@link AnthropicRequestBuilder#build} rephrases the schema instruction when tools are
 *       present so it stops fighting the deployer's "use tools first" guidance.
 * </ol>
 */
class SchemaPlusToolsBugReproTest {

  public record SimpleAnswer(String text) {}

  private static Tool searchTool() {
    return Tool.newBuilder()
        .withName("search")
        .withDescription("Search the knowledge base")
        .withExecutor((args, ctx) -> ToolResult.success("ok"))
        .build();
  }

  private static AnthropicRequestBuilder requests(ModelConfig config) {
    var model = AnthropicModelId.CLAUDE_SONNET_4_6;
    return new AnthropicRequestBuilder(model.id(), model, config, CachePolicy.shortLived());
  }

  // ---------------------------------------------------------------------------------------------
  // Claim #1 — schema instruction is contextualised when tools are present
  // ---------------------------------------------------------------------------------------------

  @Test
  void buildRequestWithSchemaAndToolsAppendsTheTurnAwareInstruction() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(config);
    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());

    var request =
        requests.build(
            List.of(Message.user("Find me three matches.")), List.of(searchTool()), schema);

    var system = (List<?>) request.system();
    assertEquals(1, system.size());
    var systemText = ((SystemContent) system.getFirst()).text();
    assertTrue(
        systemText.contains("You may call the available tools"),
        "tool-using schema instruction must acknowledge the loop; system=\n" + systemText);
    assertTrue(
        systemText.contains("When you are ready to emit your final answer"),
        "tool-using schema instruction must defer JSON to the final turn; system=\n" + systemText);
    assertFalse(
        systemText.contains("You must respond with valid JSON"),
        "the unconditional 'must respond with JSON' phrasing must not fire on a tool-using"
            + " session; system=\n"
            + systemText);
  }

  @Test
  void buildRequestWithSchemaButNoToolsKeepsTheBareJsonInstruction() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(config);
    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());

    var request = requests.build(List.of(Message.user("Extract")), List.of(), schema);

    var system = (List<?>) request.system();
    assertEquals(1, system.size());
    var systemText = ((SystemContent) system.getFirst()).text();
    assertTrue(
        systemText.contains("You must respond with valid JSON"),
        "tool-less schema instruction stays bare; system=\n" + systemText);
    assertFalse(
        systemText.contains("You may call the available tools"),
        "the loop-aware phrasing only applies when tools are present; system=\n" + systemText);
  }

  // ---------------------------------------------------------------------------------------------
  // Claim #2 — chat(messages, tools, outputSchema) skips parse when toolCalls are present
  // ---------------------------------------------------------------------------------------------

  /**
   * Chats with a schema and the search tool against a stub replying with an SSE body that emits a
   * tool_use block (and optionally a prose preamble before it), terminated with {@code
   * stop_reason=tool_use}.
   */
  private static Response<SimpleAnswer> chatAnsweredWithToolUse(String preamble) {
    var response = new AtomicReference<Response<SimpleAnswer>>();
    ModelHarness.exchange(
        List.of(toolUseSse(preamble)),
        uri ->
            new AnthropicProvider()
                .create(
                    AnthropicModelId.CLAUDE_OPUS_4_7.id(),
                    ModelConfig.newBuilder()
                        .withApiKey("test-key")
                        .withBaseUrl(uri + "/v1/messages")
                        .build()),
        model ->
            response.set(
                model.chat(
                    List.of(Message.user("Find me three matches.")),
                    List.of(searchTool()),
                    OutputSchema.of(SimpleAnswer.class))));
    return response.get();
  }

  private static String toolUseSse(String preamble) {
    var sse =
        new StringBuilder(
            named(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
                    + "\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
                    + "\"model\":\"claude-opus-4-7-20260101\",\"stop_reason\":null,"
                    + "\"usage\":{\"input_tokens\":50,\"output_tokens\":1}}}"));

    int idx = 0;
    if (preamble != null && !preamble.isEmpty()) {
      sse.append(blockStart(idx, "{\"type\":\"text\",\"text\":\"\"}"))
          .append(
              blockDelta(
                  idx,
                  "{\"type\":\"text_delta\",\"text\":\"" + preamble.replace("\"", "\\\"") + "\"}"))
          .append(blockStop(idx));
      idx++;
    }

    sse.append(
            blockStart(
                idx,
                "{\"type\":\"tool_use\",\"id\":\"toolu_42\",\"name\":\"search\",\"input\":{}}"))
        .append(
            blockDelta(
                idx,
                "{\"type\":\"input_json_delta\","
                    + "\"partial_json\":\"{\\\"q\\\":\\\"matches\\\"}\"}"))
        .append(blockStop(idx))
        .append(
            named(
                "message_delta",
                "{\"type\":\"message_delta\","
                    + "\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},"
                    + "\"usage\":{\"output_tokens\":20}}"))
        .append(named("message_stop", "{\"type\":\"message_stop\"}"));
    return sse.toString();
  }

  private static String blockStart(int index, String contentBlock) {
    return named(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":"
            + index
            + ",\"content_block\":"
            + contentBlock
            + "}");
  }

  private static String blockDelta(int index, String delta) {
    return named(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":" + index + ",\"delta\":" + delta + "}");
  }

  private static String blockStop(int index) {
    return named("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":" + index + "}");
  }

  /**
   * Direct reproduction of Light Grid's canary failure mode. The model returns prose preamble +
   * tool_use. Pre-2.3.3 this threw {@code AnthropicException("Failed to parse structured output:
   * I'll work through this carefully.")}; post-fix it returns a Response with tool calls present
   * and {@code parsed} null (the structured output is the deliverable of a later turn).
   */
  @Test
  void chatWithProsePreambleAndToolCallsReturnsToolCallsWithoutParsing() {
    var response = chatAnsweredWithToolUse("I'll work through this carefully.");

    assertFalse(response.toolCalls().isEmpty(), "tool call must be surfaced");
    assertEquals("search", response.toolCalls().getFirst().name());
    assertNull(response.parsed(), "parsed slot stays null on a tool-calling turn");
    assertTrue(
        response.content().contains("I'll work through this carefully"),
        "prose preamble must still be preserved in the Response.content for history");
  }

  /**
   * Tighter variant — tool_use response with no assistant text. Same shape as above: the tool call
   * is surfaced and {@code parsed} is null.
   */
  @Test
  void chatWithToolUseAndNoProseReturnsNullParsed() {
    var response = chatAnsweredWithToolUse(null);

    assertFalse(response.toolCalls().isEmpty(), "tool call must be surfaced");
    assertEquals("search", response.toolCalls().getFirst().name());
    assertNull(response.parsed(), "parsed slot stays null on a blank-prose tool-use turn");
  }
}
