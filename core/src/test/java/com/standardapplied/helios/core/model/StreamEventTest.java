/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamEventTest {

  @Test
  void textDelta() {
    var event = new StreamEvent.TextDelta("Hello");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("Hello", event.text());
  }

  @Test
  void toolCallStart() {
    var event = new StreamEvent.ToolCallStart("call_123", "search");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("call_123", event.callId());
    assertEquals("search", event.toolName());
  }

  @Test
  void toolCallDelta() {
    var event = new StreamEvent.ToolCallDelta("call_123", "{\"query\":");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("call_123", event.callId());
    assertEquals("{\"query\":", event.argumentsDelta());
  }

  @Test
  void toolCallComplete() {
    var toolCall =
        ToolCall.newBuilder()
            .withId("call_123")
            .withName("search")
            .withArguments(Map.of("query", "test"))
            .build();
    var event = new StreamEvent.ToolCallComplete(toolCall);

    assertInstanceOf(StreamEvent.class, event);
    assertEquals(toolCall, event.toolCall());
  }

  @Test
  void done() {
    var response =
        Response.newBuilder().withContent("Done!").withFinishReason(FinishReason.STOP).build();
    var event = new StreamEvent.Done(response);

    assertInstanceOf(StreamEvent.class, event);
    assertEquals(response, event.response());
  }

  @Test
  void errorWithCause() {
    var cause = new RuntimeException("Network error");
    var event = new StreamEvent.Error("Connection failed", cause);

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("Connection failed", event.message());
    assertEquals(cause, event.cause());
  }

  @Test
  void errorWithoutCause() {
    var event = new StreamEvent.Error("Timeout occurred");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("Timeout occurred", event.message());
    assertNull(event.cause());
  }

  @Test
  void thinkingDelta() {
    var event = new StreamEvent.ThinkingDelta("Let me reason about this");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("Let me reason about this", event.text());
  }

  @Test
  void thinkingCompleteWithSignature() {
    var event = new StreamEvent.ThinkingComplete("full thinking text", "sig_abc123");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("full thinking text", event.fullThinking());
    assertEquals("sig_abc123", event.signature());
  }

  @Test
  void thinkingCompleteWithoutSignature() {
    var event = new StreamEvent.ThinkingComplete("full thinking text");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("full thinking text", event.fullThinking());
    assertNull(event.signature());
  }

  @Test
  void patternMatchingOnEvents() {
    StreamEvent textEvent = new StreamEvent.TextDelta("text");
    StreamEvent errorEvent = new StreamEvent.Error("error");
    StreamEvent thinkingEvent = new StreamEvent.ThinkingDelta("thinking");

    var textResult = classify(textEvent);
    var errorResult = classify(errorEvent);
    var thinkingResult = classify(thinkingEvent);

    assertEquals("text: text", textResult);
    assertEquals("error: error", errorResult);
    assertEquals("thinking-delta: thinking", thinkingResult);
  }

  private static String classify(StreamEvent event) {
    return switch (event) {
      case StreamEvent.TextDelta(var text) -> "text: " + text;
      case StreamEvent.ThinkingDelta(var text) -> "thinking-delta: " + text;
      case StreamEvent.ThinkingComplete(var full, var sig) -> "thinking-complete";
      case StreamEvent.ToolCallStart(var id, var name) -> "start";
      case StreamEvent.ToolCallDelta(var id, var delta) -> "delta";
      case StreamEvent.ToolCallComplete(var tc) -> "complete";
      case StreamEvent.Done(var r) -> "done";
      case StreamEvent.Error(var msg, var cause) -> "error: " + msg;
    };
  }
}
