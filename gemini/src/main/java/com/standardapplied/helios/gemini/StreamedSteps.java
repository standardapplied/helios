/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.provider.ToolArguments;
import com.standardapplied.helios.gemini.api.ContentItem;
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.Step;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * The steps of a streamed interaction, by index, from {@code step.start} through {@code step.delta}
 * to {@code step.stop}: model output streams as text, a thought as thinking, and a function call as
 * arguments that become a tool call when its step stops.
 */
final class StreamedSteps {

  /** The partial state of one streaming step. */
  private static final class StepState {
    private final StringBuilder streamedArguments = new StringBuilder();
    private String type;
    private String id;
    private String name;
    private Map<String, Object> startArguments;

    boolean isCall() {
      return "function_call".equals(type);
    }

    /** {@code fragment} streamed into the arguments of this call, as its delta. */
    StreamEvent stream(String fragment) {
      streamedArguments.append(fragment);
      return new StreamEvent.ToolCallDelta(id, fragment);
    }

    /**
     * The streamed arguments, or the arguments the step started with when none streamed.
     *
     * @throws GeminiException if the streamed arguments are not a JSON object
     */
    Map<String, Object> arguments() {
      if (!streamedArguments.isEmpty()) {
        return read(streamedArguments.toString());
      }
      return startArguments != null ? startArguments : Map.of();
    }

    /**
     * {@code json} as the arguments of this call.
     *
     * @throws GeminiException if {@code json} is not a JSON object
     */
    Map<String, Object> read(String json) {
      return ToolArguments.read(name, json, GeminiJson.STRUCTURED.reader(), GeminiException::new);
    }
  }

  private final Map<Integer, StepState> steps = new LinkedHashMap<>();
  private final StepOutput output = new StepOutput();

  /**
   * A {@code step.start}.
   *
   * @throws GeminiException if a function call starts with arguments that are not a JSON object
   */
  StreamEvent start(Integer index, Step step) {
    if (index == null || step == null) {
      return null;
    }
    var state = steps.computeIfAbsent(index, i -> new StepState());
    state.type = step.type();
    state.name = step.name();
    state.id = step.id();
    state.startArguments =
        state.isCall() && step.arguments() != null ? state.read(step.arguments().toString()) : null;
    if (step.hasTypeThought()) {
      return thought(step);
    }
    if (state.isCall()) {
      return new StreamEvent.ToolCallStart(state.id, state.name);
    }
    return step.hasTypeModelOutput() && step.hasContent() ? output.initialText(step) : null;
  }

  /** A {@code step.delta} carrying {@code argumentsDelta} or {@code delta}. */
  StreamEvent delta(Integer index, String argumentsDelta, ContentItem delta) {
    if (index == null) {
      return null;
    }
    var state = steps.get(index);
    var isCall = state != null && state.isCall();
    if (argumentsDelta != null && isCall) {
      return state.stream(argumentsDelta);
    }
    if (delta == null) {
      return null;
    }
    return switch (delta.type()) {
      case "arguments_delta" -> {
        if (!isCall || delta.arguments() == null) {
          yield annotations(delta);
        }
        yield state.stream(delta.arguments());
      }
      case "thought_signature" -> signature(delta);
      case "thought_summary" -> thinking(delta.content());
      case "text" -> delta.text() == null ? annotations(delta) : text(state, delta);
      case null, default -> annotations(delta);
    };
  }

  /** A {@code step.stop}: the tool call of a function call, or a completed thought. */
  StreamEvent stop(Integer index) {
    var state = index == null ? null : steps.remove(index);
    if (state == null) {
      return null;
    }
    if (state.isCall()) {
      return output.call(
          ToolCall.newBuilder()
              .withId(state.id)
              .withName(state.name)
              .withArguments(state.arguments())
              .build());
    }
    return "thought".equals(state.type) ? output.thinkingComplete() : null;
  }

  /**
   * The name of a function call whose step is still open, {@code <unnamed>} when it has none, or
   * {@code null} when none is open.
   */
  String openCall() {
    return steps.values().stream()
        .filter(StepState::isCall)
        .map(state -> Objects.requireNonNullElse(state.name, "<unnamed>"))
        .findFirst()
        .orElse(null);
  }

  StepOutput.Result result() {
    return output.result();
  }

  /** The summary a thought starts with, one item per line, as thinking. */
  private StreamEvent thought(Step step) {
    output.signature(step.signature());
    var summary = new StringJoiner("\n");
    if (step.hasSummary()) {
      for (var item : step.summary()) {
        if (item.hasTypeText() && item.text() != null && !item.text().isEmpty()) {
          output.thought(item.text());
          summary.add(item.text());
        }
      }
    }
    return summary.length() == 0 ? null : new StreamEvent.ThinkingDelta(summary.toString());
  }

  private StreamEvent signature(ContentItem delta) {
    if (delta.signature() == null || delta.signature().isEmpty()) {
      return annotations(delta);
    }
    output.signature(delta.signature());
    return null;
  }

  private StreamEvent text(StepState state, ContentItem delta) {
    if (state != null && "thought".equals(state.type)) {
      return thinking(delta);
    }
    var event = output.text(delta.text());
    annotations(delta);
    return event;
  }

  /** The text of a thought, or of a thought summary's content, as thinking. */
  private StreamEvent thinking(ContentItem item) {
    if (item == null || !item.hasTypeText() || item.text() == null) {
      return null;
    }
    output.thought(item.text());
    return new StreamEvent.ThinkingDelta(item.text());
  }

  private StreamEvent annotations(ContentItem delta) {
    if (delta.hasAnnotations()) {
      output.cite(delta);
    }
    return null;
  }
}
