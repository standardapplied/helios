/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.gemini.api.ContentItem;
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.Step;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * The streamed arguments, or the arguments the step started with when none streamed or they are
     * not a JSON object.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> arguments() {
      if (!streamedArguments.isEmpty()) {
        try {
          var parsed =
              (Map<String, Object>)
                  GeminiJson.LENIENT.readValue(streamedArguments.toString(), Map.class);
          if (parsed != null) {
            return parsed;
          }
        } catch (RuntimeException notAnObject) {
          // Fall back to the arguments the step started with.
        }
      }
      return startArguments != null ? startArguments : Map.of();
    }
  }

  private final Map<Integer, StepState> steps = new LinkedHashMap<>();
  private final StepOutput output = new StepOutput();

  /** A {@code step.start}. */
  StreamEvent start(Integer index, Step step) {
    if (index == null || step == null) {
      return null;
    }
    var state = steps.computeIfAbsent(index, i -> new StepState());
    state.type = step.type();
    state.name = step.name();
    state.id = step.id();
    state.startArguments = step.arguments();
    if (step.hasTypeThought()) {
      thought(step);
      return null;
    }
    return step.hasTypeModelOutput() && step.hasContent() ? initialOutput(step) : null;
  }

  /** A {@code step.delta} carrying {@code argumentsDelta} or {@code delta}. */
  StreamEvent delta(Integer index, String argumentsDelta, ContentItem delta) {
    if (index == null) {
      return null;
    }
    var state = steps.get(index);
    var isCall = state != null && state.isCall();
    if (argumentsDelta != null && isCall) {
      state.streamedArguments.append(argumentsDelta);
      return null;
    }
    if (delta == null) {
      return null;
    }
    return switch (delta.type()) {
      case "arguments_delta" -> {
        if (!isCall || delta.arguments() == null) {
          yield annotations(delta);
        }
        state.streamedArguments.append(delta.arguments());
        yield null;
      }
      case "thought_signature" -> signature(delta);
      case "thought_summary" -> thinking(delta.content());
      case "text" -> delta.text() == null ? annotations(delta) : text(state, delta);
      case null, default -> annotations(delta);
    };
  }

  /** A {@code step.stop}: the tool call of a function call, or a completed thought. */
  StreamEvent stop(Integer index) {
    var state = index == null ? null : steps.get(index);
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

  StepOutput.Result result() {
    return output.result();
  }

  /**
   * The text a {@code model_output} step starts with, as one delta, its annotations harvested along
   * the way.
   */
  private StreamEvent initialOutput(Step step) {
    var initialText = new StringBuilder();
    for (var item : step.content()) {
      if (item.hasTypeText() && item.text() != null && !item.text().isEmpty()) {
        initialText.append(item.text());
      }
      if (item.hasAnnotations()) {
        output.cite(item);
      }
    }
    return initialText.isEmpty() ? null : output.text(initialText.toString());
  }

  private void thought(Step step) {
    output.signature(step.signature());
    if (step.hasSummary()) {
      for (var item : step.summary()) {
        if (item.hasTypeText() && item.text() != null && !item.text().isEmpty()) {
          output.thought(item.text());
        }
      }
    }
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
