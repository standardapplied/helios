/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.provider.ToolArguments;
import com.standardapplied.helios.core.provider.ToolCallStarts;
import com.standardapplied.helios.openai.api.OpenAIJson;
import com.standardapplied.helios.openai.api.OutputItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The function calls of a streamed response, keyed by output item: each announced when its item is
 * added, its arguments streamed, and a tool call once its arguments are done.
 */
final class FunctionCalls {

  private record Pending(String callId, String name, StringBuilder arguments) {}

  private final Map<String, Pending> pending = new LinkedHashMap<>();
  private final List<ToolCall> calls = new ArrayList<>();

  /**
   * An added output item: the start of a call when it is a function call.
   *
   * @throws OpenAIException if the function call has no call id or no name
   */
  StreamEvent added(OutputItem item) {
    if (item == null || !item.hasTypeFunctionCall()) {
      return null;
    }
    var started = ToolCallStarts.of(item.callId(), item.name(), OpenAIException::new);
    pending.put(item.id(), new Pending(item.callId(), item.name(), new StringBuilder()));
    return started;
  }

  /** Arguments streamed into the call of item {@code itemId}, if it is open. */
  StreamEvent argumentsDelta(String itemId, String delta) {
    if (delta != null && itemId != null) {
      var call = pending.get(itemId);
      if (call != null) {
        call.arguments().append(delta);
        return new StreamEvent.ToolCallDelta(call.callId(), delta);
      }
    }
    return null;
  }

  /**
   * The end of the arguments of item {@code itemId}, finalized as {@code arguments} or, when the
   * event carries none, as streamed: its tool call, if it was open.
   *
   * @throws OpenAIException if the arguments are not a JSON object
   */
  StreamEvent argumentsDone(String itemId, String arguments) {
    var call = itemId == null ? null : pending.remove(itemId);
    if (call == null) {
      return null;
    }
    var json = arguments != null ? arguments : call.arguments().toString();
    var toolCall =
        ToolCall.newBuilder()
            .withId(call.callId())
            .withName(call.name())
            .withArguments(
                ToolArguments.read(
                    call.name(), json, OpenAIJson.STRUCTURED.reader(), OpenAIException::new))
            .build();
    calls.add(toolCall);
    return new StreamEvent.ToolCallComplete(toolCall);
  }

  /**
   * The name of a function call whose arguments are still open, {@code <unnamed>} when it has none,
   * or {@code null} when no call is open.
   */
  String open() {
    return pending.isEmpty() ? null : pending.values().iterator().next().name();
  }

  /** The tool calls, in the order their arguments completed. */
  List<ToolCall> calls() {
    return List.copyOf(calls);
  }
}
