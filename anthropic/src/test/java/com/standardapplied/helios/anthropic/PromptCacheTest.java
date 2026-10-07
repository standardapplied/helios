/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.INTERLEAVED_THINKING_SSE;
import static com.standardapplied.helios.anthropic.AnthropicFixture.drainSseFixture;
import static com.standardapplied.helios.anthropic.AnthropicFixture.model;
import static com.standardapplied.helios.anthropic.AnthropicFixture.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptCacheTest {

  @Test
  void promptCachingDefaultsOn() {
    assertNotNull(
        SentCacheControl.onSystemPrompt(at -> model(AnthropicModelId.CLAUDE_OPUS_4_7, at)),
        "Helios bills Anthropic via prompt caching by default — opt-out is explicit");
  }

  @Test
  void promptCachingDisabledViaCachePolicy() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var request =
        requests(AnthropicModelId.CLAUDE_OPUS_4_7, config, CachePolicy.disabled())
            .build(List.of(Message.system("Be helpful"), Message.user("Hi")), List.of(), null);

    // System emits the legacy plain-string shape — no cache_control, no array wrapping.
    assertEquals("Be helpful", request.system());
    // Last message stays as a String — caching annotation requires the array shape.
    assertEquals("Hi", request.messages().getFirst().content());
    assertNull(
        SentCacheControl.onSystemPrompt(
            at -> model(AnthropicModelId.CLAUDE_OPUS_4_7, at, CachePolicy.disabled())));
  }

  @Test
  void disabledCachePolicyHasNoBreakpoint() {
    assertNull(CachePolicy.disabled().breakpoint());
  }

  @Test
  void cachePolicyLongLivedAnnotatesWithOneHourTtl() {
    // 1h TTL is opt-in; cache write at 2x base, read still at 0.10x. Verify the breakpoint
    // payload reaches the system block intact so Anthropic bills the correct rate.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var request =
        requests(AnthropicModelId.CLAUDE_OPUS_4_7, config, CachePolicy.longLived())
            .build(List.of(Message.system("Be helpful"), Message.user("Hi")), List.of(), null);

    @SuppressWarnings("unchecked")
    var systemBlocks =
        (List<com.standardapplied.helios.anthropic.api.SystemContent>) request.system();
    var cc = systemBlocks.getFirst().cacheControl();
    assertNotNull(cc);
    assertEquals(com.standardapplied.helios.anthropic.api.CacheControl.TYPE_EPHEMERAL, cc.type());
    assertEquals(
        com.standardapplied.helios.anthropic.api.CacheControl.TTL_1_HOUR,
        cc.ttl(),
        "long-lived CachePolicy must propagate ttl='1h' to every cache breakpoint");
    assertEquals(
        SentCacheControl.LONG_LIVED,
        SentCacheControl.onSystemPrompt(
            at -> model(AnthropicModelId.CLAUDE_OPUS_4_7, at, CachePolicy.longLived())));
  }

  @Test
  void cachePolicyShortLivedHasNoExplicitTtl() {
    // 5m is Anthropic's implicit default; sending ttl='5m' would still work but adds wire bloat.
    // Verify the short-lived policy emits a breakpoint with no ttl field set.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config, CachePolicy.shortLived());
    var request =
        requests.build(List.of(Message.system("Be helpful"), Message.user("Hi")), List.of(), null);

    @SuppressWarnings("unchecked")
    var systemBlocks =
        (List<com.standardapplied.helios.anthropic.api.SystemContent>) request.system();
    var cc = systemBlocks.getFirst().cacheControl();
    assertNotNull(cc);
    assertNull(cc.ttl(), "short-lived policy must omit ttl so the wire stays minimal");
  }

  @Test
  void promptCachingAnnotatesSystemPromptWithCacheControl() {
    // hv2-bug2 Issue 1 regression: without cache_control on the system prefix the Anthropic
    // server bills every input token at the base rate, producing the Light Grid matchmaking
    // baseline's flat $235.54 across 24 viewers. Annotated requests get the cache discount.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request =
        requests.build(
            List.of(Message.system("You are a careful assistant."), Message.user("Hi")),
            List.of(),
            null);

    @SuppressWarnings("unchecked")
    var systemBlocks =
        (List<com.standardapplied.helios.anthropic.api.SystemContent>) request.system();
    assertEquals(1, systemBlocks.size());
    var block = systemBlocks.getFirst();
    assertEquals("text", block.type());
    assertEquals("You are a careful assistant.", block.text());
    assertNotNull(
        block.cacheControl(),
        "system prefix must carry a cache_control breakpoint for the default agent-loop pattern");
    assertEquals(
        com.standardapplied.helios.anthropic.api.CacheControl.TYPE_EPHEMERAL,
        block.cacheControl().type());
  }

  @Test
  void promptCachingOmitsSystemBlockWhenSystemPromptIsBlank() {
    // No system prompt set; caching ON. The model must not synthesize an empty SystemContent
    // block — Anthropic rejects empty system arrays and an empty text block wastes a breakpoint.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);
    assertNull(request.system(), "blank system must serialize as omitted, not as an empty array");
  }

  @Test
  void promptCachingAnnotatesLastToolWithCacheControl() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var tool1 =
        Tool.newBuilder()
            .withName("search")
            .withDescription("search the web")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("q")
                    .withType(ParameterType.STRING)
                    .withDescription("query")
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var tool2 =
        Tool.newBuilder()
            .withName("calculator")
            .withDescription("compute arithmetic")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("expr")
                    .withType(ParameterType.STRING)
                    .withDescription("the expression")
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool1, tool2), null);

    assertEquals(2, request.tools().size());
    assertNull(
        request.tools().get(0).cacheControl(),
        "non-tail tool blocks must NOT carry cache_control — wastes a breakpoint");
    assertNotNull(
        request.tools().get(1).cacheControl(),
        "the last tool anchors the cache breakpoint covering the entire tools array");
    assertEquals(
        com.standardapplied.helios.anthropic.api.CacheControl.TYPE_EPHEMERAL,
        request.tools().get(1).cacheControl().type());
  }

  @Test
  void promptCachingSingleToolGetsCacheControl() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var tool =
        Tool.newBuilder()
            .withName("solo")
            .withDescription("a lone tool")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var request = requests.build(List.of(Message.user("Hi")), List.of(tool), null);
    assertNotNull(request.tools().getFirst().cacheControl());
  }

  @Test
  void promptCachingOmitsToolBreakpointWhenToolsEmpty() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);
    assertNull(request.tools(), "empty tools list serializes as omitted, no breakpoint");
  }

  @Test
  void promptCachingPromotesLastMessageStringToBlockWithCacheControl() {
    // String content cannot carry cache_control on the wire — must be promoted to a single-text
    // block. This test guards the promotion path: a single string-content user message becomes a
    // single-block list with the block annotated.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = requests.build(List.of(Message.user("Latest turn")), List.of(), null);

    var last = request.messages().getLast();
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) last.content();
    assertEquals(1, blocks.size());
    assertEquals("text", blocks.getFirst().type());
    assertEquals("Latest turn", blocks.getFirst().text());
    assertNotNull(
        blocks.getFirst().cacheControl(),
        "the last message must anchor a cache breakpoint so the next turn reuses the prefix");
  }

  @Test
  void promptCachingAnnotatesOnlyTailBlockOfMultiBlockMessage() {
    // A multi-block message (e.g. text + image) must annotate the LAST block. Anthropic accepts a
    // single cache_control per request slot; annotating multiple blocks of the same message would
    // burn breakpoints with no incremental cacheability.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    var msg = Message.user("Look at this", List.of(InlineFile.of(pngBytes, "image/png")));
    var request = requests.build(List.of(msg), List.of(), null);

    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) request.messages().getFirst().content();
    assertEquals(2, blocks.size());
    assertNull(blocks.get(0).cacheControl(), "non-tail block must not carry cache_control");
    assertNotNull(blocks.get(1).cacheControl(), "tail block must carry cache_control");
  }

  @Test
  void promptCachingAnnotatesLastTwoMessagesForRollingLookback() {
    // Multiple user/assistant turns: BOTH the last and second-to-last messages get cache_control
    // breakpoints. The pair gives the next turn's lookback a stable rolling write to find within
    // Anthropic's 20-block lookback window, which would otherwise blind out the system+tools
    // cache after roughly five tool-heavy agent turns.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request =
        requests.build(
            List.of(Message.user("hello"), Message.assistant("hi"), Message.user("how are you")),
            List.of(),
            null);

    assertEquals(3, request.messages().size());
    // First user (anchor of conversation) — plain string, no annotation.
    assertEquals("hello", request.messages().get(0).content());
    // Penultimate (assistant) — promoted to block list with cache_control on the tail block.
    @SuppressWarnings("unchecked")
    var penultimateBlocks = (List<ContentBlock>) request.messages().get(1).content();
    assertEquals(1, penultimateBlocks.size());
    assertNotNull(
        penultimateBlocks.getFirst().cacheControl(),
        "rolling lookback requires the penultimate message to carry a cache breakpoint");
    // Last user — promoted to block list with cache_control on the tail block.
    @SuppressWarnings("unchecked")
    var lastBlocks = (List<ContentBlock>) request.messages().get(2).content();
    assertEquals(1, lastBlocks.size());
    assertNotNull(lastBlocks.getFirst().cacheControl());
  }

  @Test
  void promptCachingSingleMessageOmitsPenultimateBreakpoint() {
    // Single-message request: there's no second-to-last to annotate. Only the last gets a
    // breakpoint — no synthetic empty breakpoint, no off-by-one.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = requests.build(List.of(Message.user("solo")), List.of(), null);

    assertEquals(1, request.messages().size());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) request.messages().getFirst().content();
    assertNotNull(blocks.getFirst().cacheControl());
  }

  @Test
  void promptCachingAnnotatesPenultimateOnPriorAssistantMessage() {
    // Verify the rolling breakpoint lands on an ASSISTANT message when that's the penultimate
    // entry — Anthropic accepts cache_control on assistant content blocks too.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request =
        requests.build(List.of(Message.user("hi"), Message.assistant("hello!")), List.of(), null);

    @SuppressWarnings("unchecked")
    var penultimate = (List<ContentBlock>) request.messages().get(0).content();
    @SuppressWarnings("unchecked")
    var last = (List<ContentBlock>) request.messages().get(1).content();
    assertEquals("user", request.messages().get(0).role());
    assertEquals("assistant", request.messages().get(1).role());
    assertNotNull(penultimate.getFirst().cacheControl());
    assertNotNull(last.getFirst().cacheControl());
  }

  @Test
  void promptCachingSkipsMessageBreakpointForEmptyContent() {
    // Pathological: a user message with an empty string content (nothing to cache). The
    // annotator must skip rather than synthesize an empty block. The other breakpoints (system,
    // tools) still apply.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var request = requests.build(List.of(Message.system("S"), Message.user("")), List.of(), null);
    // Last message stays as an empty string — no promotion to an empty block.
    assertEquals("", request.messages().getFirst().content());
    // System still cached.
    @SuppressWarnings("unchecked")
    var systemBlocks =
        (List<com.standardapplied.helios.anthropic.api.SystemContent>) request.system();
    assertNotNull(systemBlocks.getFirst().cacheControl());
  }

  @Test
  void promptCachingProducesFullBreakpointBudgetForMultiTurnAgentLoop() {
    // The canonical multi-turn agent placement: system + tools + penultimate-message +
    // last-message = 4 breakpoints. The penultimate breakpoint maintains rolling cache lookup
    // within Anthropic's 20-block lookback window for long conversations.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var tool =
        Tool.newBuilder()
            .withName("only")
            .withDescription("only tool")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var request =
        requests.build(
            List.of(
                Message.system("be helpful"),
                Message.user("hello"),
                Message.assistant("hi"),
                Message.user("how are you")),
            List.of(tool),
            null);

    var breakpointCount = 0;
    @SuppressWarnings("unchecked")
    var systemBlocks =
        (List<com.standardapplied.helios.anthropic.api.SystemContent>) request.system();
    if (systemBlocks.getLast().cacheControl() != null) breakpointCount++;
    if (request.tools().getLast().cacheControl() != null) breakpointCount++;
    @SuppressWarnings("unchecked")
    var penultimateBlocks =
        (List<ContentBlock>) request.messages().get(request.messages().size() - 2).content();
    if (penultimateBlocks.getLast().cacheControl() != null) breakpointCount++;
    @SuppressWarnings("unchecked")
    var lastBlocks = (List<ContentBlock>) request.messages().getLast().content();
    if (lastBlocks.getLast().cacheControl() != null) breakpointCount++;

    assertEquals(
        4,
        breakpointCount,
        "multi-turn agent loop uses Anthropic's full 4-breakpoint cache budget for rolling"
            + " lookback");
  }

  @Test
  void promptCachingSingleTurnUsesThreeBreakpoints() {
    // First-turn shape (no prior conversation): system + tools + last-message = 3 breakpoints.
    // No penultimate to annotate, so we stay one under the 4-breakpoint budget.
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    var tool =
        Tool.newBuilder()
            .withName("only")
            .withDescription("only tool")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    var request =
        requests.build(
            List.of(Message.system("be helpful"), Message.user("hi")), List.of(tool), null);

    var breakpointCount = 0;
    @SuppressWarnings("unchecked")
    var systemBlocks =
        (List<com.standardapplied.helios.anthropic.api.SystemContent>) request.system();
    if (systemBlocks.getLast().cacheControl() != null) breakpointCount++;
    if (request.tools().getLast().cacheControl() != null) breakpointCount++;
    @SuppressWarnings("unchecked")
    var lastBlocks = (List<ContentBlock>) request.messages().getLast().content();
    if (lastBlocks.getLast().cacheControl() != null) breakpointCount++;

    assertEquals(
        3, breakpointCount, "single-turn first-call uses 3 of 4 breakpoints — no penultimate");
  }

  @Test
  @SuppressWarnings("unchecked")
  void rawEchoTurnEndingInAToolCallStillCarriesTheCacheBreakpoint() throws Exception {
    var response = drainSseFixture(INTERLEAVED_THINKING_SSE).response();
    var storedRawContent = response.metadata().get(RawContentEcho.RAW_CONTENT_KEY);
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5_5, config);
    var assistantMessage = response.toMessage();

    var request =
        requests.build(
            List.of(
                Message.user("Compare profiles 1 and 2"),
                assistantMessage,
                Message.tool("toolu_1", "get_profile", "profile one"),
                Message.tool("toolu_2", "get_profile", "profile two")),
            List.of(),
            null);

    var assistant = (List<Map<String, Object>>) request.messages().get(1).content();
    assertNotNull(
        assistant.getLast().get("cache_control"),
        "the penultimate-message breakpoint keeps the rolling cache prefix alive");
    assertTrue(
        assistant.subList(0, 3).stream().noneMatch(block -> block.containsKey("cache_control")));
    assertEquals(
        storedRawContent,
        assistantMessage.metadata().get(RawContentEcho.RAW_CONTENT_KEY),
        "annotation must never leak into the stored turn");
  }

  @Test
  @SuppressWarnings("unchecked")
  void rawEchoTurnEndingInAnythingButAClientToolCallIsNeverAnnotated() {
    var rawJson =
        "[{\"type\":\"redacted_thinking\",\"data\":\"ENC\"},"
            + "{\"type\":\"text\",\"text\":\"Done.\"}]";
    var assistant =
        Message.assistant("Done.", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, rawJson));
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5_5, config);

    var request =
        requests.build(
            List.of(Message.user("go"), assistant, Message.user("more")), List.of(), null);

    var blocks = (List<Map<String, Object>>) request.messages().get(1).content();
    assertEquals(
        List.of(
            Map.of("type", "redacted_thinking", "data", "ENC"),
            Map.of("type", "text", "text", "Done.")),
        blocks);
  }

  @Test
  void rawEchoTurnEndingInANonObjectBlockIsLeftUntouched() {
    var assistant =
        Message.assistant(
            "Done.", List.of(), Map.of(RawContentEcho.RAW_CONTENT_KEY, "[\"not-a-block\"]"));
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5_5, config);

    var request =
        requests.build(
            List.of(Message.user("go"), assistant, Message.user("more")), List.of(), null);

    assertEquals(List.of("not-a-block"), request.messages().get(1).content());
  }
}
