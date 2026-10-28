/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.underCurrentPrompt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.anthropic.api.SystemContent;
import com.standardapplied.helios.anthropic.api.ToolDefinition;
import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicRequestPartsTest {

  @Test
  void anAssistantTurnWithoutMetadataOrTextSendsItsToolCalls() {
    var call = ToolCall.newBuilder().withId("t1").withName("search").build();
    var message = new Message(Role.ASSISTANT, "", List.of(call), null, null, null, List.of());

    var entry = AnthropicMessages.assistant(message, true);

    var blocks = assertInstanceOf(List.class, entry.content());
    assertEquals(1, blocks.size());
    assertEquals("tool_use", ((ContentBlock) blocks.getFirst()).type());
  }

  @Test
  void anAssistantTurnThatOnlyThoughtSendsItsThinkingBlock() {
    var message =
        Message.assistant(
            "",
            List.of(),
            Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, "[{\"text\":\"hm\",\"signature\":\"s\"}]"));

    var blocks = (List<?>) AnthropicMessages.assistant(message, true).content();

    assertEquals(List.of(ContentBlock.thinking("hm", "s")), blocks);
  }

  @Test
  void anAssistantTurnWithoutContentSendsOnlyItsToolCalls() {
    var call = ToolCall.newBuilder().withId("t1").withName("search").build();
    var message = new Message(Role.ASSISTANT, null, List.of(call), null, null, Map.of(), List.of());

    var blocks = (List<?>) AnthropicMessages.assistant(message, true).content();

    assertEquals(List.of(ContentBlock.toolUse("t1", "search", Map.of())), blocks);
  }

  @Test
  void emptyVerbatimContentFallsBackToTheTypedEcho() {
    var message = Message.assistant("Hi", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, ""));

    assertEquals("Hi", AnthropicMessages.assistant(message, true).content());
  }

  @Test
  void unreadableVerbatimContentIsRefused() {
    var message =
        Message.assistant("Hi", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, "{bad"));

    var failure =
        assertThrows(AnthropicException.class, () -> AnthropicMessages.assistant(message, true));

    assertTrue(failure.getMessage().startsWith("Corrupted raw content on assistant message"));
  }

  @Test
  void aUserTurnWithoutTextSendsOnlyItsAttachments() {
    var message =
        new Message(
            Role.USER,
            null,
            List.of(),
            null,
            null,
            Map.of(),
            List.of(InlineFile.of(new byte[] {'x'}, "text/x-unknown")));

    var entries = AnthropicMessages.entries(List.of(message), 0);

    var blocks = (List<?>) entries.getFirst().content();
    assertEquals(1, blocks.size());
    assertEquals("[attachment text/x-unknown]\nx", ((ContentBlock) blocks.getFirst()).text());
  }

  @Test
  void aToolResultRunEndsAtTheNextNonToolMessage() {
    var entries =
        AnthropicMessages.entries(
            List.of(
                Message.tool("t1", "search", "one"),
                Message.user("next"),
                Message.tool("t2", "search", "two")),
            0);

    assertEquals(3, entries.size());
  }

  @Test
  void aConversationsEntriesCannotBeChanged() {
    var entries = AnthropicMessages.entries(List.of(Message.user("hi")), 0);

    assertThrows(UnsupportedOperationException.class, entries::removeFirst);
  }

  @Test
  void placingBreakpointsLeavesTheConversationUntouched() {
    var entries = AnthropicMessages.entries(List.of(Message.user("hi"), Message.user("again")), 0);
    var request = MessagesRequest.newBuilder().withModel("m").withMaxTokens(1);

    PromptCache.apply(
        CachePolicy.shortLived(),
        request,
        null,
        AnthropicTools.of(List.of(), ModelConfig.of("k")),
        entries);

    assertEquals("again", entries.getLast().content());
    var marked = (List<?>) request.build().messages().getLast().content();
    assertEquals(
        CachePolicy.shortLived().breakpoint(), ((ContentBlock) marked.getFirst()).cacheControl());
  }

  @Test
  void aCachedRequestWithoutToolsOrMessagesMarksNothing() {
    var request = requests().build(List.of(), List.of(), null).body();

    assertNull(request.tools());
    assertEquals(List.of(), request.messages());
  }

  @Test
  void aCachedMessageWithoutBlocksIsLeftUnmarked() {
    var requests = requests();
    var empty = Message.assistant("", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, "[]"));

    var request =
        requests
            .build(
                new ArrayList<>(
                    List.of(Message.user("hi"), underCurrentPrompt(empty, requests, List.of()))),
                List.of(),
                null)
            .body();

    assertEquals(List.of(), request.messages().getLast().content());
  }

  @Test
  void aSchemaWithAnEmptyToolListAsksForJsonAlone() {
    var requests = requests();

    var request =
        requests.build(List.of(Message.user("hi")), List.of(), Map.of("type", "object")).body();

    var system = (List<?>) request.system();
    assertEquals(1, system.size());
    assertTrue(
        ((SystemContent) system.getFirst()).text().startsWith("You must respond with valid JSON"));
  }

  @Test
  void aSchemaWithoutAToolListAsksForJsonAlone() {
    var requests = requests();

    var request =
        requests.build(List.of(Message.user("hi")), null, Map.of("type", "object")).body();

    var system = (List<?>) request.system();
    assertEquals(1, system.size());
    assertTrue(
        ((SystemContent) system.getFirst()).text().startsWith("You must respond with valid JSON"));
  }

  @Test
  void aSchemaThatCannotBeWrittenFailsTheRequest() {
    var requests = requests();
    var schema = ConversationFixture.selfReferencing();

    var failure =
        assertThrows(
            AnthropicException.class,
            () -> requests.build(List.of(Message.user("hi")), List.of(), schema).body());

    assertEquals("Failed to serialize value", failure.getMessage());
  }

  @Test
  void aToolThatCannotBeWrittenFailsTheCallBeforeItIsSent() {
    var model =
        new AnthropicProvider()
            .create(
                AnthropicModelId.CLAUDE_OPUS_5_5.id(),
                ModelConfig.newBuilder().withApiKey("k").withBaseUrl("http://127.0.0.1:1").build());
    var tool = ConversationFixture.unwritableTool();

    var failure =
        assertThrows(
            AnthropicException.class, () -> model.chat(List.of(Message.user("hi")), List.of(tool)));

    assertEquals("Failed to serialize request", failure.getMessage());
  }

  @Test
  void aRequestBodyThatCannotBeWrittenIsNeverOpened() {
    var self = new HashMap<String, Object>();
    self.put("self", self);
    var body =
        MessagesRequest.newBuilder()
            .withModel("m")
            .withTools(List.of(new ToolDefinition(null, "t", "d", self, null)))
            .build();
    try (var client = HttpClientFactory.create()) {
      var streams = new AnthropicStreams(ModelConfig.of("k"), client, List.of());

      var failure =
          assertThrows(
              AnthropicException.class, () -> streams.open(new StampedRequest(body, "stamp")));

      assertEquals("Failed to serialize request", failure.getMessage());
    }
  }

  @Test
  void aKeylessRequestToACustomEndpointCarriesNoApiKeyHeader() {
    var config = ModelConfig.newBuilder().withBaseUrl("http://gateway.local/v1/messages").build();

    var request = httpRequest(config);

    assertEquals(URI.create("http://gateway.local/v1/messages"), request.uri());
    assertTrue(request.headers().firstValue("x-api-key").isEmpty());
  }

  @Test
  void thinkingMetadataWithoutBlocksDecodesToNone() {
    assertEquals(List.of(), ThinkingBlock.decodeAll(null));
    assertEquals(List.of(), ThinkingBlock.decodeAll(Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, "")));
    assertEquals(
        List.of(new ThinkingBlock("", "sig")),
        ThinkingBlock.decodeAll(
            Map.of(ThinkingBlock.THINKING_BLOCKS_KEY, "[{\"signature\":\"sig\"}]")));
  }

  @Test
  void aRequestsStampFollowsItsSystemPromptAndToolsOnly() {
    var tools = List.of(tool("weather", "Current weather"));
    var stamp = stamp(AnthropicModelId.CLAUDE_OPUS_5_5, CachePolicy.shortLived(), "sys", tools);

    assertEquals(
        stamp, stamp(AnthropicModelId.CLAUDE_OPUS_5_5, CachePolicy.shortLived(), "sys", tools));
    assertEquals(
        stamp, stamp(AnthropicModelId.CLAUDE_SONNET_4_6, CachePolicy.shortLived(), "sys", tools));
    assertEquals(
        stamp, stamp(AnthropicModelId.CLAUDE_OPUS_5_5, CachePolicy.disabled(), "sys", tools));
    assertNotEquals(
        stamp, stamp(AnthropicModelId.CLAUDE_OPUS_5_5, CachePolicy.shortLived(), "other", tools));
    assertNotEquals(
        stamp,
        stamp(
            AnthropicModelId.CLAUDE_OPUS_5_5,
            CachePolicy.shortLived(),
            "sys",
            List.of(tool("weather", "Current weather"), tool("time", "Current time"))));
    assertNotEquals(
        stamp,
        stamp(
            AnthropicModelId.CLAUDE_OPUS_5_5,
            CachePolicy.shortLived(),
            "sys",
            List.of(tool("weather", "Forecast"))));
    assertEquals(64, stamp.length());
  }

  @Test
  void theAssistantTurnCarriesTheStampOfTheRequestThatProducedIt() {
    var config = ModelConfig.newBuilder().withApiKey("k");
    var history = List.of(Message.system("sys"), Message.user("Hi"));
    var stamps = new ArrayList<String>();

    ModelHarness.exchange(
        List.of(Golden.read("anthropic/requests-reply.sse")),
        uri ->
            new AnthropicProvider()
                .create(
                    AnthropicModelId.CLAUDE_OPUS_5_5.id(),
                    config.withBaseUrl(uri + "/v1/messages").build()),
        model ->
            stamps.add(
                model
                    .chat(history, ConversationFixture.tools())
                    .metadata()
                    .get(ReasoningReplay.PROMPT_STAMP_KEY)));

    assertEquals(
        List.of(requests().build(history, ConversationFixture.tools(), null).promptStamp()),
        stamps);
  }

  private static String stamp(
      AnthropicModelId model, CachePolicy cachePolicy, String system, List<Tool> tools) {
    return new AnthropicRequestBuilder(model.id(), model, ModelConfig.of("k"), cachePolicy)
        .build(List.of(Message.system(system), Message.user("Hi")), tools, null)
        .promptStamp();
  }

  private static Tool tool(String name, String description) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription(description)
        .withExecutor((arguments, context) -> ToolResult.success("ok"))
        .build();
  }

  private static AnthropicRequestBuilder requests() {
    var model = AnthropicModelId.CLAUDE_OPUS_5_5;
    var config = ModelConfig.newBuilder().withApiKey("k").build();
    return new AnthropicRequestBuilder(model.id(), model, config, CachePolicy.shortLived());
  }

  private static HttpRequest httpRequest(ModelConfig config) {
    try (var client = HttpClient.newHttpClient()) {
      return new AnthropicStreams(config, client, List.of()).httpRequest("{}");
    }
  }
}
