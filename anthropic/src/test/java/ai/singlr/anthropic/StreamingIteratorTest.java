/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.StreamEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class StreamingIteratorTest {

  private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofMillis(200);

  private static final String MESSAGE_START =
      "event: message_start\n"
          + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
          + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-6-20250514\","
          + "\"stop_reason\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}\n\n";

  private static final String TEXT_BLOCK_START =
      "event: content_block_start\n"
          + "data: {\"type\":\"content_block_start\",\"index\":0,"
          + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n";

  private static final String TEXT_DELTA =
      "event: content_block_delta\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":0,"
          + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n";

  private static final String CONTENT_BLOCK_STOP_0 =
      "event: content_block_stop\n" + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n";

  private static final String MESSAGE_DELTA_END_TURN =
      "event: message_delta\n"
          + "data: {\"type\":\"message_delta\","
          + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
          + "\"usage\":{\"output_tokens\":15}}\n\n";

  private static final String MESSAGE_STOP =
      "event: message_stop\n" + "data: {\"type\":\"message_stop\"}\n\n";

  private final tools.jackson.databind.ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @org.junit.jupiter.api.Test
  void textDeltaEvents() {
    var sse =
        MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
      assertEquals("Hello", ((StreamEvent.TextDelta) events.get(0)).text());
      assertInstanceOf(StreamEvent.Done.class, events.get(1));

      var done = (StreamEvent.Done) events.get(1);
      assertEquals("Hello", done.response().content());
      assertEquals(FinishReason.STOP, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void toolCallFromStreaming() {
    var toolBlockStart =
        "event: content_block_start\n"
            + "data: {\"type\":\"content_block_start\",\"index\":1,"
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\","
            + "\"name\":\"get_weather\",\"input\":{}}}\n\n";

    var toolDelta1 =
        "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":1,"
            + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\"\"}}\n\n";

    var toolDelta2 =
        "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":1,"
            + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\":\\\"NYC\\\"}\"}}\n\n";

    var contentBlockStop1 =
        "event: content_block_stop\n" + "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n";

    var messageDeltaToolUse =
        "event: message_delta\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},"
            + "\"usage\":{\"output_tokens\":30}}\n\n";

    var sse =
        MESSAGE_START
            + toolBlockStart
            + toolDelta1
            + toolDelta2
            + contentBlockStop1
            + messageDeltaToolUse
            + MESSAGE_STOP;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(0));
      var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
      assertEquals("get_weather", tc.name());
      assertEquals("toolu_1", tc.id());
      assertEquals(Map.of("city", "NYC"), tc.arguments());

      var done = (StreamEvent.Done) events.get(1);
      assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
      assertFalse(done.response().toolCalls().isEmpty());
    }
  }

  @org.junit.jupiter.api.Test
  void thinkingEventCapturesContent() {
    var thinkingStart =
        "event: content_block_start\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n";

    var thinkingDelta =
        "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me think...\"}}\n\n";

    var signatureDelta =
        "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"EqoB123\"}}\n\n";

    var sse =
        MESSAGE_START
            + thinkingStart
            + thinkingDelta
            + signatureDelta
            + CONTENT_BLOCK_STOP_0
            + TEXT_BLOCK_START.replace("\"index\":0", "\"index\":1")
            + TEXT_DELTA.replace("\"index\":0", "\"index\":1")
            + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }

      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().thinking());
      assertTrue(done.response().thinking().contains("Let me think..."));

      var metadata = done.response().metadata();
      assertTrue(metadata.containsKey(AnthropicModel.THINKING_KEY));
      assertTrue(metadata.containsKey(AnthropicModel.THINKING_SIGNATURE_KEY));
      assertEquals("EqoB123", metadata.get(AnthropicModel.THINKING_SIGNATURE_KEY));

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
  }

  @org.junit.jupiter.api.Test
  void usageFromEvents() {
    var sse =
        MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().usage());
      assertEquals(25, done.response().usage().inputTokens());
      assertEquals(15, done.response().usage().outputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void maxTokensStopReason() {
    var messageDelta =
        "event: message_delta\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"max_tokens\",\"stop_sequence\":null},"
            + "\"usage\":{\"output_tokens\":4096}}\n\n";

    var sse =
        MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + messageDelta
            + MESSAGE_STOP;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals(FinishReason.LENGTH, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void emptyAndDoneDataLinesAreSkipped() {
    var sse =
        "data: \n\ndata: [DONE]\n\n"
            + MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void nonDataLinesAreIgnored() {
    var sse =
        "event: ping\n\n"
            + MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void malformedJsonEmitsErrorEvent() {
    var sse =
        "data: {not valid json}\n\n"
            + MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertTrue(events.size() >= 2);
      assertInstanceOf(StreamEvent.Error.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void idleTimeoutEmitsErrorEvent() throws Exception {
    var pipedIn = new PipedInputStream();
    var pipedOut = new PipedOutputStream(pipedIn);

    try (var iterator =
        new AnthropicStreamingIterator(fakeResponse(pipedIn), objectMapper, SHORT_IDLE_TIMEOUT)) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("idle timeout"));
      assertInstanceOf(AnthropicException.class, error.cause());
      assertTrue(((AnthropicException) error.cause()).isRetryable());
    }
    pipedOut.close();
  }

  @org.junit.jupiter.api.Test
  void closeIsIdempotent() {
    var sse =
        MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    var iterator = createIterator(sse, Duration.ofSeconds(5));
    iterator.close();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void closeAfterPartialConsumption() {
    var sse =
        MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    var iterator = createIterator(sse, Duration.ofSeconds(5));
    assertTrue(iterator.hasNext());
    iterator.next();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void multipleTextDeltas() {
    var delta2 =
        "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\" World\"}}\n\n";

    var sse =
        MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + delta2
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(3, events.size());
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("Hello World", done.response().content());
    }
  }

  @org.junit.jupiter.api.Test
  void emptyStreamProducesDoneWithEmptyContent() {
    var sse = MESSAGE_START + MESSAGE_DELTA_END_TURN + MESSAGE_STOP;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(1, events.size());
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals("", done.response().content());
      assertEquals(FinishReason.STOP, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void noThinkingMetadataWhenNotPresent() {
    var sse =
        MESSAGE_START
            + TEXT_BLOCK_START
            + TEXT_DELTA
            + CONTENT_BLOCK_STOP_0
            + MESSAGE_DELTA_END_TURN
            + MESSAGE_STOP;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNull(done.response().thinking());
      assertFalse(done.response().metadata().containsKey(AnthropicModel.THINKING_KEY));
      assertFalse(done.response().metadata().containsKey(AnthropicModel.THINKING_SIGNATURE_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void toolCallWithEmptyArgs() {
    var toolBlockStart =
        "event: content_block_start\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\","
            + "\"name\":\"list_items\",\"input\":{}}}\n\n";

    var contentBlockStop =
        "event: content_block_stop\n" + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n";

    var messageDelta =
        "event: message_delta\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"tool_use\"},"
            + "\"usage\":{\"output_tokens\":10}}\n\n";

    var sse = MESSAGE_START + toolBlockStart + contentBlockStop + messageDelta + MESSAGE_STOP;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
      assertEquals("list_items", tc.name());
      assertEquals(Map.of(), tc.arguments());
    }
  }

  @org.junit.jupiter.api.Test
  void ioExceptionFromReaderEmitsErrorEvent() {
    var failingStream =
        new InputStream() {
          @Override
          public int read() throws java.io.IOException {
            throw new java.io.IOException("Simulated I/O failure");
          }
        };
    try (var iterator =
        new AnthropicStreamingIterator(
            fakeResponse(failingStream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("Stream read error"));
    }
  }

  @org.junit.jupiter.api.Test
  void runtimeExceptionFromReaderEmitsErrorEvent() {
    var failingStream =
        new InputStream() {
          @Override
          public int read() {
            throw new RuntimeException("Unexpected failure");
          }
        };
    try (var iterator =
        new AnthropicStreamingIterator(
            fakeResponse(failingStream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("Stream read error"));
    }
  }

  @org.junit.jupiter.api.Test
  void interruptedThreadEmitsErrorEvent() throws Exception {
    var pipedIn = new PipedInputStream();
    var pipedOut = new PipedOutputStream(pipedIn);
    var events = new ArrayList<StreamEvent>();
    var thread =
        new Thread(
            () -> {
              try (var iterator =
                  new AnthropicStreamingIterator(
                      fakeResponse(pipedIn), objectMapper, Duration.ofSeconds(30))) {
                while (iterator.hasNext()) {
                  events.add(iterator.next());
                }
              }
            });
    thread.start();
    Thread.sleep(100);
    thread.interrupt();
    thread.join(5000);
    assertFalse(events.isEmpty());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    pipedOut.close();
  }

  // ── thinking blocks: verbatim echo, omitted display, redaction ────────────

  private static String blockStart(int index, String contentBlock) {
    return "data: {\"type\":\"content_block_start\",\"index\":"
        + index
        + ",\"content_block\":"
        + contentBlock
        + "}\n\n";
  }

  private static String blockDelta(int index, String delta) {
    return "data: {\"type\":\"content_block_delta\",\"index\":"
        + index
        + ",\"delta\":"
        + delta
        + "}\n\n";
  }

  private static String blockStop(int index) {
    return "data: {\"type\":\"content_block_stop\",\"index\":" + index + "}\n\n";
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
    return "data: {\"type\":\"message_delta\",\"delta\":"
        + delta
        + ",\"usage\":{\"output_tokens\":15}}\n\n";
  }

  private java.util.List<StreamEvent> drain(String sse) {
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      return events;
    }
  }

  private Map<String, String> doneMetadata(String sse) {
    return ((StreamEvent.Done) drain(sse).getLast()).response().metadata();
  }

  @SuppressWarnings("unchecked")
  private java.util.List<Map<String, Object>> rawContent(Map<String, String> metadata) {
    return objectMapper.readValue(
        metadata.get(AnthropicModel.RAW_CONTENT_KEY), java.util.List.class);
  }

  @org.junit.jupiter.api.Test
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
        java.util.List.of("text", "thinking", "tool_use", "thinking", "tool_use"),
        blocks.stream().map(block -> block.get("type")).toList());
    assertEquals("SIG-1", blocks.get(1).get("signature"));
    assertEquals("Reading the first profile.", blocks.get(1).get("thinking"));
    assertEquals("toolu_1", blocks.get(2).get("id"));
    assertEquals("SIG-2", blocks.get(3).get("signature"));
    assertEquals("toolu_2", blocks.get(4).get("id"));
  }

  @org.junit.jupiter.api.Test
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
        java.util.List.of("tool_use", "thinking", "tool_use"),
        blocks.stream().map(block -> block.get("type")).toList());
  }

  @org.junit.jupiter.api.Test
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
        metadata.get(AnthropicModel.RAW_CONTENT_KEY),
        "the typed echo already reproduces thinking, text, tool_use order");
    assertEquals(2, AnthropicModel.decodeThinkingBlocks(metadata).size());
  }

  @org.junit.jupiter.api.Test
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

    assertNull(doneMetadata(unsignedThinkingAfterText).get(AnthropicModel.RAW_CONTENT_KEY));
    assertNull(doneMetadata(emptyTextBeforeThinking).get(AnthropicModel.RAW_CONTENT_KEY));
  }

  @org.junit.jupiter.api.Test
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

  @org.junit.jupiter.api.Test
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
        java.util.List.of("server_tool_use", "code_execution_tool_result", "tool_use"),
        blocks.stream().map(block -> block.get("type")).toList(),
        "a server_tool_use echoed without its result block is a 400");
    assertEquals("srv_1", blocks.get(1).get("tool_use_id"));
    assertEquals(
        Map.of("type", "code_execution_result", "stdout", "1839"), blocks.get(1).get("content"));
  }

  @org.junit.jupiter.api.Test
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
    assertNull(response.metadata().get(AnthropicModel.RAW_CONTENT_KEY));
  }

  @org.junit.jupiter.api.Test
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

  @org.junit.jupiter.api.Test
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
    var thinkingBlocks = AnthropicModel.decodeThinkingBlocks(response.metadata());
    assertEquals(1, thinkingBlocks.size());
    assertEquals("", thinkingBlocks.getFirst().text());
    assertEquals("SIG-OMITTED", thinkingBlocks.getFirst().signature());
  }

  // ── refusal stop details ──────────────────────────────────────────────────

  @org.junit.jupiter.api.Test
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
    assertEquals(
        "cyber",
        done.response().metadata().get(ai.singlr.core.model.Response.REFUSAL_CATEGORY_KEY));
    assertEquals(
        "This request was declined because it could enable cyber harm.",
        done.response().metadata().get(ai.singlr.core.model.Response.REFUSAL_EXPLANATION_KEY));
  }

  @org.junit.jupiter.api.Test
  void uncategorisedRefusalCarriesNoCategoryMetadata() {
    var sse =
        MESSAGE_START
            + messageDelta(
                "{\"stop_reason\":\"refusal\",\"stop_details\":"
                    + "{\"type\":\"refusal\",\"category\":null,\"explanation\":null}}")
            + MESSAGE_STOP;

    var metadata = doneMetadata(sse);

    assertEquals("refusal", metadata.get(AnthropicModel.STOP_REASON_KEY));
    assertFalse(metadata.containsKey(ai.singlr.core.model.Response.REFUSAL_CATEGORY_KEY));
    assertFalse(metadata.containsKey(ai.singlr.core.model.Response.REFUSAL_EXPLANATION_KEY));
  }

  @org.junit.jupiter.api.Test
  void nonRefusalStopsCarryNoRefusalMetadata() {
    var sse =
        MESSAGE_START
            + textBlock(0, "Answer.")
            + messageDelta("{\"stop_reason\":\"end_turn\",\"stop_details\":null}")
            + MESSAGE_STOP;

    assertFalse(doneMetadata(sse).containsKey(ai.singlr.core.model.Response.REFUSAL_CATEGORY_KEY));
  }

  // ── API errors reported mid-stream ────────────────────────────────────────

  private StreamEvent.Error streamError(String errorObject) {
    var sse = MESSAGE_START + "event: error\ndata: {\"type\":\"error\"" + errorObject + "}\n\n";
    return assertInstanceOf(StreamEvent.Error.class, drain(sse).getFirst());
  }

  @org.junit.jupiter.api.Test
  void retryableApiErrorsMidStreamAreTransient() {
    for (var type :
        java.util.List.of("overloaded_error", "api_error", "timeout_error", "rate_limit_error")) {
      var error = streamError(",\"error\":{\"type\":\"" + type + "\",\"message\":\"try again\"}");

      var cause =
          assertInstanceOf(
              ai.singlr.core.model.TransientStreamException.class, error.cause(), type);
      assertEquals("anthropic", cause.providerName());
      assertTrue(error.message().startsWith("API stream error:"), error.message());
      assertTrue(cause.getMessage().contains(type), cause.getMessage());
    }
  }

  @org.junit.jupiter.api.Test
  void nonRetryableApiErrorsMidStreamStayTerminal() {
    for (var type :
        java.util.List.of("invalid_request_error", "authentication_error", "permission_error")) {
      var error = streamError(",\"error\":{\"type\":\"" + type + "\",\"message\":\"no\"}");

      assertNull(error.cause(), type);
      assertTrue(error.message().contains(type), error.message());
    }
  }

  @org.junit.jupiter.api.Test
  void anErrorEventWithoutAnErrorObjectStaysTerminal() {
    var missing = streamError("");
    var untyped = streamError(",\"error\":{\"message\":\"no type\"}");

    assertNull(missing.cause());
    assertNull(untyped.cause());
  }

  private AnthropicStreamingIterator createIterator(String sseData, Duration idleTimeout) {
    var inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8));
    return new AnthropicStreamingIterator(fakeResponse(inputStream), objectMapper, idleTimeout);
  }

  private static HttpResponse<InputStream> fakeResponse(InputStream body) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 200;
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
      }

      @Override
      public InputStream body() {
        return body;
      }

      @Override
      public Optional<HttpResponse<InputStream>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpRequest request() {
        return null;
      }

      @Override
      public URI uri() {
        return URI.create("https://test");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_2;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }
    };
  }
}
