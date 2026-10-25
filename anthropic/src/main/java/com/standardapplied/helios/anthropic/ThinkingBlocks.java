/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.model.StreamEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The thinking blocks of a streamed turn, in arrival order. Each keeps its own signature: the API
 * rejects a signature fabricated across blocks. A delta may arrive before its block opens, so a
 * block is created by whichever comes first.
 */
final class ThinkingBlocks implements RawContentEcho.Source {

  /** What stands between the texts of two thinking blocks. */
  static final String SEPARATOR = "\n\n";

  private record Thinking(StringBuilder text, StringBuilder signature) {
    Thinking() {
      this(new StringBuilder(), new StringBuilder());
    }
  }

  private final LinkedHashMap<Integer, Thinking> blocks = new LinkedHashMap<>();

  /** A thinking block opening at {@code index}. */
  void start(int index) {
    blocks.putIfAbsent(index, new Thinking());
  }

  /**
   * Thinking streamed into the block at {@code index}, surfaced token by token so a live UI can
   * render it; an empty delta, the one a block streams under {@code display=omitted}, yields none.
   * A block's first text after another block's carries the {@link #SEPARATOR} {@link #text()} puts
   * between them, so the deltas add up to the thinking.
   */
  StreamEvent append(int index, String text) {
    if (text.isEmpty()) {
      return null;
    }
    var block = at(index).text();
    var separator = block.isEmpty() && text() != null ? SEPARATOR : "";
    block.append(text);
    return new StreamEvent.ThinkingDelta(separator + text);
  }

  /** Signature streamed into the block at {@code index}. */
  void sign(int index, String signature) {
    at(index).signature().append(signature);
  }

  /**
   * The text of every block, in order and separated by a blank line, or {@code null} when none has
   * any; a block keeps its text whether or not its signature arrived.
   */
  String text() {
    var text =
        blocks.values().stream()
            .map(thinking -> thinking.text().toString())
            .filter(value -> !value.isEmpty())
            .collect(Collectors.joining(SEPARATOR));
    return text.isEmpty() ? null : text;
  }

  /** The signed blocks, in order: a block without a signature cannot go back to the API. */
  List<ThinkingBlock> signed() {
    var signed = new ArrayList<ThinkingBlock>();
    for (var thinking : blocks.values()) {
      if (!thinking.signature().isEmpty()) {
        signed.add(new ThinkingBlock(thinking.text().toString(), thinking.signature().toString()));
      }
    }
    return signed;
  }

  /** The index of the last signed block, or {@code -1} when none is signed. */
  int lastSigned() {
    return blocks.entrySet().stream()
        .filter(block -> !block.getValue().signature().isEmpty())
        .mapToInt(Map.Entry::getKey)
        .max()
        .orElse(-1);
  }

  @Override
  public Set<Integer> indices() {
    return blocks.keySet();
  }

  @Override
  public Map<String, Object> echo(int index) {
    var thinking = blocks.get(index);
    if (thinking.signature().isEmpty()) {
      return null;
    }
    var block = new LinkedHashMap<String, Object>();
    block.put("type", "thinking");
    block.put("thinking", thinking.text().toString());
    block.put("signature", thinking.signature().toString());
    return block;
  }

  private Thinking at(int index) {
    return blocks.computeIfAbsent(index, i -> new Thinking());
  }
}
