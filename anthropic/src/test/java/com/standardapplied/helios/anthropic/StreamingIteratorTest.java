/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.core.test.SseEvents.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class StreamingIteratorTest {

  private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofMillis(200);

  private static final String MESSAGE_START =
      named(
          "message_start",
          "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
              + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-6-20250514\","
              + "\"stop_reason\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}");

  private static final String TEXT_BLOCK_START =
      named(
          "content_block_start",
          "{\"type\":\"content_block_start\",\"index\":0,"
              + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");

  private static final String TEXT_DELTA =
      named(
          "content_block_delta",
          "{\"type\":\"content_block_delta\",\"index\":0,"
              + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");

  private static final String CONTENT_BLOCK_STOP_0 =
      named("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

  private static final String MESSAGE_DELTA_END_TURN =
      named(
          "message_delta",
          "{\"type\":\"message_delta\","
              + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
              + "\"usage\":{\"output_tokens\":15}}");

  private static final String MESSAGE_STOP = named("message_stop", "{\"type\":\"message_stop\"}");

  private static final String HELLO_TURN =
      MESSAGE_START
          + TEXT_BLOCK_START
          + TEXT_DELTA
          + CONTENT_BLOCK_STOP_0
          + MESSAGE_DELTA_END_TURN
          + MESSAGE_STOP;

  private final ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void textDeltaEvents() {
    var events = drain(HELLO_TURN);

    assertEquals(2, events.size());
    assertEquals("Hello", assertInstanceOf(StreamEvent.TextDelta.class, events.get(0)).text());
    var done = assertInstanceOf(StreamEvent.Done.class, events.get(1));
    assertEquals("Hello", done.response().content());
    assertEquals(FinishReason.STOP, done.response().finishReason());
  }

  @Test
  void toolCallFromStreaming() {
    var toolBlockStart =
        named(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                + "\"name\":\"get_weather\",\"input\":{}}}");

    var toolDelta1 =
        named(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\"\"}}");

    var toolDelta2 =
        named(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\":\\\"NYC\\\"}\"}}");

    var contentBlockStop1 =
        named("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");

    var messageDeltaToolUse =
        named(
            "message_delta",
            "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":30}}");

    var events =
        drain(
            MESSAGE_START
                + toolBlockStart
                + toolDelta1
                + toolDelta2
                + contentBlockStop1
                + messageDeltaToolUse
                + MESSAGE_STOP);

    assertEquals(2, events.size());
    var tc = assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(0)).toolCall();
    assertEquals("get_weather", tc.name());
    assertEquals("toolu_1", tc.id());
    assertEquals(Map.of("city", "NYC"), tc.arguments());

    var done = (StreamEvent.Done) events.get(1);
    assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
    assertFalse(done.response().toolCalls().isEmpty());
  }

  @Test
  void thinkingEventCapturesContent() {
    var thinkingStart =
        named(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");

    var thinkingDelta =
        named(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me think...\"}}");

    var signatureDelta =
        named(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"EqoB123\"}}");

    var events =
        drain(
            MESSAGE_START
                + thinkingStart
                + thinkingDelta
                + signatureDelta
                + CONTENT_BLOCK_STOP_0
                + TEXT_BLOCK_START.replace("\"index\":0", "\"index\":1")
                + TEXT_DELTA.replace("\"index\":0", "\"index\":1")
                + named("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
                + MESSAGE_DELTA_END_TURN
                + MESSAGE_STOP);

    var done = (StreamEvent.Done) events.getLast();
    assertNotNull(done.response().thinking());
    assertTrue(done.response().thinking().contains("Let me think..."));

    var metadata = done.response().metadata();
    assertEquals(
        List.of(new ThinkingBlock("Let me think...", "EqoB123")),
        ThinkingBlock.decodeAll(metadata));
    assertFalse(metadata.containsKey("anthropic.thinking"));
    assertFalse(metadata.containsKey("anthropic.thinkingSignature"));

    // Streaming surface: each thinking_delta arrives as ThinkingDelta, and the closing
    // content_block_stop emits ThinkingComplete with the assembled text + signature.
    var thinkingDeltaEvent =
        events.stream()
            .filter(StreamEvent.ThinkingDelta.class::isInstance)
            .map(StreamEvent.ThinkingDelta.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("Let me think...", thinkingDeltaEvent.text());
    var thinkingComplete =
        events.stream()
            .filter(StreamEvent.ThinkingComplete.class::isInstance)
            .map(StreamEvent.ThinkingComplete.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("Let me think...", thinkingComplete.fullThinking());
    assertEquals("EqoB123", thinkingComplete.signature());
  }

  @Test
  void usageFromEvents() {
    var done = (StreamEvent.Done) drain(HELLO_TURN).getLast();

    assertNotNull(done.response().usage());
    assertEquals(25, done.response().usage().inputTokens());
    assertEquals(15, done.response().usage().outputTokens());
  }

  @Test
  void maxTokensStopReason() {
    var messageDelta =
        named(
            "message_delta",
            "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"max_tokens\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":4096}}");

    var done =
        (StreamEvent.Done)
            drain(
                    MESSAGE_START
                        + TEXT_BLOCK_START
                        + TEXT_DELTA
                        + CONTENT_BLOCK_STOP_0
                        + messageDelta
                        + MESSAGE_STOP)
                .getLast();

    assertEquals(FinishReason.LENGTH, done.response().finishReason());
  }

  @Test
  void emptyAndDoneDataLinesAreSkipped() {
    var events = drain("data: \n\ndata: [DONE]\n\n" + HELLO_TURN);

    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
  }

  @Test
  void nonDataLinesAreIgnored() {
    assertEquals(2, drain("event: ping\n\n" + HELLO_TURN).size());
  }

  @Test
  void malformedJsonEmitsErrorEvent() {
    var events = drain("data: {not valid json}\n\n" + HELLO_TURN);

    assertTrue(events.size() >= 2);
    assertInstanceOf(StreamEvent.Error.class, events.get(0));
  }

  @Test
  void idleTimeoutEmitsErrorEvent() {
    var neverDelivers = new FeedableInputStream();

    try (var iterator =
        new SseReader(
            neverDelivers,
            SHORT_IDLE_TIMEOUT,
            new AnthropicStreamParser(),
            AnthropicException::new)) {
      assertTrue(iterator.hasNext());
      var error = assertInstanceOf(StreamEvent.Error.class, iterator.next());
      assertTrue(error.message().contains("idle timeout"));
      assertTrue(assertInstanceOf(AnthropicException.class, error.cause()).isRetryable());
    }
  }

  @Test
  void closeIsIdempotent() {
    var iterator = reader(SseEvents.body(HELLO_TURN));
    iterator.close();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @Test
  void closeAfterPartialConsumption() {
    var iterator = reader(SseEvents.body(HELLO_TURN));
    assertTrue(iterator.hasNext());
    iterator.next();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @Test
  void multipleTextDeltas() {
    var delta2 =
        named(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\" World\"}}");

    var events =
        drain(
            MESSAGE_START
                + TEXT_BLOCK_START
                + TEXT_DELTA
                + delta2
                + CONTENT_BLOCK_STOP_0
                + MESSAGE_DELTA_END_TURN
                + MESSAGE_STOP);

    assertEquals(3, events.size());
    assertEquals("Hello World", ((StreamEvent.Done) events.getLast()).response().content());
  }

  @Test
  void emptyStreamProducesDoneWithEmptyContent() {
    var events = drain(MESSAGE_START + MESSAGE_DELTA_END_TURN + MESSAGE_STOP);

    assertEquals(1, events.size());
    var done = (StreamEvent.Done) events.getFirst();
    assertEquals("", done.response().content());
    assertEquals(FinishReason.STOP, done.response().finishReason());
  }

  @Test
  void noThinkingMetadataWhenNotPresent() {
    var done = (StreamEvent.Done) drain(HELLO_TURN).getLast();

    assertNull(done.response().thinking());
    assertFalse(done.response().metadata().containsKey(ThinkingBlock.THINKING_BLOCKS_KEY));
  }

  @Test
  void toolCallWithEmptyArgs() {
    var toolBlockStart =
        named(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                + "\"name\":\"list_items\",\"input\":{}}}");

    var messageDelta =
        named(
            "message_delta",
            "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"tool_use\"},"
                + "\"usage\":{\"output_tokens\":10}}");

    var events =
        drain(MESSAGE_START + toolBlockStart + CONTENT_BLOCK_STOP_0 + messageDelta + MESSAGE_STOP);

    assertEquals(2, events.size());
    var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
    assertEquals("list_items", tc.name());
    assertEquals(Map.of(), tc.arguments());
  }

  @Test
  void ioExceptionFromReaderEmitsErrorEvent() {
    var error = firstError(FailingInputStream.onRead(new IOException("Simulated I/O failure")));

    assertTrue(error.message().contains("Stream read error"));
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

  // ── thinking blocks: verbatim echo, omitted display, redaction ────────────

  private static String blockStart(int index, String contentBlock) {
    return SseEvents.data(
        "{\"type\":\"content_block_start\",\"index\":"
            + index
            + ",\"content_block\":"
            + contentBlock
            + "}");
  }

  private static String blockDelta(int index, String delta) {
    return SseEvents.data(
        "{\"type\":\"content_block_delta\",\"index\":" + index + ",\"delta\":" + delta + "}");
  }

  private static String blockStop(int index) {
    return SseEvents.data("{\"type\":\"content_block_stop\",\"index\":" + index + "}");
  }

  private static String thinkingBlock(int index, String text, String signature) {
    return blockStart(index, "{\"type\":\"thinking\",\"thinking\":\"\"}")
        + blockDelta(index, "{\"type\":\"thinking_delta\",\"thinking\":\"" + text + "\"}")
        + (signature.isEmpty()
            ? ""
            : blockDelta(
                index, "{\"type\":\"signature_delta\",\"signature\":\"" + signature + "\"}"))
        + blockStop(index);
  }

  private static String textBlock(int index, String text) {
    return blockStart(index, "{\"type\":\"text\",\"text\":\"\"}")
        + blockDelta(index, "{\"type\":\"text_delta\",\"text\":\"" + text + "\"}")
        + blockStop(index);
  }

  private static String toolUseBlock(int index, String id, String name) {
    return blockStart(
            index, "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\"}")
        + blockDelta(index, "{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}")
        + blockStop(index);
  }

  private static String messageDelta(String delta) {
    return SseEvents.data(
        "{\"type\":\"message_delta\",\"delta\":" + delta + ",\"usage\":{\"output_tokens\":15}}");
  }

  private Map<String, String> doneMetadata(String sse) {
    return ((StreamEvent.Done) drain(sse).getLast()).response().metadata();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> rawContent(Map<String, String> metadata) {
    return objectMapper.readValue(metadata.get(RawContentEcho.RAW_CONTENT_KEY), List.class);
  }

  @Test
  void thinkingInterleavedWithTextAndParallelToolCallsIsEchoedInStreamOrder() {
    var sse =
        MESSAGE_START
            + textBlock(0, "Checking two profiles.")
            + thinkingBlock(1, "Reading the first profile.", "SIG-1")
            + toolUseBlock(2, "toolu_1", "get_profile")
            + thinkingBlock(3, "Reading the second profile.", "SIG-2")
            + toolUseBlock(4, "toolu_2", "get_profile")
            + messageDelta("{\"stop_reason\":\"tool_use\"}")
            + MESSAGE_STOP;

    var blocks = rawContent(doneMetadata(sse));

    assertEquals(
        List.of("text", "thinking", "tool_use", "thinking", "tool_use"),
        blocks.stream().map(block -> block.get("type")).toList());
    assertEquals("SIG-1", blocks.get(1).get("signature"));
    assertEquals("Reading the first profile.", blocks.get(1).get("thinking"));
    assertEquals("toolu_1", blocks.get(2).get("id"));
    assertEquals("SIG-2", blocks.get(3).get("signature"));
    assertEquals("toolu_2", blocks.get(4).get("id"));
  }

  @Test
  void thinkingAfterAToolCallAloneRequiresTheVerbatimEcho() {
    var sse =
        MESSAGE_START
            + toolUseBlock(0, "toolu_1", "search_profiles")
            + thinkingBlock(1, "Now the second search.", "SIG-1")
            + toolUseBlock(2, "toolu_2", "search_profiles")
            + messageDelta("{\"stop_reason\":\"tool_use\"}")
            + MESSAGE_STOP;

    var blocks = rawContent(doneMetadata(sse));

    assertEquals(
        List.of("tool_use", "thinking", "tool_use"),
        blocks.stream().map(block -> block.get("type")).toList());
  }

  @Test
  void thinkingAheadOfTextAndToolCallsKeepsTheTypedEcho() {
    var sse =
        MESSAGE_START
            + thinkingBlock(0, "Reasoning.", "SIG-1")
            + thinkingBlock(1, "Searching next.", "SIG-2")
            + textBlock(2, "On it.")
            + toolUseBlock(3, "toolu_1", "search_profiles")
            + messageDelta("{\"stop_reason\":\"tool_use\"}")
            + MESSAGE_STOP;

    var metadata = doneMetadata(sse);

    assertNull(
        metadata.get(RawContentEcho.RAW_CONTENT_KEY),
        "the typed echo already reproduces thinking, text, tool_use order");
    assertEquals(2, ThinkingBlock.decodeAll(metadata).size());
  }

  @Test
  void blocksThatAreNeverEchoedDoNotForceTheVerbatimEcho() {
    var unsignedThinkingAfterText =
        MESSAGE_START
            + textBlock(0, "Answer.")
            + thinkingBlock(1, "unsigned", "")
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    var emptyTextBeforeThinking =
        MESSAGE_START
            + blockStart(0, "{\"type\":\"text\",\"text\":\"\"}")
            + blockStop(0)
            + thinkingBlock(1, "Reasoning.", "SIG-1")
            + textBlock(2, "Answer.")
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;

    assertNull(doneMetadata(unsignedThinkingAfterText).get(RawContentEcho.RAW_CONTENT_KEY));
    assertNull(doneMetadata(emptyTextBeforeThinking).get(RawContentEcho.RAW_CONTENT_KEY));
  }

  @Test
  void redactedThinkingIsCapturedVerbatimAndEchoedInPlace() {
    var sse =
        MESSAGE_START
            + blockStart(0, "{\"type\":\"redacted_thinking\",\"data\":\"ENCRYPTED-PAYLOAD\"}")
            + blockStop(0)
            + thinkingBlock(1, "Visible reasoning.", "SIG-1")
            + toolUseBlock(2, "toolu_1", "search_profiles")
            + messageDelta("{\"stop_reason\":\"tool_use\"}")
            + MESSAGE_STOP;

    var blocks = rawContent(doneMetadata(sse));

    assertEquals(
        Map.of("type", "redacted_thinking", "data", "ENCRYPTED-PAYLOAD"),
        blocks.get(0),
        "dropping or rewriting a redacted block breaks the multi-turn protocol");
    assertEquals("thinking", blocks.get(1).get("type"));
    assertEquals("tool_use", blocks.get(2).get("type"));
  }

  @Test
  void codeExecutionResultsFromWebSearchFilteringAreCapturedVerbatim() {
    var sse =
        MESSAGE_START
            + blockStart(
                0, "{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"code_execution\"}")
            + blockDelta(0, "{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}")
            + blockStop(0)
            + blockStart(
                1,
                "{\"type\":\"code_execution_tool_result\",\"tool_use_id\":\"srv_1\","
                    + "\"content\":{\"type\":\"code_execution_result\",\"stdout\":\"1839\"}}")
            + blockStop(1)
            + toolUseBlock(2, "toolu_1", "save_note")
            + messageDelta("{\"stop_reason\":\"tool_use\"}")
            + MESSAGE_STOP;

    var blocks = rawContent(doneMetadata(sse));

    assertEquals(
        List.of("server_tool_use", "code_execution_tool_result", "tool_use"),
        blocks.stream().map(block -> block.get("type")).toList(),
        "a server_tool_use echoed without its result block is a 400");
    assertEquals("srv_1", blocks.get(1).get("tool_use_id"));
    assertEquals(
        Map.of("type", "code_execution_result", "stdout", "1839"), blocks.get(1).get("content"));
  }

  @Test
  void anUnmodelledBlockTypeIsSkippedWithoutDisturbingTheTurn() {
    var sse =
        MESSAGE_START
            + blockStart(0, "{\"type\":\"mystery_block\",\"payload\":\"x\"}")
            + blockStop(0)
            + blockStart(1, "{\"payload\":\"typeless\"}")
            + blockStop(1)
            + textBlock(2, "Answer.")
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;

    var response = ((StreamEvent.Done) drain(sse).getLast()).response();

    assertEquals("Answer.", response.content());
    assertNull(response.metadata().get(RawContentEcho.RAW_CONTENT_KEY));
  }

  @Test
  void thinkingDeltaAheadOfItsBlockStartIsKept() {
    var sse =
        MESSAGE_START
            + blockDelta(0, "{\"type\":\"thinking_delta\",\"thinking\":\"Early. \"}")
            + thinkingBlock(0, "On time.", "SIG-1")
            + textBlock(1, "Answer.")
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;

    var response = ((StreamEvent.Done) drain(sse).getLast()).response();

    assertEquals("Early. On time.", response.thinking());
  }

  @Test
  void omittedDisplayThinkingKeepsItsSignatureAndEmitsNoThinkingEvents() {
    var sse =
        MESSAGE_START
            + thinkingBlock(0, "", "SIG-OMITTED")
            + textBlock(1, "Answer.")
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;

    var events = drain(sse);
    var response = ((StreamEvent.Done) events.getLast()).response();

    assertTrue(
        events.stream()
            .noneMatch(
                event ->
                    event instanceof StreamEvent.ThinkingDelta
                        || event instanceof StreamEvent.ThinkingComplete),
        "an empty thinking block carries nothing to render");
    assertNull(response.thinking());
    var thinkingBlocks = ThinkingBlock.decodeAll(response.metadata());
    assertEquals(1, thinkingBlocks.size());
    assertEquals("", thinkingBlocks.getFirst().text());
    assertEquals("SIG-OMITTED", thinkingBlocks.getFirst().signature());
  }

  // ── refusal stop details ──────────────────────────────────────────────────

  @Test
  void refusalStopDetailsSurfaceAsProviderNeutralMetadata() {
    var sse =
        MESSAGE_START
            + messageDelta(
                "{\"stop_reason\":\"refusal\",\"stop_sequence\":null,\"stop_details\":"
                    + "{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":"
                    + "\"This request was declined because it could enable cyber harm.\"}}")
            + MESSAGE_STOP;

    var done = (StreamEvent.Done) drain(sse).getLast();

    assertEquals(FinishReason.REFUSAL, done.response().finishReason());
    assertEquals("cyber", done.response().metadata().get(Response.REFUSAL_CATEGORY_KEY));
    assertEquals(
        "This request was declined because it could enable cyber harm.",
        done.response().metadata().get(Response.REFUSAL_EXPLANATION_KEY));
  }

  @Test
  void uncategorisedRefusalCarriesNoCategoryMetadata() {
    var sse =
        MESSAGE_START
            + messageDelta(
                "{\"stop_reason\":\"refusal\",\"stop_details\":"
                    + "{\"type\":\"refusal\",\"category\":null,\"explanation\":null}}")
            + MESSAGE_STOP;

    var metadata = doneMetadata(sse);

    assertEquals("refusal", metadata.get(AnthropicResponseAssembler.STOP_REASON_KEY));
    assertFalse(metadata.containsKey(Response.REFUSAL_CATEGORY_KEY));
    assertFalse(metadata.containsKey(Response.REFUSAL_EXPLANATION_KEY));
  }

  @Test
  void nonRefusalStopsCarryNoRefusalMetadata() {
    var sse =
        MESSAGE_START
            + textBlock(0, "Answer.")
            + messageDelta("{\"stop_reason\":\"end_turn\",\"stop_details\":null}")
            + MESSAGE_STOP;

    assertFalse(doneMetadata(sse).containsKey(Response.REFUSAL_CATEGORY_KEY));
  }

  // ── API errors reported mid-stream ────────────────────────────────────────

  private StreamEvent.Error streamError(String errorObject) {
    var sse = MESSAGE_START + named("error", "{\"type\":\"error\"" + errorObject + "}");
    return assertInstanceOf(StreamEvent.Error.class, drain(sse).getFirst());
  }

  @Test
  void retryableApiErrorsMidStreamAreTransient() {
    for (var type : List.of("overloaded_error", "api_error", "timeout_error", "rate_limit_error")) {
      var error = streamError(",\"error\":{\"type\":\"" + type + "\",\"message\":\"try again\"}");

      var cause = assertInstanceOf(TransientStreamException.class, error.cause(), type);
      assertEquals("anthropic", cause.providerName());
      assertTrue(error.message().startsWith("API stream error:"), error.message());
      assertTrue(cause.getMessage().contains(type), cause.getMessage());
    }
  }

  @Test
  void nonRetryableApiErrorsMidStreamStayTerminal() {
    for (var type : List.of("invalid_request_error", "authentication_error", "permission_error")) {
      var error = streamError(",\"error\":{\"type\":\"" + type + "\",\"message\":\"no\"}");

      assertNull(error.cause(), type);
      assertTrue(error.message().contains(type), error.message());
    }
  }

  @Test
  void anErrorEventWithoutAnErrorObjectStaysTerminal() {
    var missing = streamError("");
    var untyped = streamError(",\"error\":{\"message\":\"no type\"}");

    assertNull(missing.cause());
    assertNull(untyped.cause());
  }

  private static SseReader reader(InputStream body) {
    return new SseReader(
        body, SseEvents.NEVER_IDLE, new AnthropicStreamParser(), AnthropicException::new);
  }

  private static List<StreamEvent> drain(String sse) {
    return SseEvents.drain(reader(SseEvents.body(sse)));
  }

  private static StreamEvent.Error firstError(InputStream body) {
    return assertInstanceOf(StreamEvent.Error.class, SseEvents.drain(reader(body)).getFirst());
  }
}
