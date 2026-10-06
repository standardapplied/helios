/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The client {@code tool_use} blocks of a streamed turn, each a tool call once it closes. */
final class ToolUseBlocks implements RawContentEcho.Source {

  private record Pending(String id, String name, StringBuilder input) {}

  private final Map<Integer, Pending> pending = new HashMap<>();
  private final TreeMap<Integer, ToolCall> completed = new TreeMap<>();
  private final List<ToolCall> calls = new ArrayList<>();

  /**
   * The JSON object {@code json} as a map: empty for no input, and {@code {"_raw": json}} for input
   * that is not a JSON object, so the model sees what it sent.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> arguments(String json) {
    if (json.isEmpty()) {
      return Map.of();
    }
    try {
      return AnthropicJson.LENIENT.readValue(json, Map.class);
    } catch (RuntimeException e) {
      return Map.of("_raw", json);
    }
  }

  /** A tool-use block opening at {@code index}. */
  void start(int index, String id, String name) {
    pending.put(index, new Pending(id, name, new StringBuilder()));
  }

  /** Input JSON streamed into the open block at {@code index}, if there is one. */
  void appendInput(int index, String json) {
    var block = pending.get(index);
    if (block != null) {
      block.input().append(json);
    }
  }

  /** The closing of the block at {@code index}: its tool call, or none if no block is open. */
  StreamEvent stop(int index) {
    var block = pending.remove(index);
    if (block == null) {
      return null;
    }
    var call =
        ToolCall.newBuilder()
            .withId(block.id())
            .withName(block.name())
            .withArguments(arguments(block.input().toString()))
            .build();
    calls.add(call);
    completed.put(index, call);
    return new StreamEvent.ToolCallComplete(call);
  }

  /** The tool calls, in the order they closed. */
  List<ToolCall> calls() {
    return List.copyOf(calls);
  }

  /** The index of the first closed block, or {@link Integer#MAX_VALUE} when none has closed. */
  int firstCompleted() {
    return completed.isEmpty() ? Integer.MAX_VALUE : completed.firstKey();
  }

  @Override
  public Set<Integer> indices() {
    return completed.keySet();
  }

  @Override
  public Map<String, Object> echo(int index) {
    var call = completed.get(index);
    var block = new LinkedHashMap<String, Object>();
    block.put("type", "tool_use");
    block.put("id", call.id());
    block.put("name", call.name());
    block.put("input", call.arguments());
    return block;
  }
}
