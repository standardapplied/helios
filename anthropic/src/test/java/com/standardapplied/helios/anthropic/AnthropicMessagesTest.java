/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.INTERLEAVED_THINKING_SSE;
import static com.standardapplied.helios.anthropic.AnthropicFixture.drainSseFixture;
import static com.standardapplied.helios.anthropic.AnthropicFixture.requests;
import static com.standardapplied.helios.anthropic.AnthropicFixture.underCurrentPrompt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnthropicMessagesTest {

  private static final String THINKING = "[{\"text\":\"t\",\"signature\":\"sig\"}]";

  private static final String OMITTED = "[earlier output omitted]";

  @Test
  void convertAssistantMessageSimpleText() {
    var message = Message.assistant("Hello");

    var entry = AnthropicMessages.assistant(message, true);

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

    var entry = AnthropicMessages.assistant(message, true);

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

    var entry = AnthropicMessages.assistant(message, true);

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

    var entry = AnthropicMessages.assistant(message, true);

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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-json",
        "null",
        "\"sig\"",
        "7",
        "{\"text\":\"t\",\"signature\":\"sig\"}",
        "[\"sig\"]",
        "[{\"text\":\"t\"}]",
        "[{\"text\":\"t\",\"signature\":\"\"}]",
        "[{\"text\":\"t\",\"signature\":7}]",
        "[{\"text\":7,\"signature\":\"sig\"}]"
      })
  void anUnreadableThinkingValueFailsTheRequestBuild(String encoded) {
    var message =
        Message.newBuilder()
            .withRole(Role.ASSISTANT)
            .withContent("answer")
            .withMetadata(Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, encoded))
            .build();
    var conversation = List.of(Message.user("q"), message, Message.user("next"));
    var builder =
        requests(
            AnthropicModelId.CLAUDE_OPUS_5_5, ModelConfig.newBuilder().withApiKey("k").build());

    var failure =
        assertThrows(
            AnthropicException.class, () -> builder.build(conversation, List.of(), null).body());

    assertTrue(
        failure.getMessage().contains(ThinkingBlock.THINKING_BLOCKS_KEY), failure.getMessage());
  }

  @Test
  void aSignedThinkingBlockWithoutTextDecodesToEmptyText() {
    var blocks =
        ThinkingBlock.decodeAll(
            Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, "[{\"signature\":\"sig-1\"}]"));

    assertEquals(List.of(new ThinkingBlock("", "sig-1")), blocks);
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

    var entry = AnthropicMessages.assistant(msg, true);

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
        requests
            .build(
                List.of(
                    Message.user("Compare profiles 1 and 2"),
                    underCurrentPrompt(response.toMessage(), requests, List.of()),
                    Message.tool("toolu_1", "get_profile", "profile one"),
                    Message.tool("toolu_2", "get_profile", "profile two")),
                List.of(),
                null)
            .body();

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

  @Test
  void aBindingModelReplaysReasoningOnlyAfterTheLastTurnUnderAnotherPrompt() {
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5_5, ModelConfig.of("k"));
    var current = currentStamp(requests);

    assertEquals(
        List.of(false, false, true, true), thinkingKept(requests, "x", "x", current, current));
    assertEquals(List.of(false, false, false, false), thinkingKept(requests, "x", "x", "z", "z"));
    assertEquals(List.of(false, false, true), thinkingKept(requests, current, "x", current));
    assertEquals(List.of(false, true), thinkingKept(requests, null, current));
  }

  @Test
  void aTurnWithoutMetadataCountsAsProducedUnderAnotherPrompt() {
    var bare = new Message(Role.ASSISTANT, "Hi", List.of(), null, null, null, List.of());

    assertEquals(
        2,
        ReasoningReplay.keptFrom(List.of(Message.user("a"), bare, Message.user("b")), "s", true));
  }

  @Test
  void aNonBindingModelReplaysEveryTurnsReasoning() {
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5, ModelConfig.of("k"));

    assertEquals(List.of(true, true), thinkingKept(requests, "x", null));
  }

  @Test
  void aRecordedTurnLosesOnlyItsReasoningBlocksKeepingEveryOtherInOrder() {
    var raw =
        "[{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":\"s\"},"
            + "{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\"},"
            + "{\"type\":\"redacted_thinking\",\"data\":\"ENC\"},"
            + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srv_1\","
            + "\"content\":[{\"encrypted_content\":\"E\"}]},"
            + "{\"type\":\"text\",\"text\":\"Found it.\"}]";
    var message =
        Message.assistant(
            "Found it.",
            List.of(),
            Map.of(RawContentEcho.RAW_CONTENT_KEY, raw, ReasoningReplay.PROMPT_STAMP_KEY, "x"));

    var entry = AnthropicMessages.assistant(message, false);

    assertEquals(
        List.of(
            Map.of("type", "server_tool_use", "id", "srv_1", "name", "web_search"),
            Map.of(
                "type",
                "web_search_tool_result",
                "tool_use_id",
                "srv_1",
                "content",
                List.of(Map.of("encrypted_content", "E"))),
            Map.of("type", "text", "text", "Found it.")),
        entry.content());
  }

  @Test
  void aTurnWhoseOnlyContentWasReasoningIsSentAsThePlaceholder() {
    var raw = "[{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":\"s\"}]";
    var recorded = Message.assistant("", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, raw));
    var typed = turn("", "x");

    assertEquals(OMITTED, AnthropicMessages.assistant(recorded, false).content());
    assertEquals(OMITTED, AnthropicMessages.assistant(typed, false).content());
    assertEquals("", AnthropicMessages.assistant(Message.assistant(""), true).content());
  }

  @Test
  void aTurnLosingItsReasoningKeepsItsTextAndToolCalls() {
    var call = ToolCall.newBuilder().withId("t1").withName("search").build();
    var message =
        Message.assistant(
            "Searching.",
            List.of(call),
            Map.of(
                ThinkingBlock.THINKING_BLOCKS_KEY,
                THINKING,
                ReasoningReplay.PROMPT_STAMP_KEY,
                "x"));

    var entry = AnthropicMessages.assistant(message, false);

    assertEquals(List.of("text", "tool_use"), types(entry.content()));
  }

  private static String currentStamp(AnthropicRequestBuilder requests) {
    return requests.build(List.of(), List.of(), null).promptStamp();
  }

  /** Whether each assistant turn, stamped {@code stamps} in order, goes back with its thinking. */
  private static List<Boolean> thinkingKept(AnthropicRequestBuilder requests, String... stamps) {
    var messages = new ArrayList<Message>();
    for (var stamp : stamps) {
      messages.add(Message.user("Next?"));
      messages.add(turn("Answer.", stamp));
    }
    messages.add(Message.user("Last."));
    return requests.build(messages, List.of(), null).body().messages().stream()
        .filter(entry -> "assistant".equals(entry.role()))
        .map(entry -> types(entry.content()).contains("thinking"))
        .toList();
  }

  private static Message turn(String text, String stamp) {
    var metadata = new HashMap<String, String>();
    metadata.put(ThinkingBlock.THINKING_BLOCKS_KEY, THINKING);
    if (stamp != null) {
      metadata.put(ReasoningReplay.PROMPT_STAMP_KEY, stamp);
    }
    return Message.assistant(text, List.of(), metadata);
  }

  private static List<String> types(Object content) {
    if (!(content instanceof List<?> blocks)) {
      return List.of("text");
    }
    return blocks.stream()
        .map(block -> block instanceof ContentBlock typed ? typed.type() : null)
        .toList();
  }
}
