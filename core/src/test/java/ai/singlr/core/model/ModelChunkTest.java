/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Response.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModelChunkTest {

  @Test
  void textDeltaCarriesText() {
    var c = new ModelChunk.TextDelta("hello");
    assertEquals("hello", c.text());
  }

  @Test
  void textDeltaAllowsEmptyText() {
    assertEquals("", new ModelChunk.TextDelta("").text());
  }

  @Test
  void textDeltaRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> new ModelChunk.TextDelta(null));
    assertEquals("text must not be null", ex.getMessage());
  }

  @Test
  void thinkingDeltaCarriesText() {
    assertEquals("reasoning", new ModelChunk.ThinkingDelta("reasoning").text());
  }

  @Test
  void thinkingDeltaRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> new ModelChunk.ThinkingDelta(null));
    assertEquals("text must not be null", ex.getMessage());
  }

  @Test
  void toolUseStartCarriesFields() {
    var c = new ModelChunk.ToolUseStart("call-1", "read");
    assertEquals("call-1", c.callId());
    assertEquals("read", c.toolName());
  }

  @Test
  void toolUseStartRejectsNullCallId() {
    var ex = assertThrows(NullPointerException.class, () -> new ModelChunk.ToolUseStart(null, "x"));
    assertEquals("callId must not be null", ex.getMessage());
  }

  @Test
  void toolUseStartRejectsBlankCallId() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> new ModelChunk.ToolUseStart("  ", "x"));
    assertEquals("callId must not be blank", ex.getMessage());
  }

  @Test
  void toolUseStartRejectsNullToolName() {
    var ex = assertThrows(NullPointerException.class, () -> new ModelChunk.ToolUseStart("c", null));
    assertEquals("toolName must not be null", ex.getMessage());
  }

  @Test
  void toolUseStartRejectsBlankToolName() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> new ModelChunk.ToolUseStart("c", " "));
    assertEquals("toolName must not be blank", ex.getMessage());
  }

  @Test
  void toolUseDeltaCarriesFields() {
    var c = new ModelChunk.ToolUseDelta("call-1", "{\"a\":");
    assertEquals("call-1", c.callId());
    assertEquals("{\"a\":", c.argumentsDelta());
  }

  @Test
  void toolUseDeltaAllowsEmptyArguments() {
    assertEquals("", new ModelChunk.ToolUseDelta("c", "").argumentsDelta());
  }

  @Test
  void toolUseDeltaRejectsNullCallId() {
    var ex = assertThrows(NullPointerException.class, () -> new ModelChunk.ToolUseDelta(null, ""));
    assertEquals("callId must not be null", ex.getMessage());
  }

  @Test
  void toolUseDeltaRejectsBlankCallId() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> new ModelChunk.ToolUseDelta("", ""));
    assertEquals("callId must not be blank", ex.getMessage());
  }

  @Test
  void toolUseDeltaRejectsNullArgumentsDelta() {
    var ex = assertThrows(NullPointerException.class, () -> new ModelChunk.ToolUseDelta("c", null));
    assertEquals("argumentsDelta must not be null", ex.getMessage());
  }

  @Test
  void toolUseStopCarriesToolCall() {
    var call = new ToolCall("c", "read", Map.of("k", "v"));
    var c = new ModelChunk.ToolUseStop(call);
    assertEquals(call, c.toolCall());
  }

  @Test
  void toolUseStopRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> new ModelChunk.ToolUseStop(null));
    assertEquals("toolCall must not be null", ex.getMessage());
  }

  @Test
  void messageStopCarriesFields() {
    var u = Usage.of(10, 5);
    var c = new ModelChunk.MessageStop("end_turn", u);
    assertEquals("end_turn", c.stopReason());
    assertEquals(u, c.usage());
  }

  @Test
  void messageStopDefaultsCitationsToEmpty() {
    assertTrue(new ModelChunk.MessageStop("end_turn", Usage.of(0, 0)).citations().isEmpty());
    assertTrue(
        new ModelChunk.MessageStop("end_turn", Usage.of(0, 0), Map.of("k", "v"))
            .citations()
            .isEmpty());
  }

  @Test
  void messageStopCarriesCitations() {
    var cite = Citation.of("https://src", "snippet");
    var c = new ModelChunk.MessageStop("end_turn", Usage.of(1, 1), Map.of(), List.of(cite));
    assertEquals(List.of(cite), c.citations());
  }

  @Test
  void messageStopDefensivelyCopiesCitations() {
    var mutable = new ArrayList<Citation>();
    mutable.add(Citation.of("https://a", "x"));
    var c = new ModelChunk.MessageStop("end_turn", Usage.of(1, 1), Map.of(), mutable);
    mutable.add(Citation.of("https://b", "y"));
    assertEquals(1, c.citations().size());
    assertThrows(UnsupportedOperationException.class, () -> c.citations().add(mutable.getFirst()));
  }

  @Test
  void messageStopRejectsNullCitations() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ModelChunk.MessageStop("end_turn", Usage.of(0, 0), Map.of(), null));
    assertEquals("citations must not be null", ex.getMessage());
  }

  @Test
  void messageStopRejectsNullStopReason() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new ModelChunk.MessageStop(null, Usage.of(0, 0)));
    assertEquals("stopReason must not be null", ex.getMessage());
  }

  @Test
  void messageStopRejectsBlankStopReason() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ModelChunk.MessageStop("   ", Usage.of(0, 0)));
    assertEquals("stopReason must not be blank", ex.getMessage());
  }

  @Test
  void messageStopRejectsNullUsage() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new ModelChunk.MessageStop("end_turn", null));
    assertEquals("usage must not be null", ex.getMessage());
  }

  @Test
  void usageDeltaCarriesUsage() {
    var u = Usage.of(7, 3);
    assertEquals(u, new ModelChunk.UsageDelta(u).usage());
  }

  @Test
  void usageDeltaRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> new ModelChunk.UsageDelta(null));
    assertEquals("usage must not be null", ex.getMessage());
  }
}
