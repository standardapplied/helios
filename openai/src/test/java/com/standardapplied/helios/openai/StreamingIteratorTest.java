/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.test.FailingInputStream;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.SseEvents;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamingIteratorTest {

  private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofMillis(200);

  private static final String TEXT_DELTA =
      "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
          + "\"content_index\":0,\"delta\":\"Hello\"}\n\n";

  private static final String RESPONSE_COMPLETED =
      "data: {\"type\":\"response.completed\",\"response\":{"
          + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
          + "\"output\":[],\"model\":\"gpt-4o\","
          + "\"usage\":{\"input_tokens\":25,\"output_tokens\":15,\"total_tokens\":40}}}\n\n";

  private static final String RESPONSE_COMPLETED_NO_USAGE =
      "data: {\"type\":\"response.completed\",\"response\":{"
          + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
          + "\"output\":[],\"model\":\"gpt-4o\"}}\n\n";

  /** Cache hit: 1920 of 2006 input tokens served from cache (canonical OpenAI shape). */
  private static final String RESPONSE_COMPLETED_WITH_CACHED_TOKENS =
      "data: {\"type\":\"response.completed\",\"response\":{"
          + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
          + "\"output\":[],\"model\":\"gpt-4o\","
          + "\"usage\":{\"input_tokens\":2006,\"output_tokens\":150,\"total_tokens\":2156,"
          + "\"input_tokens_details\":{\"cached_tokens\":1920}}}}\n\n";

  /** Below 1024-token threshold: server reports cached_tokens=0 explicitly. */
  private static final String RESPONSE_COMPLETED_WITH_ZERO_CACHED =
      "data: {\"type\":\"response.completed\",\"response\":{"
          + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
          + "\"output\":[],\"model\":\"gpt-4o\","
          + "\"usage\":{\"input_tokens\":50,\"output_tokens\":15,\"total_tokens\":65,"
          + "\"input_tokens_details\":{\"cached_tokens\":0}}}}\n\n";

  @Test
  void textDeltaEvents() {
    var events = drain(TEXT_DELTA + RESPONSE_COMPLETED);

    assertEquals(2, events.size());
    assertEquals("Hello", assertInstanceOf(StreamEvent.TextDelta.class, events.get(0)).text());
    var done = assertInstanceOf(StreamEvent.Done.class, events.get(1));
    assertEquals("Hello", done.response().content());
    assertEquals(FinishReason.STOP, done.response().finishReason());
  }

  @Test
  void toolCallFromStreaming() {
    var toolItemAdded =
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
            + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":\"\","
            + "\"status\":\"in_progress\"}}\n\n";

    var argsDelta1 =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"delta\":\"{\\\"city\\\"\"}\n\n";

    var argsDelta2 =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"delta\":\":\\\"NYC\\\"}\"}\n\n";

    var argsDone =
        "data: {\"type\":\"response.function_call_arguments.done\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"get_weather\","
            + "\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"}\n\n";

    var sse = toolItemAdded + argsDelta1 + argsDelta2 + argsDone + RESPONSE_COMPLETED;

    var events = drain(sse);
    assertEquals(3, events.size());
    assertInstanceOf(StreamEvent.ToolCallStart.class, events.get(0));
    var start = (StreamEvent.ToolCallStart) events.get(0);
    assertEquals("call_1", start.callId());
    assertEquals("get_weather", start.toolName());

    assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(1));
    var tc = ((StreamEvent.ToolCallComplete) events.get(1)).toolCall();
    assertEquals("get_weather", tc.name());
    assertEquals("call_1", tc.id());
    assertEquals(Map.of("city", "NYC"), tc.arguments());

    var done = (StreamEvent.Done) events.get(2);
    assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
    assertFalse(done.response().toolCalls().isEmpty());
  }

  @Test
  void reasoningSummaryCapture() {
    var reasoningDelta1 =
        "data: {\"type\":\"response.reasoning_summary_text.delta\","
            + "\"output_index\":0,\"text\":\"Let me think\"}\n\n";

    var reasoningDelta2 =
        "data: {\"type\":\"response.reasoning_summary_text.delta\","
            + "\"output_index\":0,\"text\":\" about this.\"}\n\n";

    var sse = reasoningDelta1 + reasoningDelta2 + TEXT_DELTA + RESPONSE_COMPLETED;

    var events = drain(sse);

    var done = (StreamEvent.Done) events.getLast();
    assertNotNull(done.response().thinking());
    assertEquals("Let me think about this.", done.response().thinking());
    assertTrue(done.response().metadata().containsKey(OpenAIResponseAssembler.REASONING_KEY));

    // Streaming surface: each reasoning delta arrives as ThinkingDelta.
    var thinkingDeltas =
        events.stream().filter(StreamEvent.ThinkingDelta.class::isInstance).count();
    assertEquals(2, thinkingDeltas);
  }

  @Test
  void reasoningSummaryEmitsThinkingComplete() {
    var reasoningDelta =
        "data: {\"type\":\"response.reasoning_summary_text.delta\","
            + "\"output_index\":0,\"text\":\"Done thinking.\"}\n\n";
    var reasoningDone =
        "data: {\"type\":\"response.reasoning_summary_text.done\"," + "\"output_index\":0}\n\n";
    var sse = reasoningDelta + reasoningDone + TEXT_DELTA + RESPONSE_COMPLETED;

    var events = drain(sse);
    var complete =
        events.stream()
            .filter(StreamEvent.ThinkingComplete.class::isInstance)
            .map(StreamEvent.ThinkingComplete.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("Done thinking.", complete.fullThinking());
  }

  @Test
  void usageFromCompletedEvent() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertNotNull(done.response().usage());
    assertEquals(25, done.response().usage().inputTokens());
    assertEquals(15, done.response().usage().outputTokens());
  }

  @Test
  void emptyAndDoneDataLinesAreSkipped() {
    var sse = "data: \n\ndata: [DONE]\n\n" + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
  }

  @Test
  void nonDataLinesAreIgnored() {
    var sse = "event: ping\n\n" + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
  }

  @Test
  void malformedJsonEmitsErrorEvent() {
    var sse = "data: {not valid json}\n\n" + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertTrue(events.size() >= 2);
    assertInstanceOf(StreamEvent.Error.class, events.get(0));
  }

  @Test
  void idleTimeoutEmitsErrorEvent() {
    var neverDelivers = new FeedableInputStream();

    try (var iterator =
        new SseReader(
            neverDelivers, SHORT_IDLE_TIMEOUT, new OpenAIStreamParser(), OpenAIException::new)) {
      assertTrue(iterator.hasNext());
      var error = assertInstanceOf(StreamEvent.Error.class, iterator.next());
      assertTrue(error.message().contains("idle timeout"));
      assertTrue(assertInstanceOf(OpenAIException.class, error.cause()).isRetryable());
    }
  }

  @Test
  void closeIsIdempotent() {
    var iterator = reader(SseEvents.body(TEXT_DELTA + RESPONSE_COMPLETED));
    iterator.close();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @Test
  void closeAfterPartialConsumption() {
    var iterator = reader(SseEvents.body(TEXT_DELTA + RESPONSE_COMPLETED));
    assertTrue(iterator.hasNext());
    iterator.next();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @Test
  void multipleTextDeltas() {
    var delta2 =
        "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
            + "\"content_index\":0,\"delta\":\" World\"}\n\n";

    var sse = TEXT_DELTA + delta2 + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(3, events.size());
    var done = (StreamEvent.Done) events.getLast();
    assertEquals("Hello World", done.response().content());
  }

  // ── prompt-caching usage surfacing (hv2-bug2 Issue 1 — OpenAI peer) ──────

  @Test
  void cachedTokensSurfaceThroughResponseUsageInDisjointShape() {
    // OpenAI reports input_tokens as the TOTAL (cached + uncached), with
    // input_tokens_details.cached_tokens as the cached subset. The Helios provider must
    // re-project into the disjoint Response.Usage shape so cost accounting doesn't
    // double-count cached tokens at the base input rate.
    var sse = TEXT_DELTA + RESPONSE_COMPLETED_WITH_CACHED_TOKENS;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    var usage = done.response().usage();
    assertEquals(
        2006 - 1920,
        usage.inputTokens(),
        "Helios inputTokens must be UNCACHED only — wire input_tokens minus cached_tokens");
    assertEquals(150, usage.outputTokens());
    assertEquals(
        0,
        usage.cacheCreationInputTokens(),
        "OpenAI does not premium cache writes, so cacheCreation is always 0");
    assertEquals(
        1920,
        usage.cacheReadInputTokens(),
        "cached_tokens from input_tokens_details surfaces as cacheReadInputTokens");
    assertEquals(
        (2006 - 1920) + 150 + 0 + 1920,
        usage.totalTokens(),
        "totalTokens sums every billable token across all four classes");
  }

  @Test
  void cachedTokensZeroProducesUsageWithDisjointSplit() {
    // Below the 1024-token cache threshold, OpenAI reports cached_tokens=0 explicitly.
    // Re-projection still applies: inputTokens stays at the full wire value, cache fields 0.
    var sse = TEXT_DELTA + RESPONSE_COMPLETED_WITH_ZERO_CACHED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    var usage = done.response().usage();
    assertEquals(50, usage.inputTokens());
    assertEquals(15, usage.outputTokens());
    assertEquals(0, usage.cacheReadInputTokens());
    assertEquals(0, usage.cacheCreationInputTokens());
    assertEquals(65, usage.totalTokens());
  }

  @Test
  void absentInputTokensDetailsTreatsCachedAsZero() {
    // Older API versions and OpenAI-compatible proxies omit input_tokens_details entirely.
    // The provider's cachedTokensOrZero() helper normalizes to 0 so we never NPE.
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    var usage = done.response().usage();
    assertEquals(25, usage.inputTokens());
    assertEquals(15, usage.outputTokens());
    assertEquals(0, usage.cacheReadInputTokens());
  }

  @Test
  void cachedTokensExceedingInputTokensDoesNotProduceNegativeUncached() {
    // Defensive: an OpenAI accounting bug that reports cached_tokens > input_tokens would
    // produce a negative uncached value in naive arithmetic. The provider clamps at zero —
    // under-reporting uncached is safer than emitting a nonsensical negative token count.
    var pathological =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
            + "\"output\":[],\"model\":\"gpt-4o\","
            + "\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"total_tokens\":120,"
            + "\"input_tokens_details\":{\"cached_tokens\":500}}}}\n\n";
    var sse = TEXT_DELTA + pathological;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    var usage = done.response().usage();
    assertEquals(0, usage.inputTokens(), "clamped to zero — never negative");
    assertEquals(500, usage.cacheReadInputTokens());
  }

  @Test
  void emptyStreamProducesDoneWithEmptyContent() {
    var sse = RESPONSE_COMPLETED_NO_USAGE;
    var events = drain(sse);
    assertEquals(1, events.size());
    var done = (StreamEvent.Done) events.getFirst();
    assertEquals("", done.response().content());
    assertEquals(FinishReason.STOP, done.response().finishReason());
  }

  @Test
  void noReasoningMetadataWhenNotPresent() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertNull(done.response().thinking());
    assertFalse(done.response().metadata().containsKey(OpenAIResponseAssembler.REASONING_KEY));
  }

  @Test
  void toolCallWithEmptyArgs() {
    var toolItemAdded =
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
            + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"list_items\",\"arguments\":\"\","
            + "\"status\":\"in_progress\"}}\n\n";

    var argsDone =
        "data: {\"type\":\"response.function_call_arguments.done\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"list_items\","
            + "\"arguments\":\"{}\"}\n\n";

    var sse = toolItemAdded + argsDone + RESPONSE_COMPLETED;

    var events = drain(sse);
    var tcComplete =
        events.stream()
            .filter(e -> e instanceof StreamEvent.ToolCallComplete)
            .map(e -> (StreamEvent.ToolCallComplete) e)
            .findFirst()
            .orElseThrow();
    assertEquals("list_items", tcComplete.toolCall().name());
    assertEquals(Map.of(), tcComplete.toolCall().arguments());
  }

  @Test
  void responseFailedEmitsError() {
    var failed =
        "data: {\"type\":\"response.failed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"failed\"}}\n\n";
    var sse = TEXT_DELTA + failed;

    var events = drain(sse);
    assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.Error));
  }

  @Test
  void errorEventEmitsError() {
    var error = "data: {\"type\":\"error\",\"message\":\"something went wrong\"}\n\n";
    var sse = error + TEXT_DELTA + RESPONSE_COMPLETED;

    var events = drain(sse);
    assertInstanceOf(StreamEvent.Error.class, events.get(0));
  }

  @Test
  void textDeltaNullDeltaIsSkipped() {
    var nullDelta =
        "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
            + "\"content_index\":0}\n\n";
    var sse = nullDelta + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    assertEquals("Hello", ((StreamEvent.TextDelta) events.get(0)).text());
  }

  @Test
  void outputItemAddedNullItemIsSkipped() {
    var nullItem = "data: {\"type\":\"response.output_item.added\",\"output_index\":0}\n\n";
    var sse = nullItem + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
  }

  @Test
  void outputItemAddedNonFunctionCallIsSkipped() {
    var messageItem =
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
            + "\"item\":{\"type\":\"message\",\"id\":\"msg_1\","
            + "\"role\":\"assistant\",\"content\":[],\"status\":\"in_progress\"}}\n\n";
    var sse = messageItem + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
  }

  @Test
  void argsDeltaNullDeltaIsSkipped() {
    var nullDelta =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"fc_1\"}\n\n";
    var sse = nullDelta + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
  }

  @Test
  void argsDeltaNullItemIdIsSkipped() {
    var nullItemId =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"delta\":\"test\"}\n\n";
    var sse = nullItemId + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
  }

  @Test
  void argsDoneNullItemIdIsSkipped() {
    var nullItemId =
        "data: {\"type\":\"response.function_call_arguments.done\"," + "\"output_index\":0}\n\n";
    var sse = nullItemId + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
  }

  @Test
  void argsDoneUnknownItemIdIsSkipped() {
    var unknownId =
        "data: {\"type\":\"response.function_call_arguments.done\","
            + "\"output_index\":0,\"item_id\":\"unknown_id\"}\n\n";
    var sse = unknownId + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
  }

  @Test
  void responseCompletedNullResponseUsesDefaults() {
    var noResponse = "data: {\"type\":\"response.completed\"}\n\n";
    var sse = TEXT_DELTA + noResponse;
    var events = drain(sse);
    assertEquals(2, events.size());
    var done = (StreamEvent.Done) events.getLast();
    assertNull(done.response().usage());
    assertEquals(FinishReason.STOP, done.response().finishReason());
  }

  @Test
  void responseCompletedNullUsage() {
    var noUsage =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\"}}\n\n";
    var sse = TEXT_DELTA + noUsage;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertNull(done.response().usage());
  }

  @Test
  void responseCompletedPartialUsageTokens() {
    var partialUsage =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"total_tokens\":10}}}\n\n";
    var sse = TEXT_DELTA + partialUsage;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertNotNull(done.response().usage());
    assertEquals(10, done.response().usage().inputTokens());
  }

  @Test
  void reasoningSummaryNullTextIsSkipped() {
    var nullText =
        "data: {\"type\":\"response.reasoning_summary_text.delta\"," + "\"output_index\":0}\n\n";
    var sse = nullText + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertNull(done.response().thinking());
  }

  @Test
  void malformedToolCallArgsUseFallback() {
    var toolItemAdded =
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
            + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"test_fn\",\"arguments\":\"\","
            + "\"status\":\"in_progress\"}}\n\n";

    var argsDelta =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"delta\":\"not valid json\"}\n\n";

    var argsDone =
        "data: {\"type\":\"response.function_call_arguments.done\","
            + "\"output_index\":0,\"item_id\":\"fc_1\"}\n\n";

    var sse = toolItemAdded + argsDelta + argsDone + RESPONSE_COMPLETED;

    var events = drain(sse);
    var tcComplete =
        events.stream()
            .filter(e -> e instanceof StreamEvent.ToolCallComplete)
            .map(e -> (StreamEvent.ToolCallComplete) e)
            .findFirst()
            .orElseThrow();
    assertTrue(tcComplete.toolCall().arguments().containsKey("_raw"));
    assertEquals("not valid json", tcComplete.toolCall().arguments().get("_raw"));
  }

  @Test
  void argsDeltaUnknownAccumulatorIsIgnored() {
    var delta =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"unknown_id\","
            + "\"delta\":\"some data\"}\n\n";
    var sse = delta + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
  }

  @Test
  void unknownEventTypeIsSkipped() {
    var unknown = "data: {\"type\":\"response.some_unknown_event\"}\n\n";
    var sse = unknown + TEXT_DELTA + RESPONSE_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
  }

  @Test
  void nextWithoutHasNextWorks() {
    try (var iterator = reader(SseEvents.body(TEXT_DELTA + RESPONSE_COMPLETED))) {
      var event = iterator.next();
      assertNotNull(event);
      assertInstanceOf(StreamEvent.TextDelta.class, event);
    }
  }

  @Test
  void hasNextCalledTwiceReturnsCachedEvent() {
    try (var iterator = reader(SseEvents.body(TEXT_DELTA + RESPONSE_COMPLETED))) {
      assertTrue(iterator.hasNext());
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.TextDelta.class, event);
    }
  }

  @Test
  void usageWithOnlyOutputTokens() {
    var response =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"output_tokens\":42,\"total_tokens\":42}}}\n\n";
    var sse = TEXT_DELTA + response;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertNotNull(done.response().usage());
    assertEquals(42, done.response().usage().outputTokens());
    assertEquals(0, done.response().usage().inputTokens());
  }

  @Test
  void ioExceptionDuringReadEmitsError() {
    firstError(FailingInputStream.onRead(new IOException("Simulated network error")));
  }

  @Test
  void runtimeExceptionFromReaderEmitsErrorEvent() {
    var error = firstError(FailingInputStream.onRead(new RuntimeException("Unexpected failure")));

    assertTrue(error.message().contains("Stream read error"));
  }

  @Test
  void interruptedThreadEmitsErrorEvent() {
    var events = SseEvents.drainInterrupted(StreamingIteratorTest::reader);

    assertFalse(events.isEmpty());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
  }

  @Test
  void incompleteStatusMapsToLength() {
    var incompleteResponse =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"incomplete\","
            + "\"output\":[],\"model\":\"gpt-4o\","
            + "\"usage\":{\"input_tokens\":25,\"output_tokens\":4096,\"total_tokens\":4121}}}\n\n";

    var sse = TEXT_DELTA + incompleteResponse;

    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertEquals(FinishReason.LENGTH, done.response().finishReason());
  }

  private static SseReader reader(InputStream body) {
    return new SseReader(
        body, SseEvents.NEVER_IDLE, new OpenAIStreamParser(), OpenAIException::new);
  }

  private static List<StreamEvent> drain(String sse) {
    return SseEvents.drain(reader(SseEvents.body(sse)));
  }

  private static StreamEvent.Error firstError(InputStream body) {
    return assertInstanceOf(StreamEvent.Error.class, SseEvents.drain(reader(body)).getFirst());
  }
}
