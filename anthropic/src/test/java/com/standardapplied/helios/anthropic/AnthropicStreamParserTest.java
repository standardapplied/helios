/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
}
