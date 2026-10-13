/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.provider.ToolArguments;
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.InteractionResponse;
import com.standardapplied.helios.gemini.api.InteractionUsage;
import com.standardapplied.helios.gemini.api.StreamingEvent;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Parses one Interactions API stream, one event at a time, into {@link StreamEvent}s: the step
 * events through {@link StreamedSteps}, the interaction's id, status and usage from its envelope
 * and status updates, and the assembled response at {@code interaction.completed}. The stream
 * succeeds only there, with a status the assembler counts as an ending and no function call still
 * open; any other status, an unreadable event and function arguments that are not a JSON object end
 * it in an error, and an {@code error} event in one whose {@link GeminiException} carries the
 * reported code.
 */
final class GeminiStreamParser implements SseReader.Parser {

  private final boolean propagateInteractionId;
  private final String apiVersion;
  private final StreamedSteps steps = new StreamedSteps();
  private InteractionUsage usage;
  private String interactionId;
  private String status;

  /**
   * A parser for a stream of the interactions API {@code apiVersion}, whose response records its
   * interaction id when {@code propagateInteractionId} is set, for server-side continuation.
   */
  GeminiStreamParser(boolean propagateInteractionId, String apiVersion) {
    this.propagateInteractionId = propagateInteractionId;
    this.apiVersion = apiVersion;
  }

  @Override
  public StreamEvent parse(String payload) {
    try {
      var event = GeminiJson.LENIENT.readTree(payload);
      checkStartArguments(event);
      return on(GeminiJson.LENIENT.treeToValue(event, StreamingEvent.class));
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

  /**
   * Reads the arguments a function call starts with as {@link ToolArguments} reads streamed ones,
   * before deserialization reads anything but a JSON object as no arguments.
   */
  private static void checkStartArguments(JsonNode event) {
    var step = event.path("step");
    var arguments = step.get("arguments");
    if (arguments != null && "function_call".equals(step.path("type").asString())) {
      ToolArguments.read(
          step.path("name").asString(),
          arguments.isString() ? arguments.asString() : arguments.toString(),
          GeminiJson.STRUCTURED.reader(),
          GeminiException::new);
    }
  }

  private StreamEvent on(StreamingEvent event) {
    return switch (event.eventType()) {
      case "error" -> error(event.error());
      case "interaction.created" -> envelope(event.interaction());
      case "interaction.completed" -> completed(event.interaction());
      case "interaction.status_update", "interaction.in_progress", "interaction.requires_action" ->
          statusUpdate(event.interactionId(), event.status());
      case "step.start" -> steps.start(event.index(), event.step());
      case "step.delta" -> steps.delta(event.index(), event.argumentsDelta(), event.delta());
      case "step.stop" -> steps.stop(event.index());
      case null, default -> null;
    };
  }

  private StreamEvent error(Map<String, Object> error) {
    var message = "API error";
    var statusCode = 0;
    if (error != null) {
      if (error.get("message") != null) {
        message = "API error: " + error.get("message");
      }
      if (error.get("code") instanceof Number code && code.intValue() > 0) {
        statusCode = code.intValue();
      }
    }
    return failed(message, new GeminiException(message, statusCode));
  }

  private StreamEvent completed(InteractionResponse interaction) {
    envelope(interaction);
    var open = steps.openCall();
    if (open != null) {
      return failed("Interaction completed while function call '" + open + "' is still open", null);
    }
    var finishReason =
        GeminiResponseAssembler.finishReason(status, !steps.result().calls().isEmpty());
    if (finishReason == null) {
      return failed("Interaction completed with status " + status, null);
    }
    return new StreamEvent.Done(response(finishReason));
  }

  private StreamEvent failed(String message, Exception cause) {
    return new StreamEvent.Error(message, cause, partial());
  }

  private Response<Void> response(FinishReason finishReason) {
    return GeminiResponseAssembler.response(
        steps.result(),
        usage,
        propagateInteractionId ? interactionId : null,
        apiVersion,
        finishReason);
  }

  private StreamEvent envelope(InteractionResponse interaction) {
    if (interaction != null) {
      statusUpdate(interaction.id(), interaction.status());
      if (interaction.usage() != null) {
        usage = interaction.usage();
      }
    }
    return null;
  }

  private StreamEvent statusUpdate(String id, String newStatus) {
    if (id != null) {
      interactionId = id;
    }
    if (newStatus != null) {
      status = newStatus;
    }
    return null;
  }
}
