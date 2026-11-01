/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.anthropic.api.ContentDelta;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContentBlocksTest {

  private final ContentBlocks blocks = new ContentBlocks();

  @Test
  void aStartWithoutABlockOrAnIndexIsIgnored() {
    blocks.start(null, ContentBlock.text("x"), "{}");
    blocks.start(0, null, "{}");
    blocks.start(1, block("unknown_type"), "{}");
    blocks.start(2, block(null), "{}");

    assertTrue(blocks.text().indices().isEmpty());
    assertFalse(blocks.verbatim().seen());
  }

  @Test
  void textWithoutAnIndexJoinsTheTurnTextButNoBlock() {
    var event = blocks.delta(null, delta("text_delta", "loose", null, null, null, null));

    assertEquals(new StreamEvent.TextDelta("loose"), event);
    assertEquals("loose", blocks.text().content());
    assertTrue(blocks.text().indices().isEmpty());
  }

  @Test
  void deltasMissingTheirPayloadOrIndexYieldNothing() {
    assertNull(blocks.delta(0, delta("text_delta", null, null, null, null, null)));
    assertNull(blocks.delta(null, delta("input_json_delta", null, "{}", null, null, null)));
    assertNull(blocks.delta(0, delta("input_json_delta", null, null, null, null, null)));
    assertNull(
        blocks.delta(null, delta("citations_delta", null, null, null, null, Map.of("url", "u"))));
    assertNull(blocks.delta(0, delta("citations_delta", null, null, null, null, null)));
    assertNull(blocks.delta(null, delta("thinking_delta", null, null, "t", null, null)));
    assertNull(blocks.delta(0, delta("thinking_delta", null, null, null, null, null)));
    assertNull(blocks.delta(null, delta("signature_delta", null, null, null, "s", null)));
    assertNull(blocks.delta(0, delta("signature_delta", null, null, null, null, null)));
    assertNull(blocks.delta(0, delta("future_delta", "x", null, null, null, null)));
    assertNull(blocks.delta(0, delta(null, "x", null, null, null, null)));

    assertEquals("", blocks.text().content());
    assertTrue(blocks.thinking().indices().isEmpty());
    assertTrue(blocks.text().citations().isEmpty());
  }

  @Test
  void aServerToolUseOpeningWithoutInputClosesWithAnEmptyOne() {
    var payload =
        "{\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\"}}";
    blocks.start(0, block("server_tool_use"), payload);

    blocks.stop(0);

    assertEquals(Map.of(), blocks.verbatim().echo(0).get("input"));
  }

  @Test
  void aServerToolUseOpeningWithAnEmptyInputClosesWithTheInputItStreamed() {
    var payload =
        "{\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"s1\","
            + "\"name\":\"code_execution\",\"input\":{}}}";
    blocks.start(0, block("server_tool_use"), payload);
    blocks.delta(0, delta("input_json_delta", null, "{\"code\":\"pri", null, null, null));
    blocks.delta(0, delta("input_json_delta", null, "nt(1)\"}", null, null, null));

    blocks.stop(0);

    assertEquals(Map.of("code", "print(1)"), blocks.verbatim().echo(0).get("input"));
  }

  @Test
  void inputForABlockThatIsNotOpenIsDropped() {
    assertNull(blocks.delta(4, delta("input_json_delta", null, "{\"a\":1}", null, null, null)));
    assertNull(blocks.stop(4));
    assertNull(blocks.stop(null));

    assertTrue(blocks.toolUses().calls().isEmpty());
  }

  @Test
  void toolInputThatIsNotAJsonObjectFailsNamingTheTool() {
    blocks.start(
        0,
        new ContentBlock(
            "tool_use", null, "t1", "search", null, null, null, null, null, null, null),
        "{}");
    blocks.delta(0, delta("input_json_delta", null, "{not json", null, null, null));

    var thrown = assertThrows(AnthropicException.class, () -> blocks.stop(0));

    assertTrue(
        thrown.getMessage().startsWith("Arguments of tool call 'search' are not a JSON object: "));
    assertTrue(blocks.toolUses().calls().isEmpty());
  }

  @Test
  void aCitationNamingNeitherAUrlNorCitedTextIsKeptForTheEchoOnly() {
    blocks.start(0, ContentBlock.text(""), "{}");
    blocks.delta(0, delta("text_delta", "Cited.", null, null, null, null));
    blocks.delta(
        0, delta("citations_delta", null, null, null, null, Map.of("type", "char_location")));
    blocks.delta(
        0, delta("citations_delta", null, null, null, null, Map.of("cited_text", "only text")));

    assertEquals(1, blocks.text().citations().size());
    var citation = blocks.text().citations().getFirst();
    assertNull(citation.sourceId());
    assertNull(citation.title());
    assertEquals("only text", citation.content());
    assertEquals(
        List.of(Map.of("type", "char_location"), Map.of("cited_text", "only text")),
        blocks.text().echo(0).get("citations"));
  }

  @Test
  void textForABlockThatNeverOpenedStillJoinsTheEcho() {
    blocks.delta(3, delta("text_delta", "late", null, null, null, null));
    blocks.delta(3, delta("citations_delta", null, null, null, null, Map.of("url", "https://a")));

    assertEquals(
        Map.of("type", "text", "text", "late", "citations", List.of(Map.of("url", "https://a"))),
        blocks.text().echo(3));
    var citation = blocks.text().citations().getFirst();
    assertEquals("https://a", citation.sourceId());
    assertNull(citation.content());
  }

  @Test
  void anEmptyTextBlockAndAnUnsignedThinkingBlockAreLeftOutOfTheEcho() {
    blocks.start(0, block("thinking"), "{}");
    blocks.delta(0, delta("thinking_delta", null, null, "unsigned", null, null));
    blocks.start(1, ContentBlock.text(null), "{}");

    assertNull(blocks.thinking().echo(0));
    assertNull(blocks.text().echo(1));
    assertEquals("[]", RawContentEcho.assemble(blocks));
  }

  @Test
  void aWholeBlockThatCannotBeReadFailsInsteadOfLeavingAGapInTheEcho() {
    assertThrows(
        RuntimeException.class, () -> blocks.start(0, block("web_fetch_tool_result"), "{not json"));

    assertTrue(blocks.verbatim().indices().isEmpty());
  }

  private static ContentBlock block(String type) {
    return new ContentBlock(type, null, null, null, null, null, null, null, null, null, null);
  }

  private static ContentDelta delta(
      String type,
      String text,
      String partialJson,
      String thinking,
      String signature,
      Map<String, Object> citation) {
    return new ContentDelta(
        type, text, partialJson, thinking, signature, citation, null, null, null);
  }
}
