/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseTest {

  @Test
  void buildResponse() {
    var response =
        Response.newBuilder().withContent("Hello!").withFinishReason(FinishReason.STOP).build();

    assertEquals("Hello!", response.content());
    assertEquals(FinishReason.STOP, response.finishReason());
    assertTrue(response.toolCalls().isEmpty());
    assertNull(response.usage());
  }

  @Test
  void buildResponseWithToolCalls() {
    var toolCall =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("search")
            .withArguments(Map.of("query", "test"))
            .build();
    var response =
        Response.newBuilder()
            .withToolCalls(List.of(toolCall))
            .withFinishReason(FinishReason.TOOL_CALLS)
            .build();

    assertTrue(response.hasToolCalls());
    assertEquals(1, response.toolCalls().size());
    assertEquals("search", response.toolCalls().getFirst().name());
  }

  @Test
  void hasToolCallsFalseWhenEmpty() {
    var response = Response.newBuilder().withContent("No tools").build();

    assertFalse(response.hasToolCalls());
  }

  @Test
  void hasToolCallsFalseWhenNull() {
    var response = Response.newBuilder().withToolCalls(null).withContent("No tools").build();

    assertFalse(response.hasToolCalls());
    assertTrue(response.toolCalls().isEmpty());
  }

  @Test
  void toMessage() {
    var response =
        Response.newBuilder()
            .withContent("Response content")
            .withFinishReason(FinishReason.STOP)
            .build();

    var message = response.toMessage();

    assertEquals(Role.ASSISTANT, message.role());
    assertEquals("Response content", message.content());
  }

  @Test
  void toMessageWithToolCalls() {
    var toolCall = ToolCall.newBuilder().withId("call_1").withName("test").build();
    var response =
        Response.newBuilder().withContent("Calling tool").withToolCalls(List.of(toolCall)).build();

    var message = response.toMessage();

    assertEquals(Role.ASSISTANT, message.role());
    assertTrue(message.hasToolCalls());
  }

  @Test
  void usageOf() {
    var usage = Response.Usage.of(100, 50);

    assertEquals(100, usage.inputTokens());
    assertEquals(50, usage.outputTokens());
    assertEquals(0, usage.cacheCreationInputTokens());
    assertEquals(0, usage.cacheReadInputTokens());
    assertEquals(150, usage.totalTokens());
  }

  @Test
  void usageOfWithCacheTokens() {
    var usage = Response.Usage.of(100, 50, 200, 5000);

    assertEquals(100, usage.inputTokens());
    assertEquals(50, usage.outputTokens());
    assertEquals(200, usage.cacheCreationInputTokens());
    assertEquals(5000, usage.cacheReadInputTokens());
    assertEquals(
        100 + 50 + 200 + 5000, usage.totalTokens(), "totalTokens sums every billable token class");
  }

  @Test
  void usagePlusSumsClassWise() {
    var sum = Response.Usage.of(100, 50, 20, 10).plus(Response.Usage.of(1, 2, 3, 4));

    assertEquals(Response.Usage.of(101, 52, 23, 14), sum);
  }

  @Test
  void usagePlusPreservesExplicitTotal() {
    var explicit = new Response.Usage(2, 3, 0, 0, 99);

    var sum = explicit.plus(Response.Usage.of(1, 1));

    assertEquals(101, sum.totalTokens(), "provider-reported total carries through accumulation");
  }

  @Test
  void usagePlusRejectsOverflow() {
    var huge = Response.Usage.of(Integer.MAX_VALUE - 1, 0);

    assertThrows(ArithmeticException.class, () -> huge.plus(Response.Usage.of(2, 0)));
  }

  @Test
  void usageOfWithCacheTokensRejectsOverflow() {
    // input + output + cacheCreation + cacheRead would exceed Integer.MAX_VALUE.
    var huge = Integer.MAX_VALUE - 10;
    assertThrows(ArithmeticException.class, () -> Response.Usage.of(huge, huge, huge, huge));
  }

  @Test
  void buildWithUsage() {
    var usage = Response.Usage.of(200, 100);
    var response = Response.newBuilder().withContent("Test").withUsage(usage).build();

    assertEquals(usage, response.usage());
    assertEquals(200, response.usage().inputTokens());
    assertEquals(100, response.usage().outputTokens());
    assertEquals(300, response.usage().totalTokens());
  }

  @Test
  void allFinishReasons() {
    assertEquals(FinishReason.STOP, FinishReason.valueOf("STOP"));
    assertEquals(FinishReason.TOOL_CALLS, FinishReason.valueOf("TOOL_CALLS"));
    assertEquals(FinishReason.LENGTH, FinishReason.valueOf("LENGTH"));
    assertEquals(FinishReason.CONTENT_FILTER, FinishReason.valueOf("CONTENT_FILTER"));
    assertEquals(FinishReason.ERROR, FinishReason.valueOf("ERROR"));
    assertEquals(5, FinishReason.values().length);
  }

  @Test
  void hasToolCallsWithNullToolCallsDirect() {
    var response = new Response<>("content", null, null, FinishReason.STOP, null, null, null, null);

    assertFalse(response.hasToolCalls());
  }

  @Test
  void buildResponseWithThinking() {
    var response =
        Response.newBuilder()
            .withContent("Answer")
            .withThinking("Let me think about this...")
            .withFinishReason(FinishReason.STOP)
            .build();

    assertTrue(response.hasThinking());
    assertEquals("Let me think about this...", response.thinking());
  }

  @Test
  void hasThinkingFalseWhenNull() {
    var response = Response.newBuilder().withContent("No thinking").build();

    assertFalse(response.hasThinking());
  }

  @Test
  void hasThinkingFalseWhenEmpty() {
    var response = Response.newBuilder().withContent("Empty thinking").withThinking("").build();

    assertFalse(response.hasThinking());
  }

  @Test
  void buildResponseWithCitations() {
    var citation = Citation.of("doc-1", "Source content");
    var response =
        Response.newBuilder()
            .withContent("Based on sources...")
            .withCitations(List.of(citation))
            .withFinishReason(FinishReason.STOP)
            .build();

    assertTrue(response.hasCitations());
    assertEquals(1, response.citations().size());
    assertEquals("doc-1", response.citations().getFirst().sourceId());
  }

  @Test
  void hasCitationsFalseWhenNull() {
    var response = Response.newBuilder().withContent("No citations").withCitations(null).build();

    assertFalse(response.hasCitations());
    assertTrue(response.citations().isEmpty());
  }

  @Test
  void hasCitationsFalseWhenEmpty() {
    var response = Response.newBuilder().withContent("No citations").build();

    assertFalse(response.hasCitations());
  }

  @Test
  void hasCitationsWithNullCitationsDirect() {
    var response =
        new Response<>("content", null, List.of(), FinishReason.STOP, null, null, null, null);

    assertFalse(response.hasCitations());
  }

  @Test
  void hasCitationsWithEmptyCitationsDirect() {
    var response =
        new Response<>("content", null, List.of(), FinishReason.STOP, null, null, List.of(), null);

    assertFalse(response.hasCitations());
  }

  @Test
  void builderWithMetadata() {
    var metadata = Map.of("request_id", "req-123");
    var response = Response.newBuilder().withContent("Hello").withMetadata(metadata).build();

    assertEquals("req-123", response.metadata().get("request_id"));
  }

  @Test
  void builderWithNullMetadataDefaultsToEmpty() {
    var response = Response.newBuilder().withContent("Hello").withMetadata(null).build();

    assertTrue(response.metadata().isEmpty());
  }
}
