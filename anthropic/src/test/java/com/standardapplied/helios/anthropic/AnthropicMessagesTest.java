/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.INTERLEAVED_THINKING_SSE;
import static com.standardapplied.helios.anthropic.AnthropicFixture.drainSseFixture;
import static com.standardapplied.helios.anthropic.AnthropicFixture.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicMessagesTest {

  @Test
  void convertAssistantMessageSimpleText() {
    var message = Message.assistant("Hello");

    var entry = AnthropicMessages.assistant(message);

    assertEquals("assistant", entry.role());
    assertEquals("Hello", entry.content());
  }

  @Test
  void convertAssistantMessageWithToolCalls() {
    var tc =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("search")
            .withArguments(Map.of("q", "test"))
            .build();
    var message = Message.assistant("", List.of(tc));

    var entry = AnthropicMessages.assistant(message);

    assertEquals("assistant", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    assertEquals(1, blocks.size());
    assertEquals("tool_use", blocks.getFirst().type());
    assertEquals("call_1", blocks.getFirst().id());
    assertEquals("search", blocks.getFirst().name());
  }

  @Test
  void convertAssistantMessageWithThinkingSignature() {
    var metadata =
        Map.of(
            ThinkingBlock.THINKING_BLOCKS_KEY,
            "[{\"text\":\"I need to think about this\",\"signature\":\"sig123\"}]");
    var message = Message.assistant("Answer", List.of(), metadata);

    var entry = AnthropicMessages.assistant(message);

    assertEquals("assistant", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    assertEquals(2, blocks.size());
    assertEquals("thinking", blocks.get(0).type());
    assertEquals("I need to think about this", blocks.get(0).thinking());
    assertEquals("sig123", blocks.get(0).signature());
    assertEquals("text", blocks.get(1).type());
    assertEquals("Answer", blocks.get(1).text());
  }

  @Test
  void convertAssistantMessageWithEmptyThinkingSignatureUsesString() {
    var metadata =
        Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, "[{\"text\":\"unsigned\",\"signature\":\"\"}]");
    var message = Message.assistant("Answer", List.of(), metadata);

    var entry = AnthropicMessages.assistant(message);

    assertEquals("assistant", entry.role());
    assertEquals("Answer", entry.content());
  }

  @Test
  void convertAssistantMessageNullContentBecomesEmpty() {
    var message =
        new Message(
            com.standardapplied.helios.core.model.Role.ASSISTANT,
            null,
            List.of(),
            null,
            null,
            Map.of(),
            List.of());

    var entry = AnthropicMessages.assistant(message);

    assertEquals("assistant", entry.role());
    assertEquals("", entry.content());
  }

  @Test
  void decodeThinkingBlocksReadsMultiBlockJson() {
    var json =
        "[{\"text\":\"first thought\",\"signature\":\"sig-1\"},"
            + "{\"text\":\"second thought\",\"signature\":\"sig-2\"}]";
    var msg =
        Message.newBuilder()
            .withRole(com.standardapplied.helios.core.model.Role.ASSISTANT)
            .withContent("response")
            .withMetadata(Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, json))
            .build();

    var blocks = ThinkingBlock.decodeAll(msg.metadata());

    assertEquals(2, blocks.size());
    assertEquals("first thought", blocks.get(0).text());
    assertEquals("sig-1", blocks.get(0).signature());
    assertEquals("second thought", blocks.get(1).text());
    assertEquals("sig-2", blocks.get(1).signature());
  }

  @Test
  void decodeThinkingBlocksReadsASingleBlock() {
    var blocks =
        ThinkingBlock.decodeAll(
            Map.of(
                ThinkingBlock.THINKING_BLOCKS_KEY,
                "[{\"text\":\"I am thinking\",\"signature\":\"sig-1\"}]"));

    assertEquals(List.of(new ThinkingBlock("I am thinking", "sig-1")), blocks);
  }

  @Test
  void decodeThinkingBlocksIgnoresTheRemovedSingleBlockKeys() {
    var blocks =
        ThinkingBlock.decodeAll(
            Map.of("anthropic.thinking", "2.x text", "anthropic.thinkingSignature", "2.x-sig"));

    assertTrue(
        blocks.isEmpty(), "a 2.x conversation carrying only the single-block keys is not read");
  }

  @Test
  void decodeThinkingBlocksMalformedJsonYieldsNoBlocks() {
    var blocks = ThinkingBlock.decodeAll(Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, "not-json"));

    assertTrue(blocks.isEmpty());
  }

  @Test
  void decodeThinkingBlocksReturnsEmptyWhenNoMetadata() {
    var msg =
        Message.newBuilder()
            .withRole(com.standardapplied.helios.core.model.Role.ASSISTANT)
            .withContent("r")
            .build();
    assertTrue(ThinkingBlock.decodeAll(msg.metadata()).isEmpty());
  }

  @Test
  void convertAssistantMessageEmitsMultipleThinkingBlocksFromJsonMetadata() {
    var json =
        "[{\"text\":\"first thought\",\"signature\":\"sig-1\"},"
            + "{\"text\":\"second thought\",\"signature\":\"sig-2\"}]";
    var msg =
        Message.newBuilder()
            .withRole(com.standardapplied.helios.core.model.Role.ASSISTANT)
            .withContent("final answer")
            .withMetadata(Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, json))
            .build();

    var entry = AnthropicMessages.assistant(msg);

    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    var thinkingCount = blocks.stream().filter(b -> "thinking".equals(b.type())).count();
    assertEquals(
        2, thinkingCount, "two thinking blocks must round-trip into separate ContentBlocks");
    // Per-block signatures must be preserved (the bug we fixed was concatenation).
    assertEquals("sig-1", blocks.get(0).signature());
    assertEquals("sig-2", blocks.get(1).signature());
  }

  @Test
  @SuppressWarnings("unchecked")
  void interleavedThinkingTurnIsReplayedInItsOriginalBlockOrder() throws Exception {
    var response = drainSseFixture(INTERLEAVED_THINKING_SSE).response();
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5_5, config);

    var request =
        requests.build(
            List.of(
                Message.user("Compare profiles 1 and 2"),
                response.toMessage(),
                Message.tool("toolu_1", "get_profile", "profile one"),
                Message.tool("toolu_2", "get_profile", "profile two")),
            List.of(),
            null);

    var assistant = (List<Map<String, Object>>) request.messages().get(1).content();
    assertEquals(
        List.of("thinking", "tool_use", "thinking", "tool_use"),
        assistant.stream().map(block -> block.get("type")).toList(),
        "each progress note must stay immediately before the tool call it introduces");
    assertEquals("SIG-1", assistant.get(0).get("signature"));
    assertEquals(Map.of("id", 1), assistant.get(1).get("input"));
    assertEquals("SIG-2", assistant.get(2).get("signature"));
    assertEquals(Map.of("id", 2), assistant.get(3).get("input"));
  }
}
