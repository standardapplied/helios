/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The verbatim echo of a streamed assistant turn: its content-block array in stream-index order,
 * for the {@link #RAW_CONTENT_KEY} metadata. A turn needs it when it must go back exactly as it
 * arrived — it held a verbatim block, interleaved thinking with other content, or paused — since
 * the API rejects such a turn echoed in the typed shape.
 */
final class RawContentEcho {

  /**
   * Metadata key carrying the assistant turn's full content-block array as raw JSON, set whenever
   * the turn must go back exactly as it arrived: it used Anthropic server tools (web search / web
   * fetch), held a {@code redacted_thinking} block, or interleaved thinking with text or tool
   * calls. Those blocks — including each result's {@code encrypted_content} and each thinking
   * block's position — must be echoed back <b>verbatim</b> on later turns or the API rejects the
   * request with a 400; a later request replays this array as the message content when present.
   */
  static final String RAW_CONTENT_KEY = "anthropic.rawContent";

  /** One kind of content block a turn accumulates, by stream index. */
  interface Source {

    /** The stream indices holding a block of this kind. */
    Set<Integer> indices();

    /** The block at {@code index} as it is echoed, or {@code null} when it is left out. */
    Map<String, Object> echo(int index);
  }

  private RawContentEcho() {}

  /** Whether the turn in {@code blocks}, stopped for {@code stopReason}, needs the echo. */
  static boolean needed(ContentBlocks blocks, String stopReason) {
    return blocks.verbatim().seen() || interleaved(blocks) || "pause_turn".equals(stopReason);
  }

  /** The content-block array of {@code blocks}, as JSON. */
  static String assemble(ContentBlocks blocks) {
    var precedence =
        List.<Source>of(blocks.verbatim(), blocks.text(), blocks.thinking(), blocks.toolUses());
    var indices = new TreeSet<Integer>();
    precedence.forEach(source -> indices.addAll(source.indices()));
    var echoed = new ArrayList<Map<String, Object>>();
    for (var index : indices) {
      precedence.stream()
          .filter(source -> source.indices().contains(index))
          .findFirst()
          .map(source -> source.echo(index))
          .ifPresent(echoed::add);
    }
    return AnthropicJson.LENIENT.writeValueAsString(echoed);
  }

  /**
   * Whether a signed thinking block arrived after text or a client tool call. The typed echo puts
   * every thinking block first, which would pull such a block — typically the note a model writes
   * before a later parallel tool call — away from the content it introduces.
   */
  private static boolean interleaved(ContentBlocks blocks) {
    var firstOther = Math.min(blocks.text().firstWritten(), blocks.toolUses().firstCompleted());
    return firstOther < blocks.thinking().lastSigned();
  }
}
