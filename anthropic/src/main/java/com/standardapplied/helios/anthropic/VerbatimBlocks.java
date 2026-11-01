/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.ContentBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The blocks of a streamed turn that must go back exactly as they arrived: each {@code
 * server_tool_use}, and each block delivered whole when it opens — every server-tool result and
 * {@code redacted_thinking}. Dropping a result leaves its server tool use unpaired, which the API
 * rejects.
 */
final class VerbatimBlocks implements RawContentEcho.Source {

  private final TreeMap<Integer, Map<String, Object>> blocks = new TreeMap<>();
  private final Map<Integer, StringBuilder> serverToolInputs = new HashMap<>();
  private boolean seen;

  /** Whether a block of {@code type} is delivered whole when it opens. */
  static boolean arrivesWhole(String type) {
    return type != null && (type.endsWith("_tool_result") || type.equals("redacted_thinking"));
  }

  /**
   * A server tool use opening at {@code index}, kept whole from {@code payload}. One Claude calls
   * directly streams its input until it closes; one called from code execution opens with its input
   * and {@code caller} and streams none, and goes back with both or the API rejects the turn.
   */
  void startServerToolUse(int index, String payload) {
    capture(index, payload);
    serverToolInputs.put(index, new StringBuilder());
  }

  /**
   * A whole block at {@code index}, taken from the raw event {@code payload}: the typed {@link
   * ContentBlock} drops fields it does not model, such as {@code encrypted_content} and a redacted
   * block's {@code data}. The payload has already been read as a {@code content_block_start}, so it
   * holds the block as a JSON object.
   */
  @SuppressWarnings("unchecked")
  void capture(int index, String payload) {
    seen = true;
    var event = (Map<String, Object>) AnthropicJson.LENIENT.readValue(payload, Map.class);
    blocks.put(index, (Map<String, Object>) event.get("content_block"));
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
    var block = blocks.get(index);
    if (!input.isEmpty() || !block.containsKey("input")) {
      block.put("input", ToolUseBlocks.arguments((String) block.get("name"), input.toString()));
    }
    return true;
  }

  /**
   * The name of a server tool use still open, {@code <unnamed>} when it has none, or {@code null}
   * when none is open.
   */
  String open() {
    return serverToolInputs.isEmpty()
        ? null
        : Objects.requireNonNullElse(
            (String) blocks.get(serverToolInputs.keySet().iterator().next()).get("name"),
            "<unnamed>");
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
