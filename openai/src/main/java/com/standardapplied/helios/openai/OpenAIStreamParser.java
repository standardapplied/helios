/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.openai.api.ApiStreamEvent;
import com.standardapplied.helios.openai.api.ApiUsage;
import com.standardapplied.helios.openai.api.OpenAIJson;
import com.standardapplied.helios.openai.api.ResponsesResponse;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses one Responses API stream, one event at a time, into {@link StreamEvent}s: text, reasoning
 * summaries and function calls as they stream, and the assembled response at the terminal event.
 * {@code response.completed} with status {@code completed} ends the turn, refused when the model
 * streamed a refusal; {@code response.incomplete} with status {@code incomplete} ends it truncated
 * or filtered, with its usage. Either terminal with another status, {@code response.failed}, an
 * {@code error} event, an unreadable event, function arguments that are not a JSON object and a
 * terminal reached while a function call is still open end the stream in an error.
 */
final class OpenAIStreamParser implements SseReader.Parser {

  private final OutputMessage message = new OutputMessage();
  private final ReasoningSummary reasoning = new ReasoningSummary();
  private final FunctionCalls functionCalls = new FunctionCalls();
  private ApiUsage usage;
  private String incompleteReason;

  @Override
  public StreamEvent parse(String payload) {
    try {
      return on(OpenAIJson.LENIENT.readValue(payload, ApiStreamEvent.class));
    } catch (ProviderException e) {
      return failed(e.getMessage(), e);
    } catch (RuntimeException e) {
      return failed("Failed to parse stream event", e);
    }
  }

  @Override
  public Response<Void> partial() {
    return response(FinishReason.ERROR);
  }

  private StreamEvent on(ApiStreamEvent event) {
    return switch (event.type()) {
      case "response.output_text.delta" -> message.text(event.delta());
      case "response.refusal.delta" -> message.refusalDelta(event.delta());
      case "response.refusal.done" -> message.refusalDone(event.refusal());
      case "response.output_item.added" -> functionCalls.added(event.item());
      case "response.function_call_arguments.delta" ->
          functionCalls.argumentsDelta(event.itemId(), event.delta());
      case "response.function_call_arguments.done" ->
          functionCalls.argumentsDone(event.itemId(), event.arguments());
      case "response.reasoning_summary_text.delta" ->
          reasoning.delta(event.itemId(), event.summaryIndex(), event.delta());
      case "response.reasoning_summary_text.done" ->
          reasoning.done(event.itemId(), event.summaryIndex(), event.text());
      case "response.completed" -> completed(event.response());
      case "response.incomplete" -> incomplete(event.response());
      case "response.failed" -> {
        record(event.response());
        yield apiError(
            "API response failed", event.response() == null ? null : event.response().error());
      }
      case "error" ->
          apiError(
              "API stream error",
              new ResponsesResponse.ResponseError(event.code(), event.message()));
      case null, default -> null;
    };
  }

  private StreamEvent completed(ResponsesResponse response) {
    record(response);
    var status = response == null ? null : response.status();
    if (!"completed".equals(status)) {
      return failed("response.completed with status " + status, null);
    }
    return terminal(
        OpenAIResponseAssembler.completedReason(
            !message.refusal().isEmpty(), !functionCalls.calls().isEmpty()));
  }

  private StreamEvent incomplete(ResponsesResponse response) {
    record(response);
    var status = response == null ? null : response.status();
    if (!"incomplete".equals(status)) {
      return failed("response.incomplete with status " + status, null);
    }
    if (response.incompleteDetails() != null) {
      incompleteReason = response.incompleteDetails().reason();
    }
    return terminal(OpenAIResponseAssembler.incompleteReason(incompleteReason));
  }

  private StreamEvent apiError(String summary, ResponsesResponse.ResponseError error) {
    var details = error == null ? Stream.<String>empty() : Stream.of(error.code(), error.message());
    return failed(
        Stream.concat(Stream.of(summary), details)
            .filter(Objects::nonNull)
            .collect(Collectors.joining(": ")),
        null);
  }

  private StreamEvent terminal(FinishReason finishReason) {
    var open = functionCalls.open();
    if (open != null) {
      return failed("Response ended while function call '" + open + "' is still open", null);
    }
    return new StreamEvent.Done(response(finishReason));
  }

  private void record(ResponsesResponse response) {
    if (response != null && response.usage() != null) {
      usage = response.usage();
    }
  }

  private StreamEvent failed(String message, Exception cause) {
    return new StreamEvent.Error(message, cause, partial());
  }

  private Response<Void> response(FinishReason finishReason) {
    return OpenAIResponseAssembler.response(
        new OpenAIResponseAssembler.Turn(
            message.text(),
            functionCalls.calls(),
            reasoning.text(),
            message.refusal(),
            incompleteReason,
            usage),
        finishReason);
  }
}
