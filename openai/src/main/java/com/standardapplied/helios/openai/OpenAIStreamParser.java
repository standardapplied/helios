/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.openai.api.ApiStreamEvent;
import com.standardapplied.helios.openai.api.ApiUsage;
import com.standardapplied.helios.openai.api.OpenAIJson;
import com.standardapplied.helios.openai.api.ResponsesResponse;

/**
 * Parses one Responses API stream, one event at a time, into {@link StreamEvent}s: text, reasoning
 * summaries and function calls as they stream, and the assembled response at {@code
 * response.completed} or the end of the body. {@code response.failed} ends the stream with an
 * error; the stream reads on after an {@code error} event.
 */
final class OpenAIStreamParser implements SseReader.Parser {

  private final StringBuilder content = new StringBuilder();
  private final StringBuilder reasoning = new StringBuilder();
  private final FunctionCalls functionCalls = new FunctionCalls();
  private ApiUsage usage;
  private String status;
  private boolean finished;

  @Override
  public StreamEvent parse(String payload) {
    try {
      return on(OpenAIJson.LENIENT.readValue(payload, ApiStreamEvent.class), payload);
    } catch (RuntimeException e) {
      return new StreamEvent.Error("Failed to parse stream event", e);
    }
  }

  @Override
  public boolean finished() {
    return finished;
  }

  @Override
  public StreamEvent complete() {
    return OpenAIResponseAssembler.done(
        content.toString(), functionCalls.calls(), reasoning.toString(), usage, status);
  }

  private StreamEvent on(ApiStreamEvent event, String payload) {
    return switch (event.type()) {
      case "response.output_text.delta" -> text(event.delta());
      case "response.output_item.added" -> functionCalls.added(event.item());
      case "response.function_call_arguments.delta" ->
          functionCalls.argumentsDelta(event.itemId(), event.delta());
      case "response.function_call_arguments.done" -> functionCalls.argumentsDone(event.itemId());
      case "response.completed" -> completed(event.response());
      case "response.failed" -> {
        finished = true;
        yield new StreamEvent.Error("API response failed: " + payload, null);
      }
      case "error" -> new StreamEvent.Error("API stream error: " + payload, null);
      case "response.reasoning_summary_text.delta" -> reasoningDelta(event.text());
      case "response.reasoning_summary_text.done" ->
          reasoning.isEmpty() ? null : new StreamEvent.ThinkingComplete(reasoning.toString(), null);
      case null, default -> null;
    };
  }

  private StreamEvent text(String delta) {
    if (delta == null) {
      return null;
    }
    content.append(delta);
    return new StreamEvent.TextDelta(delta);
  }

  /**
   * The reasoning summary so far. The Responses API signs no reasoning summary, so its completion
   * carries no signature.
   */
  private StreamEvent reasoningDelta(String text) {
    if (text == null) {
      return null;
    }
    reasoning.append(text);
    return new StreamEvent.ThinkingDelta(text);
  }

  private StreamEvent completed(ResponsesResponse response) {
    if (response != null) {
      status = response.status();
      if (response.usage() != null) {
        usage = response.usage();
      }
    }
    finished = true;
    return complete();
  }
}
