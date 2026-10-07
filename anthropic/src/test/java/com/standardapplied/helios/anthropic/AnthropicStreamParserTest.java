/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.drainSseFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicStreamParserTest {

  private final AnthropicStreamParser parser = new AnthropicStreamParser();

  @Test
  void eventsWithoutTheirPayloadChangeNothing() {
    assertNull(parser.parse("{\"type\":\"message_start\"}"));
    assertNull(parser.parse("{\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}"));
    assertNull(parser.parse("{\"type\":\"message_start\",\"message\":{\"usage\":{}}}"));
    assertNull(parser.parse("{\"type\":\"content_block_delta\",\"index\":0}"));
    assertNull(parser.parse("{\"type\":\"message_delta\"}"));
    assertNull(parser.parse("{\"type\":\"message_delta\",\"delta\":{},\"usage\":{}}"));
    assertNull(parser.parse("{\"type\":\"ping\"}"));
    assertNull(parser.parse("{}"));

    var done = assertInstanceOf(StreamEvent.Done.class, parser.complete());
    assertNull(done.response().usage());
    assertEquals(FinishReason.STOP, done.response().finishReason());
    assertEquals(Map.of(), done.response().metadata());
    assertFalse(parser.finished());
  }

  @Test
  void usageCountsOnlyWhatTheStreamReported() {
    parser.parse(
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"cache_read_input_tokens\":3}}}");

    assertEquals(
        Response.Usage.of(0, 0, 0, 3), ((StreamEvent.Done) parser.complete()).response().usage());
  }

  @Test
  void anyReportedTokenClassMakesTheUsage() {
    var outputOnly = new AnthropicStreamParser();
    outputOnly.parse("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":4}}");
    var cacheWritesOnly = new AnthropicStreamParser();
    cacheWritesOnly.parse(
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"cache_creation_input_tokens\":2}}}");

    assertEquals(
        Response.Usage.of(0, 4, 0, 0),
        ((StreamEvent.Done) outputOnly.complete()).response().usage());
    assertEquals(
        Response.Usage.of(0, 0, 2, 0),
        ((StreamEvent.Done) cacheWritesOnly.complete()).response().usage());
  }

  @Test
  void aToolCallWithoutAToolUseStopReasonStillFinishesWithToolCalls() {
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\"}}");
    parser.parse("{\"type\":\"content_block_stop\",\"index\":0}");

    var done =
        assertInstanceOf(StreamEvent.Done.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertTrue(parser.finished());
    assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
  }

  @Test
  void anErrorEventWithoutDetailsIsNotRetryable() {
    var error = assertInstanceOf(StreamEvent.Error.class, parser.parse("{\"type\":\"error\"}"));

    assertEquals("API stream error: {\"type\":\"error\"}", error.message());
    assertNull(error.cause());
  }

  @Test
  void streamingIteratorCapturesCacheCreationAndReadTokensFromMessageStart() throws Exception {
    var json =
        "data: {\"type\":\"message_start\","
            + "\"message\":{\"usage\":{\"input_tokens\":100,\"cache_creation_input_tokens\":900,"
            + "\"cache_read_input_tokens\":500000,\"output_tokens\":0}}}\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"text\"}}\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n"
            + "data: {\"type\":\"content_block_stop\",\"index\":0}\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":50}}\n"
            + "data: {\"type\":\"message_stop\"}\n";
    var done = drainSseFixture(json);
    assertNotNull(done);
    var usage = done.response().usage();
    assertEquals(100, usage.inputTokens());
    assertEquals(50, usage.outputTokens());
    assertEquals(
        900,
        usage.cacheCreationInputTokens(),
        "cache_creation_input_tokens from message_start surfaces in Response.Usage");
    assertEquals(
        500000,
        usage.cacheReadInputTokens(),
        "cache_read_input_tokens from message_start surfaces in Response.Usage");
    assertEquals(
        100 + 50 + 900 + 500000,
        usage.totalTokens(),
        "totalTokens sums every billable token class");
  }

  @Test
  void streamingIteratorEmitsUsageWhenOnlyCacheTokensAreReported() throws Exception {
    // Degenerate but legal: pure cache read, no uncached input. Anthropic still bills the cache
    // read tokens; the Usage must surface so cost tracking accounts for it.
    var json =
        "data: {\"type\":\"message_start\","
            + "\"message\":{\"usage\":{\"input_tokens\":0,\"cache_read_input_tokens\":1234,"
            + "\"output_tokens\":0}}}\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":0}}\n"
            + "data: {\"type\":\"message_stop\"}\n";
    var done = drainSseFixture(json);
    assertNotNull(done);
    assertNotNull(
        done.response().usage(),
        "any non-zero token class must surface a Usage record so cost tracking is not lost");
    assertEquals(1234, done.response().usage().cacheReadInputTokens());
  }
}
