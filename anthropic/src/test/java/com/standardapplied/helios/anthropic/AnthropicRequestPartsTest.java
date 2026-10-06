/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.AnthropicModelId.ThinkingShape;
import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.anthropic.api.SystemContent;
import com.standardapplied.helios.anthropic.api.ThinkingConfig;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.ConversationFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicRequestPartsTest {

  @Test
  void anUnsetThinkingLevelIsTheShapesLowestSetting() {
    assertEquals(
        ThinkingConfig.disabled(),
        AnthropicThinking.of(ThinkingShape.ADAPTIVE_DEFAULT_ON, null, "m").thinking());
  }

  @Test
  void onlyAThinkingConfigOtherThanDisabledThinks() {
    assertFalse(new AnthropicThinking(null, null).thinks());
    assertFalse(new AnthropicThinking(ThinkingConfig.disabled(), null).thinks());
    assertTrue(new AnthropicThinking(ThinkingConfig.adaptive(), null).thinks());
  }

  @Test
  void anAssistantTurnWithoutMetadataOrTextSendsItsToolCalls() {
    var call = ToolCall.newBuilder().withId("t1").withName("search").build();
    var message = new Message(Role.ASSISTANT, "", List.of(call), null, null, null, List.of());

    var entry = AnthropicMessages.assistant(message);

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

    var blocks = (List<?>) AnthropicMessages.assistant(message).content();

    assertEquals(List.of(ContentBlock.thinking("hm", "s")), blocks);
  }

  @Test
  void anAssistantTurnWithoutContentSendsOnlyItsToolCalls() {
    var call = ToolCall.newBuilder().withId("t1").withName("search").build();
    var message = new Message(Role.ASSISTANT, null, List.of(call), null, null, Map.of(), List.of());

    var blocks = (List<?>) AnthropicMessages.assistant(message).content();

    assertEquals(List.of(ContentBlock.toolUse("t1", "search", Map.of())), blocks);
  }

  @Test
  void emptyVerbatimContentFallsBackToTheTypedEcho() {
    var message = Message.assistant("Hi", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, ""));

    assertEquals("Hi", AnthropicMessages.assistant(message).content());
  }

  @Test
  void unreadableVerbatimContentIsRefused() {
    var message =
        Message.assistant("Hi", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, "{bad"));

    var failure =
        assertThrows(AnthropicException.class, () -> AnthropicMessages.assistant(message));

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

    var conversation = AnthropicMessages.of(List.of(message));

    var blocks = (List<?>) conversation.entries().getFirst().content();
    assertEquals(1, blocks.size());
    assertEquals("[attachment text/x-unknown]\nx", ((ContentBlock) blocks.getFirst()).text());
  }

  @Test
  void aToolResultRunEndsAtTheNextNonToolMessage() {
    var conversation =
        AnthropicMessages.of(
            List.of(
                Message.tool("t1", "search", "one"),
                Message.user("next"),
                Message.tool("t2", "search", "two")));

    assertEquals(3, conversation.entries().size());
  }

  @Test
  void aConversationsEntriesCannotBeChanged() {
    var conversation = AnthropicMessages.of(List.of(Message.user("hi")));

    assertThrows(UnsupportedOperationException.class, () -> conversation.entries().removeFirst());
  }

  @Test
  void placingBreakpointsLeavesTheConversationUntouched() {
    var conversation = AnthropicMessages.of(List.of(Message.user("hi"), Message.user("again")));
    var request = MessagesRequest.newBuilder().withModel("m").withMaxTokens(1);

    PromptCache.apply(
        CachePolicy.shortLived(),
        request,
        null,
        AnthropicTools.of(List.of(), ModelConfig.of("k")),
        conversation.entries());

    assertEquals("again", conversation.entries().getLast().content());
    var marked = (List<?>) request.build().messages().getLast().content();
    assertEquals(
        CachePolicy.shortLived().breakpoint(), ((ContentBlock) marked.getFirst()).cacheControl());
  }

  @Test
  void aCachedRequestWithoutToolsOrMessagesMarksNothing() {
    var request = requests().build(List.of(), List.of(), null);

    assertNull(request.tools());
    assertEquals(List.of(), request.messages());
  }

  @Test
  void aCachedMessageWithoutBlocksIsLeftUnmarked() {
    var requests = requests();
    var empty = Message.assistant("", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, "[]"));

    var request =
        requests.build(new ArrayList<>(List.of(Message.user("hi"), empty)), List.of(), null);

    assertEquals(List.of(), request.messages().getLast().content());
  }

  @Test
  void aSchemaWithAnEmptyToolListAsksForJsonAlone() {
    var requests = requests();

    var request = requests.build(List.of(Message.user("hi")), List.of(), Map.of("type", "object"));

    var system = (List<?>) request.system();
    assertEquals(1, system.size());
    assertTrue(
        ((SystemContent) system.getFirst()).text().startsWith("You must respond with valid JSON"));
  }

  @Test
  void aSchemaWithoutAToolListAsksForJsonAlone() {
    var requests = requests();

    var request = requests.build(List.of(Message.user("hi")), null, Map.of("type", "object"));

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
            () -> requests.build(List.of(Message.user("hi")), List.of(), schema));

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

  private static AnthropicRequestBuilder requests() {
    var model = AnthropicModelId.CLAUDE_OPUS_5_5;
    var config = ModelConfig.newBuilder().withApiKey("k").build();
    return new AnthropicRequestBuilder(model.id(), model, config, CachePolicy.shortLived());
  }

  private static HttpRequest httpRequest(ModelConfig config) {
    try (var client = HttpClient.newHttpClient()) {
      return new AnthropicStreams(config, client).httpRequest("{}");
    }
  }
}
