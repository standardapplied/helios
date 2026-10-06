/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.ContentBlock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The blocks of a streamed turn that must go back exactly as they arrived: each {@code
 * server_tool_use} with its streamed input, and each block delivered whole when it opens — every
 * server-tool result and {@code redacted_thinking}. Dropping a result leaves its server tool use
 * unpaired, which the API rejects.
 */
final class VerbatimBlocks implements RawContentEcho.Source {

  private final TreeMap<Integer, Map<String, Object>> blocks = new TreeMap<>();
  private final Map<Integer, StringBuilder> serverToolInputs = new HashMap<>();
  private boolean seen;

  /** Whether a block of {@code type} is delivered whole when it opens. */
  static boolean arrivesWhole(String type) {
    return type != null && (type.endsWith("_tool_result") || type.equals("redacted_thinking"));
  }

  /** A server tool use opening at {@code index}; its input streams in until it closes. */
  void startServerToolUse(int index, ContentBlock block) {
    seen = true;
    var raw = new LinkedHashMap<String, Object>();
    raw.put("type", "server_tool_use");
    raw.put("id", block.id());
    raw.put("name", block.name());
    blocks.put(index, raw);
    serverToolInputs.put(index, new StringBuilder());
  }

  /**
   * A whole block at {@code index}, taken from the raw event {@code payload}: the typed {@link
   * ContentBlock} drops fields it does not model, such as {@code encrypted_content} and a redacted
   * block's {@code data}. An unreadable block is left out, so a later echo fails loudly at the API
   * instead of sending corrupted content.
   */
  @SuppressWarnings("unchecked")
  void capture(int index, String payload) {
    seen = true;
    try {
      var event = (Map<String, Object>) AnthropicJson.LENIENT.readValue(payload, Map.class);
      blocks.put(index, (Map<String, Object>) event.get("content_block"));
    } catch (RuntimeException unreadable) {
      return;
    }
  }

  /** Input JSON for the server tool use at {@code index}; whether there is one to take it. */
  boolean appendInput(int index, String json) {
    var input = serverToolInputs.get(index);
    if (input == null) {
      return false;
    }
    input.append(json);
    return true;
  }

  /** The closing of the server tool use at {@code index}; whether there was one. */
  boolean stop(int index) {
    var input = serverToolInputs.remove(index);
    if (input == null) {
      return false;
    }
    blocks.get(index).put("input", ToolUseBlocks.arguments(input.toString()));
    return true;
  }

  /** Whether the turn held a verbatim block. */
  boolean seen() {
    return seen;
  }

  @Override
  public Set<Integer> indices() {
    return blocks.keySet();
  }

  @Override
  public Map<String, Object> echo(int index) {
    return blocks.get(index);
  }
}
