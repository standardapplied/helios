/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The text blocks of a streamed turn: the turn's text, each block's text, and its citations. */
final class TextBlocks implements RawContentEcho.Source {

  private final StringBuilder content = new StringBuilder();
  private final TreeMap<Integer, StringBuilder> blocks = new TreeMap<>();
  private final TreeMap<Integer, List<Map<String, Object>>> blockCitations = new TreeMap<>();
  private final List<Citation> citations = new ArrayList<>();

  /** A text block opening at {@code index}; its opening text is not part of the turn's text. */
  void start(int index, String text) {
    blocks.put(index, new StringBuilder(text == null ? "" : text));
  }

  /** Text streamed into the block at {@code index}, which may be unknown. */
  StreamEvent append(Integer index, String text) {
    content.append(text);
    if (index != null) {
      blocks.computeIfAbsent(index, i -> new StringBuilder()).append(text);
    }
    return new StreamEvent.TextDelta(text);
  }

  /** A citation of the block at {@code index}; one naming a URL or cited text is harvested. */
  void cite(int index, Map<String, Object> citation) {
    blockCitations.computeIfAbsent(index, i -> new ArrayList<>()).add(citation);
    var url = citation.get("url");
    var citedText = citation.get("cited_text");
    if (url == null && citedText == null) {
      return;
    }
    var title = citation.get("title");
    citations.add(
        Citation.newBuilder()
            .withSourceId(url != null ? url.toString() : null)
            .withTitle(title != null ? title.toString() : null)
            .withContent(citedText != null ? citedText.toString() : null)
            .build());
  }

  /** The turn's text. */
  String content() {
    return content.toString();
  }

  /** The harvested citations, in arrival order. */
  List<Citation> citations() {
    return List.copyOf(citations);
  }

  /** The index of the first block holding text, or {@link Integer#MAX_VALUE} when none does. */
  int firstWritten() {
    return blocks.entrySet().stream()
        .filter(block -> !block.getValue().isEmpty())
        .mapToInt(Map.Entry::getKey)
        .min()
        .orElse(Integer.MAX_VALUE);
  }

  @Override
  public Set<Integer> indices() {
    return blocks.keySet();
  }

  @Override
  public Map<String, Object> echo(int index) {
    var text = blocks.get(index);
    if (text.isEmpty()) {
      return null;
    }
    var block = new LinkedHashMap<String, Object>();
    block.put("type", "text");
    block.put("text", text.toString());
    var cited = blockCitations.get(index);
    if (cited != null && !cited.isEmpty()) {
      block.put("citations", cited);
    }
    return block;
  }
}
