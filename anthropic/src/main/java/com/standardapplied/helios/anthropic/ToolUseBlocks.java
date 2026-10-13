/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.provider.ToolArguments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** The client {@code tool_use} blocks of a streamed turn, each a tool call once it closes. */
final class ToolUseBlocks implements RawContentEcho.Source {

  private record Pending(String id, String name, StringBuilder input) {}

  private final Map<Integer, Pending> pending = new HashMap<>();
  private final TreeMap<Integer, ToolCall> completed = new TreeMap<>();
  private final List<ToolCall> calls = new ArrayList<>();

  /**
   * The input of tool use {@code name}, streamed as {@code json}: a JSON object, empty when none
   * streamed.
   *
   * @throws AnthropicException if {@code json} is not a JSON object
   */
  static Map<String, Object> arguments(String name, String json) {
    return ToolArguments.read(
        name, json, AnthropicJson.STRUCTURED.reader(), AnthropicException::new);
  }

  /** A tool-use block opening at {@code index}: the start of its call. */
  StreamEvent start(int index, String id, String name) {
    pending.put(index, new Pending(id, name, new StringBuilder()));
    return new StreamEvent.ToolCallStart(id, name);
  }

  /** Input JSON streamed into the open block at {@code index}: its delta, if the block is open. */
  StreamEvent appendInput(int index, String json) {
    var block = pending.get(index);
    if (block == null) {
      return null;
    }
    block.input().append(json);
    return new StreamEvent.ToolCallDelta(block.id(), json);
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
            .withArguments(arguments(block.name(), block.input().toString()))
            .build();
    calls.add(call);
    completed.put(index, call);
    return new StreamEvent.ToolCallComplete(call);
  }

  /**
   * The name of a tool-use block still open, {@code <unnamed>} when it has none, or {@code null}
   * when no block is open.
   */
  String open() {
    return pending.isEmpty()
        ? null
        : Objects.requireNonNullElse(pending.values().iterator().next().name(), "<unnamed>");
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
