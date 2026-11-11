/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    assertEquals(100L, usage.promptTokens());
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
  void promptTokensSumEveryInputClassAndNotTheOutput() {
    assertEquals(5_500L, Response.Usage.of(1_000, 7, 500, 4_000).promptTokens());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void usageRejectsANegativeCountInAnyPosition(int position) {
    var counts = new int[] {1, 2, 3, 4};
    counts[position] = -1;
    var total = counts[0] + counts[1] + counts[2] + counts[3];

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Response.Usage(counts[0], counts[1], counts[2], counts[3], total, 0, 0));
    assertTrue(ex.getMessage().startsWith("token counts must be non-negative, got ["));
  }

  @Test
  void usageRejectsCountsWhoseSumOverflowsAnInt() {
    var quarter = Integer.MAX_VALUE / 2 + 1;

    assertThrows(
        ArithmeticException.class,
        () -> new Response.Usage(quarter, quarter, quarter, quarter, Integer.MAX_VALUE, 0, 0));
  }

  @Test
  void usageRejectsATotalThatIsNotTheSumOfItsCounts() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new Response.Usage(1, 2, 3, 4, 11, 8, 0));
    assertEquals("totalTokens must be the sum of the four counts, 10, got 11", ex.getMessage());
  }

  @Test
  void usageAcceptsATotalThatIsTheSumOfItsCounts() {
    assertEquals(Response.Usage.of(1, 2, 3, 4), new Response.Usage(1, 2, 3, 4, 10, 8, 0));
    assertEquals(10, Response.Usage.of(1, 2, 3, 4).totalTokens());
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 9})
  void usageRejectsAPromptSizeOutsideTheInputCounts(long promptTokens) {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Response.Usage(1, 2, 3, 4, 10, promptTokens, 0));
    assertEquals("promptTokens must lie in [0, 8], got " + promptTokens, ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 8})
  void usageAcceptsAPromptSizeWithinTheInputCounts(long promptTokens) {
    assertEquals(promptTokens, new Response.Usage(1, 2, 3, 4, 10, promptTokens, 0).promptTokens());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 3})
  void usageRejectsAReasoningCountOutsideTheOutputCount(int reasoningTokens) {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Response.Usage(1, 2, 3, 4, 10, 8, reasoningTokens));
    assertEquals(
        "reasoningTokens must lie in [0, outputTokens 2], got " + reasoningTokens, ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void usageAcceptsAReasoningCountWithinTheOutputCount(int reasoningTokens) {
    assertEquals(
        reasoningTokens, new Response.Usage(1, 2, 3, 4, 10, 8, reasoningTokens).reasoningTokens());
  }

  @Test
  void usageOfWithoutAReasoningCountReportsNone() {
    assertEquals(0, Response.Usage.of(10, 300).reasoningTokens());
    assertEquals(0, Response.Usage.of(10, 300, 20, 30).reasoningTokens());
  }

  @Test
  void usageOfWithAReasoningCountIsOneRequestWhoseTotalsIgnoreIt() {
    var usage = Response.Usage.of(10, 300, 20, 30, 120);

    assertEquals(new Response.Usage(10, 300, 20, 30, 360, 60, 120), usage);
    assertEquals(Response.Usage.of(10, 300, 20, 30).totalTokens(), usage.totalTokens());
    assertEquals(Response.Usage.of(10, 300, 20, 30).promptTokens(), usage.promptTokens());
  }

  @Test
  void pricingCostsAUsageReportingReasoningAsTheSameUsageWithout() {
    var pricing = new CostCalculator.Pricing(3_000_000L, 15_000_000L, 3_750_000L, 300_000L);

    assertEquals(
        pricing.cost(Response.Usage.of(10, 300, 20, 30)),
        pricing.cost(Response.Usage.of(10, 300, 20, 30, 120)));
  }

  @Test
  void usagePlusSumsTheReasoningCounts() {
    var sum = Response.Usage.of(10, 300, 0, 0, 120).plus(Response.Usage.of(5, 40, 0, 0, 25));

    assertEquals(new Response.Usage(15, 340, 0, 0, 355, 5, 145), sum);
  }

  @Test
  void usagePlusSumsClassWise() {
    var sum = Response.Usage.of(100, 50, 20, 10).plus(Response.Usage.of(1, 2, 3, 4));

    assertEquals(new Response.Usage(101, 52, 23, 14, 190, 8, 0), sum);
  }

  @Test
  void usagePlusKeepsTheLaterRequestsPromptSize() {
    var segments = Response.Usage.of(100_000, 10).plus(Response.Usage.of(110_000, 20));

    assertEquals(210_000, segments.inputTokens());
    assertEquals(110_000L, segments.promptTokens());
  }

  @Test
  void usagePlusKeepsTheEarlierPromptSizeWhenTheLaterReportsNone() {
    var usage = Response.Usage.of(1_000, 7, 500, 4_000);

    assertEquals(usage, usage.plus(Response.Usage.of(0, 0)));
  }

  @Test
  void usagePlusTakesTheLaterPromptSizeOverAZeroOne() {
    assertEquals(Response.Usage.of(5, 1), Response.Usage.of(0, 0).plus(Response.Usage.of(5, 1)));
  }

  @Test
  void usagePlusRecomputesTheTotal() {
    var sum = Response.Usage.of(1, 2, 3, 4).plus(Response.Usage.of(10, 20, 30, 40));

    assertEquals(110, sum.totalTokens());
  }

  @Test
  void usagePlusRejectsOverflow() {
    var huge = Response.Usage.of(Integer.MAX_VALUE - 1, 0);

    assertThrows(ArithmeticException.class, () -> huge.plus(Response.Usage.of(2, 0)));
  }

  @Test
  void usageReportsTokensWhenAnyBillableCountIsPositive() {
    assertFalse(Response.Usage.of(0, 0).reportsTokens());
    assertTrue(Response.Usage.of(1, 0).reportsTokens());
    assertTrue(Response.Usage.of(0, 1).reportsTokens());
    assertTrue(Response.Usage.of(0, 0, 1, 0).reportsTokens());
    assertTrue(Response.Usage.of(0, 0, 0, 1).reportsTokens());
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
    assertEquals(FinishReason.REFUSAL, FinishReason.valueOf("REFUSAL"));
    assertEquals(FinishReason.ERROR, FinishReason.valueOf("ERROR"));
    assertEquals(6, FinishReason.values().length);
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
