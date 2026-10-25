/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.gemini.api.ContentItem;
import com.standardapplied.helios.gemini.api.Step;
import java.util.ArrayList;
import java.util.List;

/**
 * What the steps of a streamed interaction produce: text, thinking, signatures, citations, calls.
 */
final class StepOutput {

  /**
   * The output of a finished stream.
   *
   * @param content the text
   * @param calls the tool calls, in the order their steps stopped
   * @param thinking the thought summaries and text, one per line, or {@code null} without any
   * @param signatures the thought signatures, in arrival order
   * @param citations the citations, in arrival order
   */
  record Result(
      String content,
      List<ToolCall> calls,
      String thinking,
      List<String> signatures,
      List<Citation> citations) {}

  private final StringBuilder content = new StringBuilder();
  private final List<ToolCall> calls = new ArrayList<>();
  private final List<String> signatures = new ArrayList<>();
  private final List<Citation> citations = new ArrayList<>();
  private String thinking;

  /** {@code text} added to the output text. */
  StreamEvent text(String text) {
    content.append(text);
    return new StreamEvent.TextDelta(text);
  }

  /**
   * The text a {@code model_output} step starts with, as one delta, its annotations harvested along
   * the way.
   */
  StreamEvent initialText(Step step) {
    var initialText = new StringBuilder();
    for (var item : step.content()) {
      if (item.hasTypeText() && item.text() != null && !item.text().isEmpty()) {
        initialText.append(item.text());
      }
      if (item.hasAnnotations()) {
        cite(item);
      }
    }
    return initialText.isEmpty() ? null : text(initialText.toString());
  }

  /**
   * {@code text} added to the thinking on a line of its own: what the thinking grew by, the newline
   * before it included.
   */
  String thought(String text) {
    var added = thinking == null ? text : "\n" + text;
    thinking = thinking == null ? added : thinking + added;
    return added;
  }

  /** A thought signature, kept when it is not empty. */
  void signature(String signature) {
    if (signature != null && !signature.isEmpty()) {
      signatures.add(signature);
    }
  }

  /** The citations among the annotations of {@code item}. */
  void cite(ContentItem item) {
    citations.addAll(GeminiCitations.of(item.annotations()));
  }

  /** A completed tool call. */
  StreamEvent call(ToolCall call) {
    calls.add(call);
    return new StreamEvent.ToolCallComplete(call);
  }

  Result result() {
    return new Result(
        content.toString(),
        List.copyOf(calls),
        thinking,
        List.copyOf(signatures),
        List.copyOf(citations));
  }
}
