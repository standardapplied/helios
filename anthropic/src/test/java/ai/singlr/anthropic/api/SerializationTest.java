/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SerializationTest {

  private final ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void serializeSimpleRequest() throws Exception {
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-sonnet-4-6-20250514")
            .withMaxTokens(4096)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hello")))
            .withStream(true)
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"model\":\"claude-sonnet-4-6-20250514\""));
    assertTrue(json.contains("\"max_tokens\":4096"));
    assertTrue(json.contains("\"Hello\""));
    assertTrue(json.contains("\"stream\":true"));
    assertFalse(json.contains("\"system\""));
  }

  @Test
  void serializeRequestWithSystem() throws Exception {
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-sonnet-4-6-20250514")
            .withMaxTokens(4096)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hello")))
            .withSystem("You are helpful")
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"system\":\"You are helpful\""));
  }

  @Test
  void serializeLegacyThinkingShape() throws Exception {
    // Opus 4.6 / Sonnet 4.6: thinking.type=enabled with budget_tokens. No output_config.
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-opus-4-6")
            .withMaxTokens(4096)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hello")))
            .withThinking(ThinkingConfig.enabled(10000))
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(
        json.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":10000}"),
        "legacy shape, got:\n" + json);
    assertFalse(json.contains("output_config"), "no output_config for legacy shape");
  }

  @Test
  void serializeAdaptiveThinkingShape() throws Exception {
    // Opus 4.7+: thinking.type=adaptive WITHOUT budget_tokens, output_config.effort sibling.
    // display=summarized is pinned by ThinkingConfig.adaptive() so callers continue to receive
    // ThinkingDelta events — Anthropic's own default flipped to "omitted" on Opus 4.7.
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-opus-4-7")
            .withMaxTokens(4096)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hello")))
            .withThinking(ThinkingConfig.adaptive())
            .withOutputConfig(OutputConfig.MEDIUM)
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(
        json.contains("\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"}"),
        "adaptive shape with explicit summarized display, got:\n" + json);
    assertTrue(
        json.contains("\"output_config\":{\"effort\":\"medium\"}"),
        "output_config sibling carries effort, got:\n" + json);
    assertFalse(
        json.contains("budget_tokens"),
        "adaptive shape must NOT include budget_tokens — Opus 4.7 rejects it");
  }

  @Test
  void serializeAdaptiveOmittedThinkingShape() throws Exception {
    // Opt-in latency win: callers using ThinkingConfig.adaptiveOmitted() trade summary visibility
    // for skipping the summary-streaming phase entirely. Verify the wire string is "omitted".
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-opus-4-7")
            .withMaxTokens(4096)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hello")))
            .withThinking(ThinkingConfig.adaptiveOmitted())
            .withOutputConfig(OutputConfig.XHIGH)
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(
        json.contains("\"thinking\":{\"type\":\"adaptive\",\"display\":\"omitted\"}"),
        "adaptive omitted shape, got:\n" + json);
    assertTrue(
        json.contains("\"output_config\":{\"effort\":\"xhigh\"}"),
        "xhigh effort wire string, got:\n" + json);
  }

  @Test
  void serializeRequestWithoutThinkingOmitsBothFields() throws Exception {
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-opus-4-7")
            .withMaxTokens(4096)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hello")))
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertFalse(json.contains("thinking"), "thinking omitted when null");
    assertFalse(json.contains("output_config"), "output_config omitted when null");
  }

  @Test
  void serializeContentBlockText() throws Exception {
    var block = ContentBlock.text("Hello world");
    var json = objectMapper.writeValueAsString(block);
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"text\":\"Hello world\""));
    assertFalse(json.contains("\"id\""));
    assertFalse(json.contains("\"name\""));
    assertFalse(json.contains("\"input\""));
  }

  @Test
  void serializeContentBlockToolUse() throws Exception {
    var block = ContentBlock.toolUse("toolu_1", "get_weather", Map.of("city", "NYC"));
    var json = objectMapper.writeValueAsString(block);
    assertTrue(json.contains("\"type\":\"tool_use\""));
    assertTrue(json.contains("\"id\":\"toolu_1\""));
    assertTrue(json.contains("\"name\":\"get_weather\""));
    assertTrue(json.contains("\"city\":\"NYC\""));
    assertFalse(json.contains("\"text\""));
    assertFalse(json.contains("\"tool_use_id\""));
  }

  @Test
  void serializeContentBlockToolResult() throws Exception {
    var block = ContentBlock.toolResult("toolu_1", "Weather is sunny");
    var json = objectMapper.writeValueAsString(block);
    assertTrue(json.contains("\"type\":\"tool_result\""));
    assertTrue(json.contains("\"tool_use_id\":\"toolu_1\""));
    assertTrue(json.contains("\"content\":\"Weather is sunny\""));
    assertFalse(json.contains("\"text\""));
    assertFalse(json.contains("\"name\""));
  }

  @Test
  void serializeContentBlockThinking() throws Exception {
    var block = ContentBlock.thinking("Let me think...", "sig123");
    var json = objectMapper.writeValueAsString(block);
    assertTrue(json.contains("\"type\":\"thinking\""));
    assertTrue(json.contains("\"thinking\":\"Let me think...\""));
    assertTrue(json.contains("\"signature\":\"sig123\""));
    assertFalse(json.contains("\"text\""));
  }

  @Test
  void serializeToolDefinition() throws Exception {
    var tool =
        Tool.newBuilder()
            .withName("search_people")
            .withDescription("Finds people using semantic search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Search query")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("[]"))
            .build();

    var toolDef =
        new ToolDefinition(tool.name(), tool.description(), tool.parametersAsJsonSchema());
    var json = objectMapper.writeValueAsString(toolDef);
    assertTrue(json.contains("\"name\":\"search_people\""));
    assertTrue(json.contains("\"description\":\"Finds people using semantic search\""));
    assertTrue(json.contains("\"input_schema\""));
    assertTrue(json.contains("\"type\":\"object\""));
    assertTrue(json.contains("\"query\""));
  }

  @Test
  void serializeToolChoiceAuto() throws Exception {
    var choice = ToolChoiceConfig.auto();
    var json = objectMapper.writeValueAsString(choice);
    assertTrue(json.contains("\"type\":\"auto\""));
    assertFalse(json.contains("\"name\""));
  }

  @Test
  void serializeToolChoiceAny() throws Exception {
    var choice = ToolChoiceConfig.any();
    var json = objectMapper.writeValueAsString(choice);
    assertTrue(json.contains("\"type\":\"any\""));
  }

  @Test
  void serializeToolChoiceTool() throws Exception {
    var choice = ToolChoiceConfig.tool("get_weather");
    var json = objectMapper.writeValueAsString(choice);
    assertTrue(json.contains("\"type\":\"tool\""));
    assertTrue(json.contains("\"name\":\"get_weather\""));
  }

  @Test
  void serializeThinkingEnabled() throws Exception {
    var thinking = ThinkingConfig.enabled(10000);
    var json = objectMapper.writeValueAsString(thinking);
    assertTrue(json.contains("\"type\":\"enabled\""));
    assertTrue(json.contains("\"budget_tokens\":10000"));
  }

  @Test
  void serializeThinkingDisabled() throws Exception {
    var thinking = ThinkingConfig.disabled();
    var json = objectMapper.writeValueAsString(thinking);
    assertTrue(json.contains("\"type\":\"disabled\""));
    assertFalse(json.contains("\"budget_tokens\""));
  }

  @Test
  void serializeRequestWithTools() throws Exception {
    var searchTool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("Search for items")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Search query")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("[]"))
            .build();

    var toolDefs =
        List.of(
            new ToolDefinition(
                searchTool.name(), searchTool.description(), searchTool.parametersAsJsonSchema()));

    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-sonnet-4-6-20250514")
            .withMaxTokens(4096)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Find things")))
            .withTools(toolDefs)
            .withToolChoice(ToolChoiceConfig.auto())
            .withStream(true)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"search\""));
    assertTrue(json.contains("\"input_schema\""));
    assertTrue(json.contains("\"tool_choice\""));
    assertTrue(json.contains("\"type\":\"auto\""));
  }

  @Test
  void serializeToolResultRoundTrip() throws Exception {
    var messages = new ArrayList<MessagesRequest.MessageEntry>();
    messages.add(MessagesRequest.MessageEntry.user("Find people"));
    messages.add(
        MessagesRequest.MessageEntry.assistant(
            List.of(ContentBlock.toolUse("toolu_1", "search", Map.of("query", "engineers")))));
    messages.add(
        MessagesRequest.MessageEntry.user(
            List.of(ContentBlock.toolResult("toolu_1", "Found 3 engineers"))));

    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-sonnet-4-6-20250514")
            .withMaxTokens(4096)
            .withMessages(messages)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"type\":\"tool_use\""));
    assertTrue(json.contains("\"id\":\"toolu_1\""));
    assertTrue(json.contains("\"type\":\"tool_result\""));
    assertTrue(json.contains("\"tool_use_id\":\"toolu_1\""));
  }

  @Test
  void deserializeMessagesResponse() throws Exception {
    var json =
        """
        {
          "id": "msg_123",
          "type": "message",
          "role": "assistant",
          "content": [
            {"type": "text", "text": "Hello!"}
          ],
          "model": "claude-sonnet-4-6-20250514",
          "stop_reason": "end_turn",
          "stop_sequence": null,
          "usage": {
            "input_tokens": 10,
            "output_tokens": 25
          }
        }
        """;

    var response = objectMapper.readValue(json, MessagesResponse.class);

    assertEquals("msg_123", response.id());
    assertEquals("message", response.type());
    assertEquals("assistant", response.role());
    assertEquals(1, response.content().size());
    assertTrue(response.content().getFirst().hasTypeText());
    assertEquals("Hello!", response.content().getFirst().text());
    assertEquals("end_turn", response.stopReason());
    assertNotNull(response.usage());
    assertEquals(10, response.usage().inputTokens());
    assertEquals(25, response.usage().outputTokens());
  }

  @Test
  void deserializeMessagesResponseWithToolUse() throws Exception {
    var json =
        """
        {
          "id": "msg_456",
          "type": "message",
          "role": "assistant",
          "content": [
            {"type": "text", "text": "Let me check."},
            {"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {"city": "NYC"}}
          ],
          "model": "claude-sonnet-4-6-20250514",
          "stop_reason": "tool_use",
          "usage": {"input_tokens": 20, "output_tokens": 50}
        }
        """;

    var response = objectMapper.readValue(json, MessagesResponse.class);

    assertEquals(2, response.content().size());
    assertTrue(response.content().get(0).hasTypeText());
    assertTrue(response.content().get(1).hasTypeToolUse());
    assertEquals("toolu_1", response.content().get(1).id());
    assertEquals("get_weather", response.content().get(1).name());
    assertEquals("tool_use", response.stopReason());
  }

  @Test
  void deserializeApiStreamEventMessageStart() throws Exception {
    var json =
        """
        {
          "type": "message_start",
          "message": {
            "id": "msg_1",
            "type": "message",
            "role": "assistant",
            "content": [],
            "model": "claude-sonnet-4-6-20250514",
            "usage": {"input_tokens": 25, "output_tokens": 1}
          }
        }
        """;

    var event = objectMapper.readValue(json, ApiStreamEvent.class);

    assertTrue(event.hasTypeMessageStart());
    assertNotNull(event.message());
    assertEquals("msg_1", event.message().id());
    assertEquals(25, event.message().usage().inputTokens());
  }

  @Test
  void deserializeApiStreamEventContentBlockDelta() throws Exception {
    var json =
        """
        {
          "type": "content_block_delta",
          "index": 0,
          "delta": {"type": "text_delta", "text": "Hello"}
        }
        """;

    var event = objectMapper.readValue(json, ApiStreamEvent.class);

    assertTrue(event.hasTypeContentBlockDelta());
    assertEquals(0, event.index());
    assertNotNull(event.delta());
    assertTrue(event.delta().hasTypeTextDelta());
    assertEquals("Hello", event.delta().text());
  }

  @Test
  void deserializeApiStreamEventMessageDelta() throws Exception {
    var json =
        """
        {
          "type": "message_delta",
          "delta": {"stop_reason": "end_turn", "stop_sequence": null},
          "usage": {"output_tokens": 15}
        }
        """;

    var event = objectMapper.readValue(json, ApiStreamEvent.class);

    assertTrue(event.hasTypeMessageDelta());
    assertNotNull(event.delta());
    assertEquals("end_turn", event.delta().stopReason());
    assertNotNull(event.usage());
    assertEquals(15, event.usage().outputTokens());
  }

  @Test
  void deserializeApiStreamEventContentBlockStart() throws Exception {
    var json =
        """
        {
          "type": "content_block_start",
          "index": 0,
          "content_block": {"type": "text", "text": ""}
        }
        """;

    var event = objectMapper.readValue(json, ApiStreamEvent.class);

    assertTrue(event.hasTypeContentBlockStart());
    assertNotNull(event.contentBlock());
    assertTrue(event.contentBlock().hasTypeText());
  }

  @Test
  void deserializeApiStreamEventToolUseStart() throws Exception {
    var json =
        """
        {
          "type": "content_block_start",
          "index": 1,
          "content_block": {"type": "tool_use", "id": "toolu_1", "name": "search", "input": {}}
        }
        """;

    var event = objectMapper.readValue(json, ApiStreamEvent.class);

    assertTrue(event.hasTypeContentBlockStart());
    assertEquals(1, event.index());
    assertTrue(event.contentBlock().hasTypeToolUse());
    assertEquals("toolu_1", event.contentBlock().id());
    assertEquals("search", event.contentBlock().name());
  }

  @Test
  void deserializeApiUsageWithCacheTokens() throws Exception {
    var json =
        """
        {
          "input_tokens": 100,
          "output_tokens": 50,
          "cache_creation_input_tokens": 10,
          "cache_read_input_tokens": 20
        }
        """;

    var usage = objectMapper.readValue(json, ApiUsage.class);

    assertEquals(100, usage.inputTokens());
    assertEquals(50, usage.outputTokens());
    assertEquals(10, usage.cacheCreationInputTokens());
    assertEquals(20, usage.cacheReadInputTokens());
  }

  @Test
  void deserializeContentDeltaInputJson() throws Exception {
    var json =
        """
        {"type": "input_json_delta", "partial_json": "{\\"city\\""}
        """;

    var delta = objectMapper.readValue(json, ContentDelta.class);

    assertTrue(delta.hasTypeInputJsonDelta());
    assertEquals("{\"city\"", delta.partialJson());
  }

  @Test
  void deserializeContentDeltaThinking() throws Exception {
    var json =
        """
        {"type": "thinking_delta", "thinking": "Let me think..."}
        """;

    var delta = objectMapper.readValue(json, ContentDelta.class);

    assertTrue(delta.hasTypeThinkingDelta());
    assertEquals("Let me think...", delta.thinking());
  }

  @Test
  void deserializeContentDeltaSignature() throws Exception {
    var json =
        """
        {"type": "signature_delta", "signature": "EqoB123abc"}
        """;

    var delta = objectMapper.readValue(json, ContentDelta.class);

    assertTrue(delta.hasTypeSignatureDelta());
    assertEquals("EqoB123abc", delta.signature());
  }

  @Test
  void serializeRequestWithThinking() throws Exception {
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-sonnet-4-6-20250514")
            .withMaxTokens(16000)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Think hard")))
            .withThinking(ThinkingConfig.enabled(10000))
            .withStream(true)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"thinking\""));
    assertTrue(json.contains("\"type\":\"enabled\""));
    assertTrue(json.contains("\"budget_tokens\":10000"));
  }

  @Test
  void serializeRequestWithGenerationParams() throws Exception {
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-sonnet-4-6-20250514")
            .withMaxTokens(4096)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hi")))
            .withTemperature(0.7)
            .withTopP(0.9)
            .withStopSequences(List.of("END", "STOP"))
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"temperature\":0.7"));
    assertTrue(json.contains("\"top_p\":0.9"));
    assertTrue(json.contains("\"stop_sequences\""));
    assertTrue(json.contains("\"END\""));
    assertTrue(json.contains("\"STOP\""));
  }

  @Test
  void messageEntryFactoryMethods() throws Exception {
    var userText = MessagesRequest.MessageEntry.user("Hello");
    assertEquals("user", userText.role());
    assertEquals("Hello", userText.content());

    var assistantText = MessagesRequest.MessageEntry.assistant("Hi");
    assertEquals("assistant", assistantText.role());
    assertEquals("Hi", assistantText.content());

    var userBlocks =
        MessagesRequest.MessageEntry.user(List.of(ContentBlock.toolResult("t1", "result")));
    assertEquals("user", userBlocks.role());

    var assistantBlocks =
        MessagesRequest.MessageEntry.assistant(
            List.of(ContentBlock.text("text"), ContentBlock.toolUse("t1", "fn", Map.of())));
    assertEquals("assistant", assistantBlocks.role());
  }

  @Test
  void contentBlockHelperMethods() {
    var text = ContentBlock.text("hello");
    assertTrue(text.hasTypeText());
    assertFalse(text.hasTypeToolUse());
    assertFalse(text.hasTypeToolResult());
    assertFalse(text.hasTypeThinking());

    var toolUse = ContentBlock.toolUse("id", "name", Map.of());
    assertFalse(toolUse.hasTypeText());
    assertTrue(toolUse.hasTypeToolUse());

    var toolResult = ContentBlock.toolResult("id", "content");
    assertTrue(toolResult.hasTypeToolResult());

    var thinking = ContentBlock.thinking("thought", "sig");
    assertTrue(thinking.hasTypeThinking());
  }

  @Test
  void contentDeltaHelperMethods() throws Exception {
    var textDelta =
        objectMapper.readValue("{\"type\":\"text_delta\",\"text\":\"hi\"}", ContentDelta.class);
    assertTrue(textDelta.hasTypeTextDelta());
    assertFalse(textDelta.hasTypeInputJsonDelta());
    assertFalse(textDelta.hasTypeThinkingDelta());
    assertFalse(textDelta.hasTypeSignatureDelta());

    var inputJson =
        objectMapper.readValue(
            "{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}", ContentDelta.class);
    assertTrue(inputJson.hasTypeInputJsonDelta());

    var thinking =
        objectMapper.readValue(
            "{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}", ContentDelta.class);
    assertTrue(thinking.hasTypeThinkingDelta());

    var signature =
        objectMapper.readValue(
            "{\"type\":\"signature_delta\",\"signature\":\"abc\"}", ContentDelta.class);
    assertTrue(signature.hasTypeSignatureDelta());
  }

  @Test
  void apiStreamEventHelperMethods() throws Exception {
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"message_start\"}", ApiStreamEvent.class)
            .hasTypeMessageStart());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"content_block_start\"}", ApiStreamEvent.class)
            .hasTypeContentBlockStart());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"content_block_delta\"}", ApiStreamEvent.class)
            .hasTypeContentBlockDelta());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"content_block_stop\"}", ApiStreamEvent.class)
            .hasTypeContentBlockStop());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"message_delta\"}", ApiStreamEvent.class)
            .hasTypeMessageDelta());
    assertTrue(
        objectMapper
            .readValue("{\"type\":\"message_stop\"}", ApiStreamEvent.class)
            .hasTypeMessageStop());
    assertTrue(objectMapper.readValue("{\"type\":\"error\"}", ApiStreamEvent.class).hasTypeError());
  }

  // ── cache-control wire shape (hv2-bug2 Issue 1) ───────────────────────────

  @Test
  void serializeCacheControlEphemeralDefaultTtl() throws Exception {
    var cc = CacheControl.ephemeral();
    var json = objectMapper.writeValueAsString(cc);
    assertEquals("{\"type\":\"ephemeral\"}", json);
  }

  @Test
  void serializeCacheControlEphemeralOneHourTtl() throws Exception {
    var cc = CacheControl.ephemeral(CacheControl.TTL_1_HOUR);
    var json = objectMapper.writeValueAsString(cc);
    assertTrue(json.contains("\"type\":\"ephemeral\""));
    assertTrue(json.contains("\"ttl\":\"1h\""));
  }

  @Test
  void cacheControlRejectsNullType() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> new CacheControl(null, null));
  }

  @Test
  void cacheControlRejectsBlankType() {
    var ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> new CacheControl("  ", null));
    assertEquals("type must not be blank", ex.getMessage());
  }

  @Test
  void serializeSystemContentWithCacheControl() throws Exception {
    var block = SystemContent.text("Be careful").withCacheControl(CacheControl.ephemeral());
    var json = objectMapper.writeValueAsString(block);
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"text\":\"Be careful\""));
    assertTrue(json.contains("\"cache_control\":{\"type\":\"ephemeral\"}"));
  }

  @Test
  void serializeContentBlockWithCacheControl() throws Exception {
    var block = ContentBlock.text("user turn").withCacheControl(CacheControl.ephemeral());
    var json = objectMapper.writeValueAsString(block);
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"text\":\"user turn\""));
    assertTrue(json.contains("\"cache_control\":{\"type\":\"ephemeral\"}"));
  }

  @Test
  void contentBlockWithCacheControlRejectsNull() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> ContentBlock.text("x").withCacheControl(null));
  }

  @Test
  void systemContentWithCacheControlRejectsNull() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> SystemContent.text("x").withCacheControl(null));
  }

  @Test
  void toolDefinitionWithCacheControlRoundTrips() throws Exception {
    var def =
        new ToolDefinition("calc", "compute", Map.of("type", "object"))
            .withCacheControl(CacheControl.ephemeral());
    var json = objectMapper.writeValueAsString(def);
    assertTrue(json.contains("\"name\":\"calc\""));
    assertTrue(json.contains("\"cache_control\":{\"type\":\"ephemeral\"}"));
  }

  @Test
  void toolDefinitionWithCacheControlRejectsNull() {
    var def = new ToolDefinition("calc", "compute", Map.of());
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> def.withCacheControl(null));
  }

  @Test
  void messagesRequestSerializesArraySystemShape() throws Exception {
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-opus-4-7")
            .withMaxTokens(1024)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hi")))
            .withSystem(
                List.of(
                    SystemContent.text("You are helpful")
                        .withCacheControl(CacheControl.ephemeral())))
            .build();
    var json = objectMapper.writeValueAsString(request);
    // system as array of objects with cache_control on the only block.
    assertTrue(
        json.contains(
            "\"system\":[{\"type\":\"text\",\"text\":\"You are helpful\",\"cache_control\":{\"type\":\"ephemeral\"}}]"),
        "system array wire shape must carry cache_control: " + json);
  }

  @Test
  void messagesRequestSerializesPlainStringSystemShape() throws Exception {
    var request =
        MessagesRequest.newBuilder()
            .withModel("claude-opus-4-7")
            .withMaxTokens(1024)
            .withMessages(List.of(MessagesRequest.MessageEntry.user("Hi")))
            .withSystem("Plain string system")
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(
        json.contains("\"system\":\"Plain string system\""),
        "plain system field must serialize as a string when no caching is requested: " + json);
  }

  @Test
  void apiUsageDeserializesCacheTokenFields() throws Exception {
    // Verifies cache_creation_input_tokens / cache_read_input_tokens reach ApiUsage from the wire.
    var usage =
        objectMapper.readValue(
            "{\"input_tokens\":10,\"output_tokens\":5,"
                + "\"cache_creation_input_tokens\":7,\"cache_read_input_tokens\":42}",
            ApiUsage.class);
    assertEquals(10, usage.inputTokens());
    assertEquals(5, usage.outputTokens());
    assertEquals(7, usage.cacheCreationInputTokens());
    assertEquals(42, usage.cacheReadInputTokens());
  }
}
