/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One thinking content block — text plus its content-block-scoped Anthropic signature — and the
 * codec of the {@link #THINKING_BLOCKS_KEY} metadata that carries a turn's blocks.
 */
record ThinkingBlock(String text, String signature) {

  /**
   * Metadata key carrying every thinking block in the message as a JSON array of {@code
   * [{"text":"…","signature":"…"}, …]}, one entry per block whatever their number. Each block keeps
   * its own signature: the Anthropic API rejects a signature fabricated across blocks.
   */
  static final String THINKING_BLOCKS_KEY = "anthropic.thinkingBlocks";

  /**
   * Every thinking block recorded in {@code metadata}, in order. Empty when the key is absent or
   * empty.
   *
   * @throws AnthropicException when the value is not a JSON array of objects, or an entry has no
   *     non-empty string {@code signature} or a {@code text} that is not a string: sending the turn
   *     without its blocks would alter the conversation the API signed
   */
  static List<ThinkingBlock> decodeAll(Map<String, String> metadata) {
    var encoded = metadata == null ? null : metadata.get(THINKING_BLOCKS_KEY);
    if (encoded == null || encoded.isEmpty()) {
      return List.of();
    }
    var blocks = new ArrayList<ThinkingBlock>();
    for (var entry : entries(encoded)) {
      blocks.add(decode(entry));
    }
    return List.copyOf(blocks);
  }

  private static List<?> entries(String encoded) {
    try {
      return AnthropicJson.DEFAULT.readValue(encoded, List.class);
    } catch (RuntimeException e) {
      throw new AnthropicException(unreadable("is not a JSON array"), e);
    }
  }

  private static ThinkingBlock decode(Object entry) {
    if (!(entry instanceof Map<?, ?> fields)) {
      throw new AnthropicException(unreadable("holds an entry that is not a JSON object"));
    }
    if (!(fields.get("signature") instanceof String signature) || signature.isEmpty()) {
      throw new AnthropicException(unreadable("holds a block without a signature"));
    }
    return switch (fields.get("text")) {
      case null -> new ThinkingBlock("", signature);
      case String text -> new ThinkingBlock(text, signature);
      default ->
          throw new AnthropicException(unreadable("holds a block whose text is not a string"));
    };
  }

  private static String unreadable(String defect) {
    return "Unreadable "
        + THINKING_BLOCKS_KEY
        + " on assistant message: the value "
        + defect
        + "; refusing to send the turn without its thinking blocks";
  }

  /** The {@link #THINKING_BLOCKS_KEY} value carrying {@code blocks}, in order. */
  static String encodeAll(List<ThinkingBlock> blocks) {
    var entries = new ArrayList<Map<String, String>>(blocks.size());
    for (var block : blocks) {
      entries.add(Map.of("text", block.text(), "signature", block.signature()));
    }
    return AnthropicJson.LENIENT.writeValueAsString(entries);
  }
}
