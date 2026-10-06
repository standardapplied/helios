/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The thinking blocks of a streamed turn, in arrival order. Each keeps its own signature: the API
 * rejects a signature fabricated across blocks. A delta may arrive before its block opens, so a
 * block is created by whichever comes first.
 */
final class ThinkingBlocks implements RawContentEcho.Source {

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
   */
  StreamEvent append(int index, String text) {
    at(index).text().append(text);
    return text.isEmpty() ? null : new StreamEvent.ThinkingDelta(text);
  }

  /** Signature streamed into the block at {@code index}. */
  void sign(int index, String signature) {
    at(index).signature().append(signature);
  }

  /** Whether a thinking block is at {@code index}. */
  boolean holds(int index) {
    return blocks.containsKey(index);
  }

  /** The closing of the block at {@code index}: its whole text and signature, unless blank. */
  StreamEvent complete(int index) {
    var thinking = blocks.get(index);
    var text = thinking.text().toString();
    if (Strings.isBlank(text)) {
      return null;
    }
    var signature = thinking.signature().isEmpty() ? null : thinking.signature().toString();
    return new StreamEvent.ThinkingComplete(text, signature);
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
