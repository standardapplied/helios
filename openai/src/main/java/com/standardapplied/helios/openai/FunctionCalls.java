/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.openai.api.OpenAIJson;
import com.standardapplied.helios.openai.api.OutputItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The function calls of a streamed response, keyed by output item: each announced when its item is
 * added, its arguments streamed, and a tool call once its arguments are done.
 */
final class FunctionCalls {

  private record Pending(String callId, String name, StringBuilder arguments) {}

  private final Map<String, Pending> pending = new HashMap<>();
  private final List<ToolCall> calls = new ArrayList<>();

  /** An added output item: the start of a call when it is a function call. */
  StreamEvent added(OutputItem item) {
    if (item == null || !item.hasTypeFunctionCall()) {
      return null;
    }
    pending.put(item.id(), new Pending(item.callId(), item.name(), new StringBuilder()));
    return new StreamEvent.ToolCallStart(item.callId(), item.name());
  }

  /** Arguments streamed into the call of item {@code itemId}, if it is open. */
  StreamEvent argumentsDelta(String itemId, String delta) {
    if (delta != null && itemId != null) {
      var call = pending.get(itemId);
      if (call != null) {
        call.arguments().append(delta);
      }
    }
    return null;
  }

  /** The end of the arguments of item {@code itemId}: its tool call, if it was open. */
  StreamEvent argumentsDone(String itemId) {
    var call = itemId == null ? null : pending.remove(itemId);
    if (call == null) {
      return null;
    }
    var toolCall =
        ToolCall.newBuilder()
            .withId(call.callId())
            .withName(call.name())
            .withArguments(arguments(call.arguments().toString()))
            .build();
    calls.add(toolCall);
    return new StreamEvent.ToolCallComplete(toolCall);
  }

  /** The tool calls, in the order their arguments completed. */
  List<ToolCall> calls() {
    return List.copyOf(calls);
  }

  /**
   * The JSON object {@code json} as a map: empty for no arguments, and {@code {"_raw": json}} for
   * arguments that are not a JSON object, so the model sees what it sent.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> arguments(String json) {
    if (json.isEmpty()) {
      return Map.of();
    }
    try {
      return OpenAIJson.LENIENT.readValue(json, Map.class);
    } catch (RuntimeException e) {
      return Map.of("_raw", json);
    }
  }
}
