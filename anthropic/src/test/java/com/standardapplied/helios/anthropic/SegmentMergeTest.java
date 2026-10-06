/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.ConversationFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SegmentMergeTest {

  @Test
  void segmentsWithoutContentUsageOrCitationsMergeToEmpty() {
    var merged = SegmentMerge.merge(segment(null, null, null), segment(null, null, null));

    assertEquals("", merged.content());
    assertNull(merged.usage());
    assertEquals(List.of(), merged.citations());
    assertFalse(merged.metadata().containsKey(AnthropicModel.RAW_CONTENT_KEY));
  }

  @Test
  void usageAndCitationsComeFromWhicheverSegmentHasThem() {
    var usage = Response.Usage.of(5, 7);
    var citation = Citation.of("https://a", "a");

    var firstOnly =
        SegmentMerge.merge(segment("a", usage, List.of(citation)), segment("b", null, null));
    var secondOnly =
        SegmentMerge.merge(segment("a", null, null), segment("b", usage, List.of(citation)));

    assertSame(usage, firstOnly.usage());
    assertSame(usage, secondOnly.usage());
    assertEquals(List.of(citation), firstOnly.citations());
    assertEquals(List.of(citation), secondOnly.citations());
  }

  @Test
  void aSegmentWithoutVerbatimContentIsRebuiltForTheEcho() {
    var call =
        ToolCall.newBuilder()
            .withId("t1")
            .withName("search")
            .withArguments(Map.of("q", "x"))
            .build();
    var paused = segment("", null, null);
    var resumed =
        Response.newBuilder()
            .withContent("Searching.")
            .withToolCalls(List.of(call))
            .withFinishReason(FinishReason.STOP)
            .build();

    var merged = SegmentMerge.merge(paused, resumed);

    assertEquals(FinishReason.TOOL_CALLS, merged.finishReason());
    assertEquals(
        "[{\"type\":\"text\",\"text\":\"Searching.\"},"
            + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\",\"input\":{\"q\":\"x\"}}]",
        merged.metadata().get(AnthropicModel.RAW_CONTENT_KEY));
  }

  @Test
  void emptyVerbatimContentIsRebuiltFromTheSegment() {
    var empty =
        Response.newBuilder()
            .withContent("Hi")
            .withMetadata(Map.of(AnthropicModel.RAW_CONTENT_KEY, ""))
            .build();

    var merged = SegmentMerge.merge(empty, segment("", null, null));

    assertEquals(
        "[{\"type\":\"text\",\"text\":\"Hi\"}]",
        merged.metadata().get(AnthropicModel.RAW_CONTENT_KEY));
  }

  @Test
  void aRefusalInTheLastSegmentSurvivesItsToolCalls() {
    var call = ToolCall.newBuilder().withId("t1").withName("search").build();
    var refused =
        Response.newBuilder()
            .withToolCalls(List.of(call))
            .withFinishReason(FinishReason.REFUSAL)
            .build();

    assertEquals(
        FinishReason.REFUSAL, SegmentMerge.merge(segment("", null, null), refused).finishReason());
  }

  @Test
  void unreadableVerbatimContentFailsTheMerge() {
    var corrupted =
        Response.newBuilder()
            .withMetadata(Map.of(AnthropicModel.RAW_CONTENT_KEY, "{not json"))
            .build();

    var failure =
        assertThrows(
            AnthropicException.class, () -> SegmentMerge.merge(corrupted, segment("", null, null)));

    assertEquals("Failed to decode segment content array", failure.getMessage());
  }

  @Test
  void toolArgumentsThatCannotBeWrittenFailTheMerge() {
    var call =
        ToolCall.newBuilder()
            .withId("t1")
            .withName("search")
            .withArguments(ConversationFixture.selfReferencing())
            .build();
    var unwritable = Response.newBuilder().withToolCalls(List.of(call)).build();

    var failure =
        assertThrows(
            AnthropicException.class,
            () -> SegmentMerge.merge(unwritable, segment("", null, null)));

    assertEquals("Failed to merge paused-turn content arrays", failure.getMessage());
  }

  private static Response<Void> segment(
      String content, Response.Usage usage, List<Citation> citations) {
    return new Response<>(
        content, null, List.of(), FinishReason.STOP, usage, null, citations, Map.of());
  }
}
