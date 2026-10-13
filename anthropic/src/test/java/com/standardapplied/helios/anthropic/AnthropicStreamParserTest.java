/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.drainSseFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.List;
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
    assertNull(parser.parse("{\"type\":\"future_event\",\"stop_reason\":\"end_turn\"}"));
    assertNull(parser.parse("{}"));

    var partial = parser.partial();
    assertNull(partial.usage());
    assertEquals(FinishReason.ERROR, partial.finishReason());
    assertEquals(Map.of(), partial.metadata());
  }

  @Test
  void usageCountsOnlyWhatTheStreamReported() {
    parser.parse(
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"cache_read_input_tokens\":3}}}");

    assertEquals(Response.Usage.of(0, 0, 0, 3), parser.partial().usage());
  }

  @Test
  void anyReportedTokenClassMakesTheUsage() {
    var outputOnly = new AnthropicStreamParser();
    outputOnly.parse("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":4}}");
    var cacheWritesOnly = new AnthropicStreamParser();
    cacheWritesOnly.parse(
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"cache_creation_input_tokens\":2}}}");

    assertEquals(Response.Usage.of(0, 4, 0, 0), outputOnly.partial().usage());
    assertEquals(Response.Usage.of(0, 0, 2, 0), cacheWritesOnly.partial().usage());
  }

  @Test
  void aToolCallWithAnEndTurnStopReasonStillFinishesWithToolCalls() {
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\"}}");
    parser.parse("{\"type\":\"content_block_stop\",\"index\":0}");
    parser.parse("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");

    var done =
        assertInstanceOf(StreamEvent.Done.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
  }

  @Test
  void aMessageStopWithoutAStopReasonIsAnError() {
    parser.parse(
        "{\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}");

    var error =
        assertInstanceOf(StreamEvent.Error.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertEquals("message_stop with unknown stop_reason: null", error.message());
    assertNull(error.cause());
    assertEquals("Hi", error.partial().content());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
  }

  @Test
  void aMessageStopWithAStopReasonThisClientDoesNotKnowIsAnError() {
    parser.parse("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"compaction\"}}");

    var error =
        assertInstanceOf(StreamEvent.Error.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertEquals("message_stop with unknown stop_reason: compaction", error.message());
    assertEquals("compaction", error.partial().metadata().get("anthropic.stopReason"));
  }

  @Test
  void aMessageStopWhileAToolUseIsOpenIsAnErrorWithoutThatCall() {
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\"}}");
    parser.parse(
        "{\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":\"}}");
    parser.parse("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}");

    var error =
        assertInstanceOf(StreamEvent.Error.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertEquals("message_stop while tool use 'search' is still open", error.message());
    assertEquals(List.of(), error.partial().toolCalls());
  }

  @Test
  void aMessageStopWhileAServerToolUseIsOpenIsAnError() {
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
            + "{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\"}}");
    parser.parse("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");

    var error =
        assertInstanceOf(StreamEvent.Error.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertEquals("message_stop while tool use 'web_search' is still open", error.message());
  }

  @Test
  void aMessageStopWhileANamelessToolUseIsOpenIsAnError() {
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\"}}");
    parser.parse(
        "{\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\"}}");
    parser.parse("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}");

    var error =
        assertInstanceOf(StreamEvent.Error.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertEquals("message_stop while tool use '<unnamed>' is still open", error.message());
    assertEquals(List.of(), error.partial().toolCalls());
  }

  @Test
  void aMessageStopWhileANamelessServerToolUseIsOpenIsAnError() {
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
            + "{\"type\":\"server_tool_use\",\"id\":\"s1\"}}");
    parser.parse("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");

    var error =
        assertInstanceOf(StreamEvent.Error.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertEquals("message_stop while tool use '<unnamed>' is still open", error.message());
  }

  @Test
  void anErrorBeforeAnyMessageDeltaKeepsTheOutputTokensOfMessageStart() {
    parser.parse(
        "{\"type\":\"message_start\",\"message\":{\"usage\":"
            + "{\"input_tokens\":25,\"output_tokens\":3}}}");

    var error =
        assertInstanceOf(
            StreamEvent.Error.class,
            parser.parse("{\"type\":\"error\",\"error\":{\"type\":\"api_error\"}}"));

    assertEquals(3, error.partial().usage().outputTokens());
    assertEquals(28, error.partial().usage().totalTokens());
  }

  @Test
  void theCumulativeUsageOfMessageDeltaReplacesTheCountsOfMessageStart() {
    parser.parse(
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":2679,"
            + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,"
            + "\"output_tokens\":3}}}");
    parser.parse(
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"input_tokens\":10682,\"cache_creation_input_tokens\":4,"
            + "\"cache_read_input_tokens\":5,\"output_tokens\":510,"
            + "\"server_tool_use\":{\"web_search_requests\":1}}}");

    var done =
        assertInstanceOf(StreamEvent.Done.class, parser.parse("{\"type\":\"message_stop\"}"));

    assertEquals(Response.Usage.of(10682, 510, 4, 5), done.response().usage());
  }

  @Test
  void aMessageDeltaWithOnlyOutputTokensKeepsTheInputCountsOfMessageStart() {
    parser.parse(
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":25,"
            + "\"cache_creation_input_tokens\":5,\"cache_read_input_tokens\":7,"
            + "\"output_tokens\":1}}}");
    parser.parse("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":15}}");

    assertEquals(Response.Usage.of(25, 15, 5, 7), parser.partial().usage());
  }

  @Test
  void toolInputThatIsNotAJsonObjectEndsTheStreamNamingTheTool() {
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\"}}");
    parser.parse(
        "{\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"[1]\"}}");

    var error =
        assertInstanceOf(
            StreamEvent.Error.class, parser.parse("{\"type\":\"content_block_stop\",\"index\":0}"));

    assertTrue(
        error.message().startsWith("Arguments of tool call 'search' are not a JSON object: "),
        error.message());
    assertInstanceOf(AnthropicException.class, error.cause());
    assertEquals(List.of(), error.partial().toolCalls());
  }

  @Test
  void aServerToolInputThatIsNotAJsonObjectEndsTheStreamNamingTheTool() {
    parser.parse(
        "{\"type\":\"content_block_delta\",\"index\":1,"
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"I will search.\"}}");
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
            + "{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\"}}");
    parser.parse(
        "{\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"null\"}}");

    var error =
        assertInstanceOf(
            StreamEvent.Error.class, parser.parse("{\"type\":\"content_block_stop\",\"index\":0}"));

    assertEquals(
        "Arguments of tool call 'web_search' are not a JSON object: null", error.message());
    assertEquals("I will search.", error.partial().content());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
  }

  @Test
  void aJsonNullArgumentValueIsKept() {
    parser.parse(
        "{\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\"}}");
    parser.parse(
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
            + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":null}\"}}");

    var complete =
        assertInstanceOf(
            StreamEvent.ToolCallComplete.class,
            parser.parse("{\"type\":\"content_block_stop\",\"index\":0}"));

    assertTrue(complete.toolCall().arguments().containsKey("q"));
    assertNull(complete.toolCall().arguments().get("q"));
  }

  @Test
  void anErrorEventWithoutDetailsIsNotRetryable() {
    var error = assertInstanceOf(StreamEvent.Error.class, parser.parse("{\"type\":\"error\"}"));

    assertEquals("API stream error: {\"type\":\"error\"}", error.message());
    assertNull(error.cause());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
  }

  @Test
  void streamingIteratorCapturesCacheCreationAndReadTokensFromMessageStart() throws Exception {
    var json =
        "data: {\"type\":\"message_start\","
            + "\"message\":{\"usage\":{\"input_tokens\":100,\"cache_creation_input_tokens\":900,"
            + "\"cache_read_input_tokens\":500000,\"output_tokens\":0}}}\n\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"text\"}}\n\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n"
            + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":50}}\n\n"
            + "data: {\"type\":\"message_stop\"}\n\n";
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
            + "\"output_tokens\":0}}}\n\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":0}}\n\n"
            + "data: {\"type\":\"message_stop\"}\n\n";
    var done = drainSseFixture(json);
    assertNotNull(done);
    assertNotNull(
        done.response().usage(),
        "any non-zero token class must surface a Usage record so cost tracking is not lost");
    assertEquals(1234, done.response().usage().cacheReadInputTokens());
  }
}
