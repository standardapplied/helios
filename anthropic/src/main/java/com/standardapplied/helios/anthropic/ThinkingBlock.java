/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One thinking content block — text plus its content-block-scoped Anthropic signature — and the
 * codec of the {@link AnthropicModel#THINKING_BLOCKS_KEY} metadata that carries a turn's blocks.
 */
record ThinkingBlock(String text, String signature) {

  /**
   * Every thinking block recorded in {@code metadata}. Empty when the key is absent or unreadable,
   * or no entry carries a signature.
   */
  static List<ThinkingBlock> decodeAll(Map<String, String> metadata) {
    if (metadata == null) {
      return List.of();
    }
    var encoded = metadata.get(AnthropicModel.THINKING_BLOCKS_KEY);
    if (encoded == null || encoded.isEmpty()) {
      return List.of();
    }
    try {
      @SuppressWarnings("unchecked")
      var raw = (List<Map<String, Object>>) AnthropicJson.DEFAULT.readValue(encoded, List.class);
      var out = new ArrayList<ThinkingBlock>(raw.size());
      for (var entry : raw) {
        var signature = textOf(entry.get("signature"));
        if (!signature.isEmpty()) {
          out.add(new ThinkingBlock(textOf(entry.get("text")), signature));
        }
      }
      return out;
    } catch (RuntimeException unreadable) {
      return List.of();
    }
  }

  /** The {@link AnthropicModel#THINKING_BLOCKS_KEY} value carrying {@code blocks}, in order. */
  static String encodeAll(List<ThinkingBlock> blocks) {
    var entries = new ArrayList<Map<String, String>>(blocks.size());
    for (var block : blocks) {
      entries.add(Map.of("text", block.text(), "signature", block.signature()));
    }
    return AnthropicJson.LENIENT.writeValueAsString(entries);
  }

  private static String textOf(Object value) {
    return value == null ? "" : value.toString();
  }
}
